package ua.net.ipk0.endpoint

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import spray.json._
import ua.net.ipk0.core.PowerUnit.{Command, ExternalActualPowerOrder}

import scala.collection.immutable.Range.{Exclusive, Inclusive}
import scala.concurrent.Future
import scala.concurrent.duration._

class EmbeddedClusterManagement(actors: Map[Int, ActorRef[Command]])(implicit system: ActorSystem) extends ManagementJsonSupport {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.dispatcher

  private[this] val route = path("management") {
    concat(patch {
      entity(as[ChangeActualPower]) { order: ChangeActualPower =>
        system.log.info(s"External actual power increase: $order")
        implicit val typedSystem = system.toTyped

        val eventualAck: Future[ChangeActualPowerAck] = actors.get(order.id).map(_.ask(ref => ExternalActualPowerOrder(order.actualPower, ref))
          .map(ack => ChangeActualPowerAck(ack.actualPower, ack.nominalPower, order.id.toString)))
          .getOrElse(Future.successful(ChangeActualPowerAck(-1, -1, order.id.toString)))
        complete(
          eventualAck
        )
      }
    },
      put {
        entity(as[ComposedPowerOrder]) { batch =>
          system.log.info(s"Get batch order : $batch")
          implicit val typedSystem = system.toTyped

          val powerUnitActorRef = actors(batch.one.id)
          val r = for {
            /*            _ <- powerUnitActorRef.ask(ref => ExternalActualPowerOrder(batch.one.actualPower, ref))
                        _ <- Future.successful(Thread.sleep(300))
                        _ <- powerUnitActorRef.ask(ref => ExternalActualPowerOrder(batch.two.actualPower, ref))
                        _ <- Future.successful(Thread.sleep(700))
                        _ <- powerUnitActorRef.ask(ref => ExternalActualPowerOrder(batch.three.actualPower, ref))
                        _ <- Future.successful(Thread.sleep(300))
                        _ <- powerUnitActorRef.ask(ref => ExternalActualPowerOrder(batch.four.actualPower, ref))
                        _ <- Future.successful(Thread.sleep(700))
            */
            res <- powerUnitActorRef.ask(ref => ExternalActualPowerOrder(batch.one.actualPower, ref))
          } yield res

          complete(r.map(ack => ChangeActualPowerAck(ack.actualPower, ack.nominalPower, powerUnitActorRef.path.name)))
        }
      }
    )
  }

  /*,
    path("batch"){
      entity(as[BatchChangeActualPower]) { batch: BatchChangeActualPower =>
      //  system.log.info(s"External actual power increase: $order")
        implicit val typedSystem = system.toTyped

        complete(
          Future.successful(ChangeActualPowerAck(-1, -1, ""))
        )
    }}
*/

  Http()(system).newServerAt("0.0.0.0", 8080).bind(route)
}

final case class ChangeActualPower(actualPower: Int, id: Int)
final case class BatchChangeActualPower(actualPower: Int, id: Exclusive)
final case class ComposedPowerOrder(one: ChangeActualPower, two: ChangeActualPower, three: ChangeActualPower, four: ChangeActualPower, fife: ChangeActualPower)

final case class ChangeActualPowerAck(actualPower: Int, nominalPower: Int, id: String)

trait ManagementJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val orderFormat: RootJsonFormat[ChangeActualPower] = jsonFormat2(ChangeActualPower.apply)
  implicit val composedOrderFormat: RootJsonFormat[ComposedPowerOrder] = jsonFormat5(ComposedPowerOrder.apply)
  implicit val orderAckFormat: RootJsonFormat[ChangeActualPowerAck] = jsonFormat3(ChangeActualPowerAck.apply)

/*
  implicit val batchOrderFormat: RootJsonFormat[BatchChangeActualPower] = jsonFormat2(BatchChangeActualPower.apply)
  implicit val rangeFormat: RootJsonFormat[Exclusive] = jsonFormat2(Range.apply(_: Int, _: Int))
*/

}
