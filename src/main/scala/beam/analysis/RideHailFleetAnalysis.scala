package beam.analysis

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ParkEvent, PathTraversalEvent, RefuelSessionEvent}
import beam.analysis.plots.GraphAnalysis
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.metrics.SimulationMetricCollector
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.vehicles.VehicleType

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class EventStatus(start: Double, end: Double, eventType: String, nextType: Option[String] = None)

class RideHailFleetAnalysis(beamServices: BeamServices) extends GraphAnalysis {

  val resolutionInSeconds = 60
  val lastHour = 24
  val timeBins = 0 until lastHour * 3600 by resolutionInSeconds
  var processedHour = 0

  val keys = Map(
    "driving-full"       -> 0,
    "driving-reposition" -> 1,
    "driving-topickup"   -> 2,
    "driving-tocharger"  -> 3,
    "queuing"            -> 4,
    "charging"           -> 5,
    "idle"               -> 6,
    "offline"            -> 7,
    "parked"             -> 8
  )

  import RefuelSessionEvent._

  val rideHailEvCav = mutable.Map[String, ArrayBuffer[Event]]()
  val ridehailEVNonCav = mutable.Map[String, ArrayBuffer[Event]]()
  val rideHailNonEvCav = mutable.Map[String, ArrayBuffer[Event]]()
  val rideHailNonEvNonCav = mutable.Map[String, ArrayBuffer[Event]]()

  val cavSet = mutable.Set[String]()
  val ridehailVehicleSet = mutable.Set[String]()

