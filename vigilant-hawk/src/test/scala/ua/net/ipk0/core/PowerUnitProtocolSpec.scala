package ua.net.ipk0.core

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.{LWWMap, SelfUniqueAddress}
import akka.cluster.typed.{Cluster, Join}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ua.net.ipk0.core.PowerUnit._
import ua.net.ipk0.core.PowerUnit.Projection.Green

class PowerUnitProtocolSpec extends AnyWordSpec with Matchers {

  private val config: Config = ConfigFactory.parseString(
    """akka.remote.artery.canonical.port = 0
      |akka.coordinated-shutdown.exit-jvm = off
      |akka.loglevel = INFO
      |""".stripMargin
  ).withFallback(ConfigFactory.load("application-test"))

  private def withTestKit(test: ActorTestKit => Unit): Unit = {
    val testKit = ActorTestKit("power-unit-protocol", config)
    try {
      val cluster = Cluster(testKit.system)
      cluster.manager ! Join(cluster.selfMember.address)
      test(testKit)
    } finally testKit.shutdownTestKit()
  }

  private def spawnUnit(testKit: ActorTestKit, id: String, actualPower: Int, nominalPower: Int, greenPeers: Seq[Domain] = Nil): (ActorRef[Command], Domain) = {
    implicit val node: SelfUniqueAddress = DistributedData(testKit.system).selfUniqueAddress
    val grid = greenPeers.foldLeft(LWWMap.empty[Domain, StateProjection])((map, peer) => map :+ (peer -> StateProjection(Green)))
    val unit = testKit.spawn(PowerUnit(self => PowerUnitState(Domain(id, 0, self), actualPower, nominalPower, grid)), id)
    (unit, Domain(id, 0, unit))
  }

  private def fakePeer(testKit: ActorTestKit, id: String): (TestProbe[Command], Domain) = {
    val probe = testKit.createTestProbe[Command](id)
    (probe, Domain(id, 1, probe.ref))
  }

  private def orderPower(testKit: ActorTestKit, unit: ActorRef[Command], actualPower: Int): Unit = {
    val probe = testKit.createTestProbe[ExternalActualPowerOrderAck]()
    unit ! ExternalActualPowerOrder(actualPower, probe.ref)
    probe.receiveMessage()
  }

  private def awaitState(testKit: ActorTestKit, unit: ActorRef[Command])(assertion: PowerUnitState => Unit): Unit = {
    val probe = testKit.createTestProbe[UnitStateAck]()
    probe.awaitAssert {
      unit ! UnitStateReq(probe.ref)
      assertion(probe.receiveMessage().unitState)
    }
  }

  private def borrowFrom(lender: TestProbe[Command], lenderId: Domain, amount: Int): Unit = {
    val ask = lender.expectMessageType[EmergencyAsk]
    ask.amount shouldBe amount
    ask.replyTo ! EmergencyAck(lenderId, amount)
  }

  "A power unit borrowing power" must {
    "ask a green neighbour for the missing power and record the loan" in withTestKit { testKit =>
      val (lender, lenderId) = fakePeer(testKit, "lender")
      val (unit, unitId) = spawnUnit(testKit, "borrower", actualPower = 0, nominalPower = 100, greenPeers = Seq(lenderId))

      orderPower(testKit, unit, 150)

      val ask = lender.expectMessageType[EmergencyAsk]
      ask.requesterId shouldBe unitId
      ask.amount shouldBe 50
      ask.replyTo ! EmergencyAck(lenderId, 50)

      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 150
        state.assets.lenders shouldBe Map(lenderId -> 50)
      }
    }

