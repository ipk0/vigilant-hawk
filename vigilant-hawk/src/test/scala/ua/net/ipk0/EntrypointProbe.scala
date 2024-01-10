package ua.net.ipk0

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ClusterEvent
import akka.cluster.ddata.{LWWMap, LWWMapKey}
import akka.cluster.typed.{Cluster, Subscribe}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import ua.net.ipk0.core.PowerUnit
import ua.net.ipk0.core.PowerUnit.{PowerUnitState, StateProjection, Domain}
import ua.net.ipk0.endpoint.NodeManagement
import ua.net.ipk0.metric.MetricManager

import java.util.UUID
import scala.util.Random
import akka.{actor => classic}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class EntrypointProbe extends AnyWordSpec with BeforeAndAfterAll with Matchers {

  "Something" must {
    "behave correctly" in {
      ActorSystem[Nothing](Behaviors.setup[Nothing] { context =>
        import akka.actor.typed.scaladsl.adapter._
        implicit val classicSystem: classic.ActorSystem = context.system.toClassic

        val cluster = Cluster(context.system)
        context.log.info("Started [" + context.system + "], cluster.selfAddress = " + cluster.selfMember.address + ")")

        val listener = context.spawn(Behaviors.receive[ClusterEvent.MemberEvent]((ctx, event) => {
          ctx.log.info("MemberEvent: {}", event)
          Behaviors.same
        }), "listener")

        Cluster(context.system).subscriptions ! Subscribe(listener, classOf[ClusterEvent.MemberEvent])

        val metricActorRef = context.spawn(MetricManager(), "metrics")
//        context.system.eventStream ! EventStream.Subscribe[LWWMap[Domain, StateProjection]](metricActorRef)

        val powerUnitActorRef: ActorRef[PowerUnit.Command] = context.spawn(
          PowerUnit(self => PowerUnitState(Domain(UUID.randomUUID.toString, Random.nextInt(5), self), 0, 200, LWWMap.empty[Domain, StateProjection])),
          "power-node")

        new NodeManagement(powerUnitActorRef)

        AkkaManagement.get(classicSystem).start()
        ClusterBootstrap.get(classicSystem).start()
        Behaviors.empty
      }, "appka")

    }
  }
}
