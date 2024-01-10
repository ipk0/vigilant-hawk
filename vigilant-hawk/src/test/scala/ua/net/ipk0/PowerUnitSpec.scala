package ua.net.ipk0

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.cluster.ddata.LWWMap
import akka.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ua.net.ipk0.core.PowerUnit
import ua.net.ipk0.core.PowerUnit.{Domain, ExternalActualPowerOrder, ExternalActualPowerOrderAck, PowerUnitState, StateProjection, Stop, UnitStateAck, UnitStateReq}

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.util.Random

class PowerUnitSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {
  val testKit = ActorTestKit("test")

  "Something" must {
    "behave correctly" in {
      val cluster = Cluster(testKit.system)
      cluster.manager.tell(Join.create(cluster.selfMember.address))

      val powerUnit = testKit.spawn(PowerUnit(self => PowerUnitState(Domain(UUID.randomUUID.toString, Random.nextInt(5), self), 0, 200, LWWMap.empty[Domain, StateProjection])), "unit")

      val probe = testKit.createTestProbe[ExternalActualPowerOrderAck]()

      powerUnit ! ExternalActualPowerOrder(2000, probe.ref)

      val message = probe.receiveMessage()
      println(message)

      val probe2 = testKit.createTestProbe[UnitStateAck]()
      powerUnit ! UnitStateReq(probe2.ref)

      val message2 = probe2.receiveMessage()
      println(message2)

      /*
      val powerUnit = testKit.spawn(PowerUnit(PowerUnitState(Domain(UUID.randomUUID.toString, Random.nextInt(5)), 0, 200, LWWMap.empty[Domain, StateProjection])), "unit")

      powerUnit ! ExternalActualPowerIncrease(2000)
*/
/*
      val probe = testKit.createTestProbe[Echo.Pong]()
      pinger ! Echo.Ping("hello", probe.ref)
      probe.expectMessage(Echo.Pong("hello"))
*/

//      testKit.stop(powerUnit, 10.seconds)

      Thread.sleep(60 * 1000)
    }
  }

  override def afterAll(): Unit = testKit.shutdownTestKit()
}
