package ua.net.ipk0.core

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.ddata.typed.scaladsl.{DistributedData, ReplicatorMessageAdapter}
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.{LWWMap, LWWMapKey, ReplicatedData, SelfUniqueAddress}
import akka.util.Timeout
import ua.net.ipk0.core.PowerUnit.Projection.{Green, Projection, Red, Yellow}
import mouse.all._
import cats._
import cats.data._
import cats.syntax.all._
import ua.net.ipk0.core.PowerUnit.{Domain, PowerUnitState, StateProjection, regularBehaviour, reloadClosestUnits}
import ua.net.ipk0.util.Utilities

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.immutable.Map
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

object PowerUnit {

  sealed trait Command
  case class ExternalActualPowerOrder(actualPower: Int, replyTo: ActorRef[ExternalActualPowerOrderAck]) extends Command
  case class ExternalActualPowerOrderAck(actualPower: Int, nominalPower: Int) extends Command
  case class UnitStateReq(replyTo: ActorRef[UnitStateAck]) extends Command
  case class UnitStateAck(unitState: PowerUnitState) extends Command
  case object Start extends Command
  case object Stop extends Command
  private sealed trait InternalCommand extends Command
  private case class InternalSubscribeResponse(rsp: SubscribeResponse[LWWMap[Domain, StateProjection]]) extends InternalCommand
  private case class InternalUpdateResponse[A <: ReplicatedData](rsp: UpdateResponse[A]) extends InternalCommand
  private case object MonitorState extends InternalCommand
  private case class EmergencyMonitorState(reloadUnitSet: Boolean = false) extends InternalCommand
  private case class StateChanged(newState: Projection) extends InternalCommand
  private case class PullRefund(requesterId: Domain, power: Int) extends InternalCommand
  private case class PushRefund(requesterId: Domain, power: Int) extends InternalCommand
  private case class EmergencyAsk(requesterId: Domain, amount: Int, replyTo: ActorRef[Command]) extends InternalCommand
  private case class EmergencyAck(lenderId: Domain, amount: Int) extends InternalCommand
  private case class DebtorTerminated(domain: Domain) extends InternalCommand
  private case class LeanderTerminated(domain: Domain) extends InternalCommand
  private case object EmptyEmergencyList extends InternalCommand

  private def determineShardState(currentPowerValue: Int, nominalPower: Int)(implicit context: ActorContext[Command]): Projection = {
    val shapeState = if (currentPowerValue < nominalPower) {
      Projection.Green
    } else if (currentPowerValue == nominalPower) {
      Projection.Yellow
    } else {
      Projection.Red
    }

    context.log.info(s"Current shape state: $shapeState")
    shapeState
  }

