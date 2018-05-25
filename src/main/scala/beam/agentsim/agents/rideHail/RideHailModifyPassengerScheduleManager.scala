package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StopDriving
import beam.agentsim.agents.rideHail.RideHailingAgent.{Interrupt, InterruptedAt, ModifyPassengerSchedule, Resume}
import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingInquiry
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import beam.utils.DebugLib
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.ListBuffer
import scala.collection.{concurrent, mutable}

class RideHailModifyPassengerScheduleManager(val log: LoggingAdapter, val rideHailingManager: ActorRef) {

  val interruptIdToModifyPassengerScheduleStatus = mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  val vehicleIdToModifyPassengerScheduleStatus = mutable.Map[Id[Vehicle], mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]]()

  private def add(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    interruptIdToModifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.interruptId, rideHailModifyPassengerScheduleStatus)
    addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus)
  }

  private def addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    var listBuffer = getWithVehicleIds(rideHailModifyPassengerScheduleStatus.vehicleId)
    listBuffer += rideHailModifyPassengerScheduleStatus
  }

  private def getWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.get(interruptId)
  }

  private def getWithVehicleIds(vehicleId: Id[Vehicle]):  mutable.ListBuffer[RideHailModifyPassengerScheduleStatus] = {
    if (!vehicleIdToModifyPassengerScheduleStatus.contains(vehicleId)) {
      vehicleIdToModifyPassengerScheduleStatus.put(vehicleId, mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]())
    }

    vehicleIdToModifyPassengerScheduleStatus.get(vehicleId).get
  }

  private def removeWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    interruptIdToModifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set = vehicleIdToModifyPassengerScheduleStatus.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
        set -= rideHailModifyPassengerScheduleStatus
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }

  private def removeWithVehicleId(vehicleId:Id[Vehicle], time:Long) ={
    var rideHailModifyPassengerScheduleStatusSet=getWithVehicleIds(vehicleId)
    val deleteItems=mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]();
    log.debug("BEFORE checkin.removeWithVehicleId("+ rideHailModifyPassengerScheduleStatusSet.size  +"):" + rideHailModifyPassengerScheduleStatusSet)
    rideHailModifyPassengerScheduleStatusSet.foreach{
      rideHailModifyPassengerScheduleStatus =>

        if (rideHailModifyPassengerScheduleStatus.tick<time){
          if (rideHailModifyPassengerScheduleStatus.status==InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT){
            interruptIdToModifyPassengerScheduleStatus.remove(rideHailModifyPassengerScheduleStatus.interruptId)
            deleteItems+=rideHailModifyPassengerScheduleStatus
          }
        }

    }

    vehicleIdToModifyPassengerScheduleStatus.put(vehicleId,rideHailModifyPassengerScheduleStatusSet diff deleteItems)

    rideHailModifyPassengerScheduleStatusSet=getWithVehicleIds(vehicleId)

    if (!rideHailModifyPassengerScheduleStatusSet.isEmpty){
      sendInterruptMessage(rideHailModifyPassengerScheduleStatusSet.head)
    }

    log.debug("AFTER checkin.removeWithVehicleId("+ rideHailModifyPassengerScheduleStatusSet.size  +"):" + getWithVehicleIds(vehicleId))
  }

  private def sendInterruptMessage( passengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit ={
    passengerScheduleStatus.status=InterruptMessageStatus.INTERRUPT_SENT
    sendMessage(passengerScheduleStatus.rideHailAgent, Interrupt(passengerScheduleStatus.interruptId, passengerScheduleStatus.tick))
  }

  private def sendModifyPassengerScheduleMessage(selectedForModifyPassengerSchedule: Option[RideHailModifyPassengerScheduleStatus],  stopDriving:Boolean): Unit ={
    selectedForModifyPassengerSchedule.foreach{selected =>
      if (stopDriving){
        sendMessage(selected.rideHailAgent, StopDriving())
      }
      sendMessage(selected.rideHailAgent,selected.modifyPassengerSchedule)
      sendMessage(selected.rideHailAgent,Resume())
      selected.status=InterruptMessageStatus.MODIFY_PASSENGER_SCHEDULE_SENT
    }
  }

  private def sendMessage(rideHailingAgent:ActorRef, message: Any): Unit ={
      rideHailingAgent.tell(message,rideHailingManager)
      log.debug("sendMessages:" + message.toString)
  }




  def handleInterrupt(interruptType: Class[_], interruptId: Id[Interrupt], interruptedPassengerSchedule: Option[PassengerSchedule], vehicleId: Id[Vehicle], tick: Double): Unit = {
    log.debug("RideHailModifyPassengerScheduleManager.handleInterrupt: "  + interruptType.getSimpleName + " -> " + vehicleId)
    interruptIdToModifyPassengerScheduleStatus.get(interruptId) match {
      case Some(modifyPassengerScheduleStatus) =>
        assert(vehicleId==modifyPassengerScheduleStatus.vehicleId)
        assert(tick==modifyPassengerScheduleStatus.tick)

        log.debug("RideHailModifyPassengerScheduleManager.handleInterrupt: " + modifyPassengerScheduleStatus.toString)

        var reservationModifyPassengerScheduleStatus=mutable.ListBuffer[RideHailModifyPassengerScheduleStatus]()
        for (modifyPassengerScheduleStatus <-getWithVehicleIds(modifyPassengerScheduleStatus.vehicleId)){
          if (modifyPassengerScheduleStatus.interruptOrigin==InterruptOrigin.RESERVATION){
            reservationModifyPassengerScheduleStatus +=modifyPassengerScheduleStatus
          }
        }

        var selectedForModifyPassengerSchedule:Option[RideHailModifyPassengerScheduleStatus]=None
        var withVehicleIds=getWithVehicleIds(vehicleId)
        if (reservationModifyPassengerScheduleStatus.isEmpty){
          // find out which repositioning to process
          //log.debug("RideHailModifyPassengerScheduleManager - getWithVehicleIds.size: " + withVehicleIds.size + ",vehicleId(" + vehicleId + ")")
          selectedForModifyPassengerSchedule=Some(withVehicleIds.last)
          DebugLib.emptyFunctionForSettingBreakPoint()
          // TODO: allow soon most recent one
        } else if (reservationModifyPassengerScheduleStatus.size==1){
          val reservationStatus=reservationModifyPassengerScheduleStatus.head
          if (reservationStatus.status== InterruptMessageStatus.UNDEFINED ||  reservationStatus.status== InterruptMessageStatus.INTERRUPT_SENT) {
            // process reservation request
            selectedForModifyPassengerSchedule=Some(reservationStatus)

          } else (
            log.error("RideHailModifyPassengerScheduleManager - unexpected interrupt message")
          )
        } else {
          log.error("RideHailModifyPassengerScheduleManager - reservationModifyPassengerScheduleStatus contained more than one rideHail reservation request for same vehicle(" + vehicleId + ")")
          reservationModifyPassengerScheduleStatus.foreach(a => log.error("reservation requests:"+ a.toString))
        }



        sendModifyPassengerScheduleMessage(selectedForModifyPassengerSchedule,interruptedPassengerSchedule.isDefined)
      case None =>
        log.error("RideHailModifyPassengerScheduleManager- interruptId not found: interruptId(" + interruptId + "),interruptType(" + interruptType+  "),interruptedPassengerSchedule(" + interruptedPassengerSchedule+ "),vehicleId(" + vehicleId+ "),tick(" + tick+")")
    }

  }

  def repositionVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef):Unit={
    //log.debug("RideHailModifyPassengerScheduleManager- repositionVehicle request: " + vehicleId)
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule),tick,vehicleId,rideHailAgent,InterruptOrigin.REPOSITION)
  }

  def reserveVehicle(passengerSchedule:PassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef,inquiryId: Option[Id[RideHailingInquiry]]):Unit={
    //log.debug("RideHailModifyPassengerScheduleManager- reserveVehicle request: " + vehicleId)
    sendInterruptMessage(ModifyPassengerSchedule(passengerSchedule,inquiryId),tick,vehicleId,rideHailAgent,InterruptOrigin.RESERVATION)
  }

   private def sendInterruptMessage(modifyPassengerSchedule:ModifyPassengerSchedule,tick:Double,vehicleId:Id[Vehicle],rideHailAgent: ActorRef, interruptOrigin: InterruptOrigin.Value):Unit={
     val rideHailAgentInterruptId = RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId
     var interruptMessageStatus=InterruptMessageStatus.UNDEFINED

     val rideHailModifyPassengerScheduleStatus = new RideHailModifyPassengerScheduleStatus(rideHailAgentInterruptId, vehicleId, modifyPassengerSchedule, interruptOrigin, tick, rideHailAgent, interruptMessageStatus)

     val withVehicleIdStats=getWithVehicleIds(vehicleId)
     if (getWithVehicleIds(vehicleId).filter(_.interruptOrigin==InterruptOrigin.RESERVATION).isEmpty){
       //log.debug("RideHailModifyPassengerScheduleManager- sendInterruptMessage: " + rideHailModifyPassengerScheduleStatus)
       sendInterruptMessage(rideHailModifyPassengerScheduleStatus)
     } else {
       log.debug("RideHailModifyPassengerScheduleManager- messageBuffered: " + rideHailModifyPassengerScheduleStatus)
     }
     add(rideHailModifyPassengerScheduleStatus)
   }

  def checkInResource(vehicleId:Id[Vehicle], availableIn: Option[SpaceTime]): Unit ={
    removeWithVehicleId(vehicleId,availableIn.get.time)
  }

}

object InterruptMessageStatus extends Enumeration {
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT, EXECUTED = Value
}

object InterruptOrigin extends Enumeration {
  val RESERVATION, REPOSITION = Value
}

case class RideHailModifyPassengerScheduleStatus(val interruptId: Id[Interrupt], val vehicleId: Id[Vehicle], val modifyPassengerSchedule: ModifyPassengerSchedule, val interruptOrigin: InterruptOrigin.Value, val tick:Double, val rideHailAgent:ActorRef, var status: InterruptMessageStatus.Value = InterruptMessageStatus.UNDEFINED)


object RideHailModifyPassengerScheduleManager {
  def nextRideHailAgentInterruptId: Id[Interrupt] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[Interrupt])
  }
}
