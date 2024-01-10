package ua.net.ipk0.metric

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ddata.LWWMap
import com.github.tototoshi.csv.CSVWriter
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.HTTPServer
import ua.net.ipk0.core.PowerUnit.Projection.{Green, Projection}
import ua.net.ipk0.core.PowerUnit.{Domain, StateProjection}

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

object MetricManager {
  private val dateFormat = DateTimeFormatter.ofPattern("mm:ss.SSS").withZone(ZoneId.systemDefault)

  def apply(): Behavior[(Domain, LWWMap[Domain, StateProjection])] = Behaviors.setup { ctx =>
    new HTTPServer.Builder()
      .withPort(8081)
      .withDaemonThreads(true)
      .build();

    val metricMap = new mutable.HashMap[Domain, Gauge]()

    import java.util.concurrent.ConcurrentHashMap
    import scala.collection.concurrent
    import scala.jdk.CollectionConverters._
    val map: concurrent.Map[String, Int] = new ConcurrentHashMap[String, Int]().asScala

    val metrics = new mutable.HashMap[String, String]()


    // id ~> linkedList

    val prepared = new ConcurrentHashMap[String, ListBuffer[Int]]()
    val rate = 20
    ctx.system.scheduler.scheduleAtFixedRate(1.seconds, rate.milliseconds)(() => {
      val nodeCount = 100 //todo: node count
      for (item <- 1 to nodeCount) {
        val domainKey = s"power-unit-$item"
        val list = prepared.computeIfAbsent(domainKey, _ => ListBuffer.empty[Int])
        val reflection = map.get(domainKey).orElse(list.lastOption).getOrElse(Green.id)
        list.addOne(reflection)
      }
    })

    ctx.system.scheduler.scheduleOnce(10.seconds, () => {
      val writer = CSVWriter.open("node-state.csv", append = false)
      val snapshot = prepared.asScala
      val columnCount = snapshot.head._2.size
      val range = Range(0, columnCount * rate, rate)
      writer.writeRow(List("") ++ range.toList)

      for ((key, value) <- snapshot.map(pair => (pair._1.replace("power-unit-", "").toInt -> pair._2)).toList.sortWith((first, second) => first._1 < second._1)) {
            writer.writeRow(List(key) ++ value.toList.take(range.toList.size))
      }
      writer.close()
      println("Done!!!!")
    })

      //    Map  ~> Id, Red|Green|Yellow

    Behaviors.receive[(Domain, LWWMap[Domain, StateProjection])]((_, event) => {
      val pointOfView = event._1.id
      val projection = event._2.entries.find(item => item._1.id == "power-unit-1") //todo: node id 1

      projection.foreach(item => map.put(pointOfView, item._2.state.id))

      Behaviors.same
    })



/*
    Behaviors.receive[(Domain, LWWMap[Domain, StateProjection])]((_, event) => {
      ctx.log.info(s"Point of view ${event._1.id} regionId: ${event._1.regionId}. New state: ###########################")
      event._2.entries.foreach {
        case (shapeId, shape) =>
          val nodeGauge = metricMap.getOrElseUpdate(shapeId,
            Gauge.build().name(s"shape_${shapeId.id.replace("-", "_")}").help(s"Node ${shapeId.id} state").register())

          nodeGauge.set(shape.state.id)
          ctx.log.error(s"[${shapeId.id}]=${shape.state} Metric for ${shapeId.id}=${shape.state} has been sent")

          if (shapeId.id == "power-unit-1"){
              writeAudit(event._1.id,s"node-data", shape.state, System.currentTimeMillis())
          }
      }
      Behaviors.same[(Domain, LWWMap[Domain, StateProjection])]
    })
*/
  }

/*
  private def write(current: concurrent.Map[String, Int], observerCount: Int): Unit = {
    val writer = CSVWriter.open("node_data.csv", append = true)
    for (1 to observerCount){

    }
  }
*/

  private def writeAudit(nodeId: String, fileName: String, targetUnitState: Projection, time: Long): Unit = {
    val writer = CSVWriter.open(s"$fileName.csv", append = true)
//    writer.writeRow(List("Time", "Target Unit State")
    writer.writeRow(List(nodeId, dateFormat.format(Instant.ofEpochMilli(time)), targetUnitState.id))
    writer.close()
  }

}