    "lend spare power to a requester and record the debt" in withTestKit { testKit =>
      val (requester, requesterId) = fakePeer(testKit, "requester")
      val (unit, unitId) = spawnUnit(testKit, "lender", actualPower = 0, nominalPower = 200)

      unit ! EmergencyAsk(requesterId, 50, requester.ref)

      requester.expectMessage(EmergencyAck(unitId, 50))
      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 150
        state.assets.debtors shouldBe Map(requesterId -> 50)
      }
    }

    "refuse to lend and record no debt when it has no spare power" in withTestKit { testKit =>
      val (requester, requesterId) = fakePeer(testKit, "requester")
      val (unit, unitId) = spawnUnit(testKit, "lender", actualPower = 100, nominalPower = 100)

      unit ! EmergencyAsk(requesterId, 50, requester.ref)

      requester.expectMessage(EmergencyAck(unitId, 0))
      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 100
        state.assets.debtors shouldBe empty
      }
    }
  }

  "A power unit with borrowed or lent power" must {
    "push borrowed power back once it is no longer needed" in withTestKit { testKit =>
      val (lender, lenderId) = fakePeer(testKit, "lender")
      val (unit, unitId) = spawnUnit(testKit, "borrower", actualPower = 0, nominalPower = 100, greenPeers = Seq(lenderId))
      orderPower(testKit, unit, 150)
      borrowFrom(lender, lenderId, 50)
      awaitState(testKit, unit)(_.assets.lenders shouldBe Map(lenderId -> 50))

      orderPower(testKit, unit, 100)

      lender.expectMessage(PushRefund(unitId, 50))
      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 100
        state.assets.lenders shouldBe empty
      }
    }

    "pull lent power back when it turns red" in withTestKit { testKit =>
      val (requester, requesterId) = fakePeer(testKit, "requester")
      val (unit, unitId) = spawnUnit(testKit, "lender", actualPower = 0, nominalPower = 200)
      unit ! EmergencyAsk(requesterId, 50, requester.ref)
      requester.expectMessage(EmergencyAck(unitId, 50))

      orderPower(testKit, unit, 170)

      requester.expectMessage(PullRefund(unitId, 50))
      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 200
        state.assets.debtors shouldBe empty
      }
    }

    "keep a grant that arrives after the emergency is over and push it back" in withTestKit { testKit =>
      val (lender, lenderId) = fakePeer(testKit, "lender")
      val (unit, unitId) = spawnUnit(testKit, "borrower", actualPower = 0, nominalPower = 100)

      unit ! EmergencyAck(lenderId, 30)

      lender.expectMessage(PushRefund(unitId, 30))
      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 100
        state.assets.lenders shouldBe empty
      }
    }
  }

  "A power unit whose neighbour fails" must {
    "give up power borrowed from a lender that stopped" in withTestKit { testKit =>
      val (lender, lenderId) = fakePeer(testKit, "lender")
      val (unit, _) = spawnUnit(testKit, "borrower", actualPower = 0, nominalPower = 100, greenPeers = Seq(lenderId))
      orderPower(testKit, unit, 150)
      borrowFrom(lender, lenderId, 50)
      awaitState(testKit, unit)(_.assets.lenders shouldBe Map(lenderId -> 50))

      lender.stop()

      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 100
        state.assets.lenders shouldBe empty
      }
    }

    "take back power lent to a debtor that stopped" in withTestKit { testKit =>
      val (requester, requesterId) = fakePeer(testKit, "requester")
      val (unit, unitId) = spawnUnit(testKit, "lender", actualPower = 0, nominalPower = 200)
      unit ! EmergencyAsk(requesterId, 50, requester.ref)
      requester.expectMessage(EmergencyAck(unitId, 50))

      requester.stop()

      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 200
        state.assets.debtors shouldBe empty
      }
    }

    "survive and settle both sides when the stopped neighbour was lender and debtor" in withTestKit { testKit =>
      val (peer, peerId) = fakePeer(testKit, "peer")
      val (unit, unitId) = spawnUnit(testKit, "unit", actualPower = 0, nominalPower = 200)
      unit ! EmergencyAsk(peerId, 50, peer.ref)
      peer.expectMessage(EmergencyAck(unitId, 50))
      unit ! EmergencyAck(peerId, 20)

      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 170
        state.assets.debtors shouldBe Map(peerId -> 50)
        state.assets.lenders shouldBe Map(peerId -> 20)
      }

      peer.stop()

      awaitState(testKit, unit) { state =>
        state.nominalPower shouldBe 200
        state.assets.debtors shouldBe empty
        state.assets.lenders shouldBe empty
      }
    }
  }
}
