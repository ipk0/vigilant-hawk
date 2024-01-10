package ua.net.ipk0

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.ClusterEvent
import akka.cluster.ddata.LWWMap
import akka.cluster.typed.{Cluster, Join, Subscribe}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.{actor => classic}
import ua.net.ipk0.core.PowerUnit
import ua.net.ipk0.core.PowerUnit.{Command, Domain, PowerUnitState, Start, StateProjection}
import ua.net.ipk0.endpoint.{EmbeddedClusterManagement, NodeManagement}
import ua.net.ipk0.metric.MetricManager

import java.util.UUID
import scala.collection.mutable
import scala.util.Random

object EmbeddedClusterEntrypoint extends App {

  ActorSystem[Nothing](Behaviors.setup[Nothing] { context =>
    import akka.actor.typed.scaladsl.adapter._
    implicit val classicSystem: classic.ActorSystem = context.system.toClassic

    val cluster = Cluster(context.system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))
    context.log.info("Started [" + context.system + "], cluster.selfAddress = " + cluster.selfMember.address + ")")

    val metricActorRef = context.spawn(MetricManager(), "metrics")
    context.system.eventStream ! EventStream.Subscribe[(Domain, LWWMap[Domain, StateProjection])](metricActorRef)

    val maps = mutable.HashMap.empty[Int, ActorRef[Command]]
    for (item <- 1 to 100) {//todo: 100
      val powerUnitActorRef: ActorRef[PowerUnit.Command] = context.spawn(
        PowerUnit(ref => PowerUnitState(Domain(s"power-unit-$item", item, ref), 100, 200, LWWMap.empty[Domain, StateProjection])),
        s"power-node-$item")
      powerUnitActorRef ! Start
      maps += (item -> powerUnitActorRef)
    }

    new EmbeddedClusterManagement(maps.toMap)

    Behaviors.empty
  }, "appka")
}
