package ua.net.ipk0.health

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import scala.annotation.unused
import scala.concurrent.Future

@unused
class HealthCheck(system: ActorSystem) extends (() => Future[Boolean]) {
  private val log = LoggerFactory.getLogger(getClass)

  override def apply(): Future[Boolean] = {
    log.info("ua.net.ipk0.health.HealthCheck called")
    Future.successful(true)
  }
}