  def apply(state: ActorRef[Command] => PowerUnitState): Behavior[Command] = Behaviors.setup { implicit context =>
    DistributedData.withReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]] { implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]] =>
      implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress

      implicit val entirePowerGridKey: LWWMapKey[Domain, StateProjection] = LWWMapKey[Domain, StateProjection]("entire-power-grid")

      replicator.subscribe(entirePowerGridKey, InternalSubscribeResponse.apply)

      regularBehaviour(state(context.self))
    }
  }

  private def commonBehaviour(unitState: PowerUnitState)(implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]],
    entirePowerGridKey: LWWMapKey[Domain, StateProjection], context: ActorContext[Command]): PartialFunction[Command, Behavior[Command]] = {
    case UnitStateReq(replyTo) =>
      replyTo ! UnitStateAck(unitState)
      Behaviors.same
    case Start =>
      context.self ! MonitorState
      Behaviors.same
    case Stop =>
      context.system.terminate()
      Behaviors.stopped
  }
  private def regularBehaviour(unitState: PowerUnitState)(implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]],
    entirePowerGridKey: LWWMapKey[Domain, StateProjection], context: ActorContext[Command], node: SelfUniqueAddress): Behavior[Command] = {
    Behaviors.receiveMessagePartial[Command](commonBehaviour(unitState).orElse {
      case currentState@StateChanged(projection) => projection match {
        case Red =>
          findEndmostPoint(unitState.assets.debtors, unitState.id.regionId).cata({ endmostDebtor =>
            endmostDebtor._1.actorRef ! PullRefund(unitState.id, endmostDebtor._2)
            context.self ! MonitorState
            regularBehaviour(unitState = unitState.copy(nominalPower = unitState.nominalPower + endmostDebtor._2, assets = unitState.assets.dropDebtor(endmostDebtor._1)))
          }, {
            context.self ! currentState
            emergencyStateBehaviour(unitState, reloadClosestUnits(unitState))
          })
        case Green =>
          val list = refundCandidates(listEndmostPoint(unitState.assets.lenders, unitState.id.regionId), unitState.extraPower)

          list.foreach(pair => pair._1.actorRef ! PushRefund(unitState.id, pair._2))

          val domainList = list.map(_._1)
          val totalPushedPower = list.map(_._2).sum
          regularBehaviour(unitState.copy(nominalPower = unitState.nominalPower - totalPushedPower, assets = unitState.assets.dropLenders(domainList)))
        case _ => Behaviors.same
      }
      case EmergencyAsk(requesterId, amount, replyTo) =>
        unitState.getExtraPower(amount).fold({
          replyTo ! EmergencyAck(unitState.id, 0)
          Behaviors.same[Command]
        })(extra => {
          context.watchWith(requesterId.actorRef, DebtorTerminated(requesterId))
          replyTo ! EmergencyAck(unitState.id, extra)
          context.self ! MonitorState
          regularBehaviour(unitState = unitState.copy(nominalPower = unitState.nominalPower - extra, assets = unitState.assets.addDebtor(requesterId, extra)))
        })
      case PullRefund(requesterId, amount) =>
        context.self ! MonitorState
        regularBehaviour(unitState = unitState.decreaseNominalPower(amount).copy(assets = unitState.assets.dropLender(requesterId)))
      case PushRefund(requesterId, amount) =>
        context.self ! MonitorState
        regularBehaviour(unitState = unitState.increaseNominalPower(amount).copy(assets = unitState.assets.dropDebtor(requesterId)))
      case ExternalActualPowerOrder(actualPowerReq, replyTo) =>
        val modifiedState = unitState.copy(actualPower = actualPowerReq)
        replyTo ! ExternalActualPowerOrderAck(modifiedState.actualPower, modifiedState.nominalPower)
        context.self ! MonitorState
        regularBehaviour(unitState = modifiedState)
      case MonitorState =>
        val currentShardState = determineShardState(unitState.actualPower, unitState.nominalPower)
        isStateChanged(unitState, currentShardState) ?? (notifyReplicator(unitState, currentShardState) |+| /*context.system.eventStream ! EventStream.Publish(unitState.id -> updatedGlobalState) |+|*/ context.self ! StateChanged(currentShardState))
        regularBehaviour(unitState = unitState.copy(globalState = unitState.globalState.:+(unitState.id -> StateProjection(currentShardState))))
      case InternalSubscribeResponse(entirePowerGrid@Changed(key)) =>
        context.log.info(s"[id][EntirePowerGrid State Changed] : ${entirePowerGrid.get(key)}")
        val globalState = entirePowerGrid.get(key)
        Thread.sleep(Math.abs(unitState.id.regionId + 10) * 2) //todo: to slow
        context.system.eventStream ! EventStream.Publish(unitState.id -> globalState)
        regularBehaviour(unitState = unitState.copy(globalState = globalState))
      case LeanderTerminated(domain) =>
        context.self ! MonitorState
        notifyDeletionAction(unitState, domain)
        regularBehaviour(unitState.decreaseNominalPower(unitState.assets.lenders.get(domain).orEmpty).copy(assets = unitState.assets.dropLender(domain)))
      case DebtorTerminated(domain) =>
        context.self ! MonitorState
        notifyDeletionAction(unitState, domain)
        regularBehaviour(unitState.increaseNominalPower(unitState.assets.debtors.get(domain).orEmpty).copy(assets = unitState.assets.dropDebtor(domain)))
      case InternalUpdateResponse(data) => //todo
        context.log.error("InternalUpdateResponse {}" ,data)
        context.system.eventStream ! EventStream.Publish(unitState.id -> unitState.globalState)
        Behaviors.same
      case InternalSubscribeResponse(_) | InternalUpdateResponse(_) | EmergencyMonitorState(_) => Behaviors.same // ok
    })
  }

  private def findEndmostPoint(data: Map[Domain, Int], currentRegionId: Int) =
    data.toList.sortWith(sortByEndmostRegion(currentRegionId)).headOption

  private def listEndmostPoint(data: Map[Domain, Int], currentRegionId: Int) =
    data.toList.sortWith(sortByEndmostRegion(currentRegionId))

  private def reloadClosestUnits(unitState: PowerUnitState) =
    unitState.globalState.entries.filter(_._2.state == Green).keys.filterNot(_.id == unitState.id.id).toList.sortWith(sortByClosestRegion(unitState.id.regionId))

  private val sortByEndmostRegion: Int => ((Domain, Int), (Domain, Int)) => Boolean = regionId => (t1, t2) =>
    Utilities.compareByRankEndmost(regionId)(t1._1.regionId, t2._1.regionId)

  private val sortByClosestRegion: Int => (Domain, Domain) => Boolean = regionId => (t1, t2) =>
    Utilities.compareByRankClosest(regionId)(t1.regionId, t2.regionId)


  private def refundCandidates(list: List[(Domain, Int)], extraPower: Int): List[(Domain, Int)] = {
    def filterDomain(list: List[(Domain, Int)], extraPower: Int)(acc: List[(Domain, Int)]): List[(Domain, Int)] = {
        list match {
          case Nil => acc
          case (domain, borrowedPower) :: tail =>
            Option.when(extraPower >= borrowedPower)(filterDomain(tail, extraPower - borrowedPower)(acc :+ (domain, borrowedPower)))
              .getOrElse(filterDomain(tail, extraPower)(acc))
        }
    }
    filterDomain(list, extraPower)(Nil)
  }

  private def emergencyStateBehaviour(unitState: PowerUnitState, closestUnits: List[Domain])(implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]],
    entirePowerGridKey: LWWMapKey[Domain, StateProjection], context: ActorContext[Command], node: SelfUniqueAddress): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receiveMessagePartial(commonBehaviour(unitState).orElse {
        case StateChanged(projection) => projection match {
          case Green =>
            val list = refundCandidates(listEndmostPoint(unitState.assets.lenders, unitState.id.regionId), unitState.extraPower)

            context.log.error(s"Candidates to refund: $list")

            list.foreach(pair => pair._1.actorRef ! PushRefund(unitState.id, pair._2))

            val domainList = list.map(_._1)
            val totalPushedPower = list.map(_._2).sum

            regularBehaviour(unitState.copy(nominalPower = unitState.nominalPower - totalPushedPower, assets = unitState.assets.dropLenders(domainList)))
          case Yellow => regularBehaviour(unitState)
          case _ =>
            context.log.info(s"It's about to change status to RED. State: $unitState")
            implicit val timeout = Timeout(10.seconds)

            import scala.util.Success
            import scala.util.Failure

            closestUnits.headOption.cata({ closestUnit: Domain =>
              context.ask(closestUnit.actorRef, createRequest(unitState)) {
                case Success(res: EmergencyAck) => res
                case Failure(ex) =>
                  context.log.error(s"Request failed. Try to get next unit. Details: $ex")
                  EmergencyAck(closestUnit, 0)
              }
            }, {
              context.self ! EmptyEmergencyList
              emergencyStateBehaviour(unitState, Nil)
            })
            emergencyStateBehaviour(unitState, closestUnits.nonEmpty ?? closestUnits.tail)
        }
        case EmergencyAck(lenderId, amount) =>
          context.self ! EmergencyMonitorState()
          Option.when(amount > 0) {
            context.watchWith(lenderId.actorRef, LeanderTerminated(lenderId))
            emergencyStateBehaviour(unitState.increaseNominalPower(amount).copy(assets = unitState.assets.addLender(lenderId, amount)), closestUnits)
          } getOrElse {
            emergencyStateBehaviour(unitState, closestUnits)
          }
        case EmptyEmergencyList =>
          timers.startSingleTimer(EmergencyMonitorState(true), FiniteDuration(1, TimeUnit.MINUTES))
          Behaviors.same
        case EmergencyMonitorState(reloadUnitSet) =>
          val newStateProjection = determineShardState(unitState.actualPower, unitState.nominalPower)
          isStateChanged(unitState, newStateProjection) ?? notifyReplicator(unitState, newStateProjection)
          context.self ! StateChanged(newStateProjection)
          emergencyStateBehaviour(unitState = unitState.copy(globalState = unitState.globalState.:+(unitState.id -> StateProjection(newStateProjection))), Option.when(reloadUnitSet)(reloadClosestUnits(unitState)).getOrElse(closestUnits))
        case EmergencyAsk(_, _, replyTo) =>
          replyTo ! EmergencyAck(unitState.id, 0)
          Behaviors.same
        case PullRefund(requesterId, amount) =>
          context.self ! EmergencyMonitorState()
          emergencyStateBehaviour(unitState = unitState.decreaseNominalPower(amount).copy(assets = unitState.assets.dropLender(requesterId)), closestUnits)
        case PushRefund(requesterId, amount) =>
          context.self ! MonitorState
          emergencyStateBehaviour(unitState = unitState.increaseNominalPower(amount).copy(assets = unitState.assets.dropDebtor(requesterId)), closestUnits)
        case ExternalActualPowerOrder(actualPowerReq, replyTo) =>
          val modifiedState = unitState.copy(actualPower = actualPowerReq)
          replyTo ! ExternalActualPowerOrderAck(modifiedState.actualPower, modifiedState.nominalPower)
          context.self ! EmergencyMonitorState()
          emergencyStateBehaviour(unitState = modifiedState, closestUnits)
        case InternalSubscribeResponse(entirePowerGrid@Changed(key)) =>
          Thread.sleep(Math.abs(unitState.id.regionId + 10) * 2) //todo: to slow
          val globalState = entirePowerGrid.get(key)
          context.system.eventStream ! EventStream.Publish(globalState)
          emergencyStateBehaviour(unitState = unitState.copy(globalState = globalState), closestUnits)
        case LeanderTerminated(domain) =>
          context.self ! EmergencyMonitorState()
          notifyDeletionAction(unitState, domain)
          emergencyStateBehaviour(unitState.decreaseNominalPower(unitState.assets.lenders.get(domain).orEmpty).copy(assets = unitState.assets.dropLender(domain)), closestUnits)
        case DebtorTerminated(domain) =>
          context.self ! EmergencyMonitorState()
          notifyDeletionAction(unitState, domain)
          emergencyStateBehaviour(unitState.increaseNominalPower(unitState.assets.debtors.get(domain).orEmpty).copy(assets = unitState.assets.dropDebtor(domain)), closestUnits)
        case InternalUpdateResponse(data) => //todo
          context.log.error("InternalUpdateResponse2 {}", data)
          context.system.eventStream ! EventStream.Publish(unitState.id -> unitState.globalState)
          Behaviors.same
      })
    }
  }

  private def createRequest(unitState: PowerUnitState): ActorRef[Command] => Command = replyTo => EmergencyAsk(unitState.id, unitState.actualPower - unitState.nominalPower, replyTo)

  private def notifyReplicator(state: PowerUnitState, currentShapeState: Projection
    )(implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]], entirePowerGridKey: LWWMapKey[Domain, StateProjection],
      node: SelfUniqueAddress): Unit = {
    replicator.askUpdate(askReplyTo => {
      Update(entirePowerGridKey, state.globalState, WriteLocal, askReplyTo
      )(_ :+ (state.id -> StateProjection(currentShapeState)))
    }, InternalUpdateResponse.apply)
  }

  private def notifyDeletionAction(state: PowerUnitState, domain: Domain
    )(implicit replicator: ReplicatorMessageAdapter[Command, LWWMap[Domain, StateProjection]], entirePowerGridKey: LWWMapKey[Domain, StateProjection],
      node: SelfUniqueAddress): Unit = {
    replicator.askUpdate(askReplyTo => {
      Update(entirePowerGridKey, state.globalState, WriteLocal, askReplyTo
      )(_.remove(node, domain))
    }, InternalUpdateResponse.apply)
  }

  private def isStateChanged(state: PowerUnitState, shapeState: Projection)(implicit context: ActorContext[Command]) = {
    val isNodeStateChanged = !state.globalState.get(state.id).map(_.state).contains(shapeState)

    context.log.info(s"Node state changes: $isNodeStateChanged. Current state: $shapeState")

    isNodeStateChanged
  }

  type Debtors = Map[Domain, Int]
  type Lenders = Map[Domain, Int]

  case class Assets(debtors: Debtors, lenders: Lenders){

    def dropDebtor(domain: Domain) = this.copy(debtors = debtors - domain)
    def dropLender(domain: Domain) = this.copy(lenders = lenders - domain)
    def dropLenders(domains: List[Domain]) = this.copy(lenders = lenders -- domains)

    def addLender(domain: Domain, amount: Int) = this.copy(lenders = lenders.updatedWith(domain)(_.map(_ + amount).orElse(amount.some)))
    def addDebtor(domain: Domain, amount: Int) = this.copy(debtors = debtors.updatedWith(domain)(_.map(_ + amount).orElse(amount.some)))
  }

  object Projection extends Enumeration {
    type Projection = Value
    val Green, Yellow, Red = Value
  }
  case class StateProjection(state: Projection)
  case class Domain(id: String, regionId: Int, actorRef: ActorRef[Command])
  case class PowerUnitState(id: Domain, actualPower: Int, nominalPower: Int, globalState: LWWMap[Domain, StateProjection], assets: Assets = Assets(Map.empty, Map.empty))

  implicit class PowerUnitStateOps(state: PowerUnitState) {

    def increaseNominalPower(amount: Int): PowerUnitState = state.copy(nominalPower = state.nominalPower + amount)
    def decreaseNominalPower(amount: Int): PowerUnitState = state.copy(nominalPower = state.nominalPower - amount)
    def increaseActualPower(amount: Int): PowerUnitState = state.copy(actualPower = state.actualPower + amount)
    def hasExtraPower(amount: Int): Boolean = state.nominalPower - state.actualPower > amount

    def extraPower: Int = state.nominalPower - state.actualPower

    def getExtraPower(amount: Int): Option[Int] = Option.when(extraPower >= 0)(Math.min(extraPower, amount))
  }
}