  override def processStats(event: Event): Unit = {
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        if (refuelSessionEvent.getAttributes.get(ATTRIBUTE_ENERGY_DELIVERED).toDouble > 0.0) {
          val vehicle = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
          if (vehicle.contains("rideHail")) {

            if (rideHailEvCav.contains(vehicle)) {
              collectEvent(
                rideHailEvCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (ridehailEVNonCav.contains(vehicle)) {
              collectEvent(
                ridehailEVNonCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (rideHailNonEvCav.contains(vehicle)) {
              collectEvent(
                rideHailNonEvCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (rideHailNonEvNonCav.contains(vehicle)) {
              collectEvent(
                rideHailNonEvNonCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            }
          }
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if (pathTraversalEvent.mode == BeamMode.CAR) {
          val vehicleTypeId = Id.create(pathTraversalEvent.vehicleType, classOf[BeamVehicleType])
          beamServices.beamScenario.vehicleTypes.get(vehicleTypeId).foreach { vehicleType =>
            val vehicle = pathTraversalEvent.vehicleId.toString
            val rideHail = vehicle.contains("rideHail")
            val ev = pathTraversalEvent.primaryFuelType == "Electricity"
            val cav = vehicleType.automationLevel > 3
            if (rideHail) {
              if (ev && cav) {
                collectEvent(
                  rideHailEvCav,
                  pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                  vehicle,
                  pathTraversalEvent.time
                )
              } else if (ev && !cav) {
                collectEvent(
                  ridehailEVNonCav,
                  pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                  vehicle,
                  pathTraversalEvent.time
                )
              } else if (!ev && cav) {
                collectEvent(
                  rideHailNonEvCav,
                  pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                  vehicle,
                  pathTraversalEvent.time
                )
              } else if (!ev && !cav) {
                collectEvent(
                  rideHailNonEvNonCav,
                  pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                  vehicle,
                  pathTraversalEvent.time
                )
              }
            }
          }
        }

      case parkEvent: ParkEvent =>
        val vehicle = parkEvent.vehicleId.toString
        if (rideHailEvCav.contains(vehicle)) {
          collectEvent(rideHailEvCav, parkEvent, vehicle, parkEvent.time)
        } else if (ridehailEVNonCav.contains(vehicle)) {
          collectEvent(ridehailEVNonCav, parkEvent, vehicle, parkEvent.time)
        } else if (rideHailNonEvCav.contains(vehicle)) {
          collectEvent(rideHailNonEvCav, parkEvent, vehicle, parkEvent.time)
        } else if (rideHailNonEvNonCav.contains(vehicle)) {
          collectEvent(rideHailNonEvNonCav, parkEvent, vehicle, parkEvent.time)
        }
      case _ =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    processVehicleStates()
  }

  def collectEvent(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    event: Event,
    vehicle: String,
    eventHour: Double
  ): Unit = {
    val events = vehicleEventTypeMap.getOrElse(vehicle, new ArrayBuffer[Event]())
    events += event
    vehicleEventTypeMap(vehicle) = events.sortBy(_.getTime)
    val hour = (eventHour / 3600).toInt
    if (hour > processedHour) {
      processVehicleStates()
      processedHour = hour
    }
  }

  def processVehicleStates() {
    processEvents(rideHailEvCav, true, true, "rh-ev-cav")
    processEvents(ridehailEVNonCav, true, false, "rh-ev-nocav")
    processEvents(rideHailNonEvCav, true, true, "rh-noev-cav")
    processEvents(rideHailNonEvNonCav, true, false, "rh-noev-nocav")
  }

  def processEvents(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    isRH: Boolean,
    isCAV: Boolean,
    graphName: String
  ) {
    var timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    var distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    vehicleEventTypeMap.values.foreach(now => {
      val timesDistances = assignVehicleDayToLocationMatrix(now, isRH, isCAV)
      timeUtilization = timesDistances._1.zip(timeUtilization).map(time => (time._1, time._2).zipped.map(_ + _))
      distanceUtilization = timesDistances._2
        .zip(distanceUtilization)
        .map(distance => (distance._1, distance._2).zipped.map(_ + _))
    })

    timeUtilization.transpose.zipWithIndex.foreach {
      case (row, index) =>
        val key = keys.keys.toList(index)
        row.grouped(60).zipWithIndex.foreach {
          case (result, hour) =>
            write(s"$graphName-count", result.sum, hour, key)
          case _ =>
        }
    }
    distanceUtilization.transpose.zipWithIndex.foreach {
      case (row, index) =>
        val key = keys.keys.toList(index)
        row.grouped(60).zipWithIndex.foreach {
          case (result, hour) =>
            write(s"$graphName-distance", result.sum, hour, key)
          case _ =>
        }
    }
  }

  def write(metric: String, value: Double, time: Int, key: String): Unit = {
    val tags = Map("vehicle-state" -> key)
    beamServices.simMetricCollector.write(
      metric,
      SimulationTime(time * 60 * 60),
      Map(SimulationMetricCollector.defaultMetricValueName -> value),
      tags,
      overwriteIfExist = true
    )
  }

  override def resetStats(): Unit = {
    rideHailEvCav.clear()
    ridehailEVNonCav.clear()
    rideHailNonEvCav.clear()
    rideHailNonEvNonCav.clear()
    processedHour = 0
  }

  def assignVehicleDayToLocationMatrix(
    days: ArrayBuffer[Event],
    isRH: Boolean,
    isCAV: Boolean
  ): (Array[Array[Double]], Array[Array[Double]]) = {
    val timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    val distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    if (isRH) {
      if (isCAV)
        timeBins.indices.foreach(timeUtilization(_)(keys("idle")) += 1)
      else
        timeBins.indices.foreach(timeUtilization(_)(keys("offline")) += 1)
    } else {
      timeBins.indices.foreach(timeUtilization(_)(keys("parked")) += 1)
    }

    days.zipWithIndex.foreach(eventIndex => {
      val event = eventIndex._1
      val idx = eventIndex._2
      val lastEvent = idx == days.size - 1
      var chargingNext = false
      var pickupNext = false

      if (!lastEvent) {
        val chargingDirectlyNext = days(idx + 1).getEventType == "RefuelSessionEvent"

        val chargingOneAfter =
          if (idx == days.size - 2)
            false
          else
            days(idx + 1).getEventType == "ParkEvent" && days(idx + 2).getEventType == "RefuelSessionEvent"
        chargingNext = chargingDirectlyNext || chargingOneAfter
        pickupNext = days(idx + 1).getEventType == "PathTraversal" && days(idx + 1).getAttributes
          .get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)
          .toInt >= 1
      }

      val eventCharacteristics = classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH, isCAV)
      val eventIdx = keys(eventCharacteristics.eventType)

      val afterDurationEventStart = timeBins
        .map(timeBin => {
          val eventStart = timeBin >= eventCharacteristics.start
          val duringEvent = eventStart && timeBin < eventCharacteristics.end
          (eventStart, duringEvent)
        })
        .unzip

      val afterEventStart = afterDurationEventStart._1
      val duringEvent = afterDurationEventStart._2

      afterEventStart.zipWithIndex.foreach(indexValue => {
        if (indexValue._1)
          timeUtilization(indexValue._2).indices.foreach(timeUtilization(indexValue._2)(_) = 0.0)
      })

      duringEvent.zipWithIndex.foreach(indexValue => {
        if (indexValue._1) {
          timeUtilization(indexValue._2)(eventIdx) += 1.0
        }
      })

      if (event.getEventType == "PathTraversal") {
        val sum = duringEvent.count(during => during)
        val legLength = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH).toDouble
        if (sum > 0) {
          val meanDistancePerTime = legLength / sum
          duringEvent.zipWithIndex.foreach(indexValue => {
            if (indexValue._1) {
              distanceUtilization(indexValue._2)(eventIdx) += meanDistancePerTime / 1609.34
            }
          })
        } else {
          val firstIndex = afterEventStart.indexOf(true)
          if (firstIndex > 0)
            distanceUtilization(firstIndex)(eventIdx) += legLength / 1609.34
        }
      }

      eventCharacteristics.nextType.foreach(nextType => {
        val afterEventEnd = timeBins.map(_ >= eventCharacteristics.end)
        afterEventEnd.zipWithIndex.foreach(indexValue => {
          if (indexValue._1) {
            timeUtilization(indexValue._2)(keys(nextType)) += 1.0
          }
        })
      })
    })

    (timeUtilization, distanceUtilization)
  }

  def classifyEventLocation(
    event: Event,
    lastEvent: Boolean,
    chargingNext: Boolean,
    pickupNext: Boolean,
    isRH: Boolean,
    isCAV: Boolean
  ): EventStatus = {
    event match {
      case event: PathTraversalEvent =>
        if (isRH) {
          if (event.numberOfPassengers >= 1) {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
            }
          } else {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
              else if (pickupNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
            }
          }
        } else {
          if (chargingNext)
            EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
          else {
            if (event.numberOfPassengers >= 1)
              EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
            else
              EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
          }
        }
      case event: RefuelSessionEvent =>
        val duration = event.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_SESSION_DURATION).toDouble
        if (isRH) {
          if (lastEvent) {
            if (isCAV)
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
            else
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("offline"))
          } else
            EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
        } else {
          EventStatus(event.getTime, event.getTime + duration, "charging", Some("parked"))
        }
      case event: ParkEvent =>
        if (isRH)
          EventStatus(event.getTime, 30 * 3600, "idle")
        else
          EventStatus(event.getTime, 30 * 3600, "parked")
      case _ =>
        EventStatus(0.0, 0.0, "Unknown")
    }
  }
}
