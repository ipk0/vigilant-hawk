package ua.net.ipk0.endpoint

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.{as, complete, entity, patch, path}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.ddata.LWWMap
import cats._
import cats.data._
import cats.syntax.all._

import scala.concurrent.duration._
import akka.util.Timeout
import spray.json._
import ua.net.ipk0.core.PowerUnit.{Assets, Domain, ExternalActualPowerOrder, StateProjection, UnitStateAck, UnitStateReq}
import ua.net.ipk0.core.PowerUnit

import scala.concurrent.Future

class NodeManagement(powerUnitActorRef: ActorRef[PowerUnit.Command])(implicit system: ActorSystem) extends JsonSupport {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.dispatcher

  private[this] val route = pathPrefix("management") {
    concat(
      patch {
        entity(as[PowerOrder]) { order: PowerOrder =>
          system.log.info(s"External actual power increase: $order")
          implicit val typedSystem = system.toTyped

          complete(
            powerUnitActorRef.ask(ref => ExternalActualPowerOrder(order.actualPower, ref))
              .map(ack => IncreasePowerOrderAck(ack.actualPower, ack.nominalPower, powerUnitActorRef.path.name)))
        }
      })}

    /*, path("state"){
      entity(as[GetNodeState]){ stateReq =>
        system.log.info(s"External get state: $stateReq")
        implicit val typedSystem = system.toTyped

        complete(
          powerUnitActorRef.ask(ref => UnitStateReq(ref))
          .map(state => GetNodeStateAck(state.unitState.id, state.unitState.actualPower, state.unitState.nominalPower, state.unitState.globalState.entries.toMap, state.unitState.assets))
        )
      }
    }*/

  Http()(system).newServerAt("0.0.0.0", 8080).bind(route)
}

final case class PowerOrder(actualPower: Int, id: String)



final case class IncreasePowerOrderAck(actualPower: Int, nominalPower: Int, id: String)

final case class GetNodeState(id: String)

final case class GetNodeStateAck(id: Domain, actualPower: Int, nominalPower: Int, globalState: Map[Domain, StateProjection], assets: Assets = Assets(Map.empty, Map.empty))

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val orderFormat: RootJsonFormat[PowerOrder] = jsonFormat2(PowerOrder.apply)
  implicit val orderAckFormat: RootJsonFormat[IncreasePowerOrderAck] = jsonFormat3(IncreasePowerOrderAck.apply)

  /*
    implicit val stateFormat: RootJsonFormat[GetNodeState] = jsonFormat1(GetNodeState.apply)
    implicit val stateAckFormat: RootJsonFormat[GetNodeStateAck] = jsonFormat5(GetNodeStateAck.apply)
  */
}
