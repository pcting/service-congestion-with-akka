package com.patrick.test.jsonpoller

import spray.httpx.SprayJsonSupport
import scala.concurrent.Await
import spray.json.DefaultJsonProtocol
import spray.routing.SimpleRoutingApp
import akka.actor.ActorSystem
import akka.actor.Actor
import scala.concurrent.Promise
import akka.actor.Props
import akka.routing.RoundRobinRouter
import akka.pattern.ask
import akka.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.collection.mutable.ListBuffer
import akka.actor.ActorRef
import SampleActorProtocol._
import DownloadItemAccumulatorActorProtocol._
import DownloadItemActorProtocol._
import spray.client.pipelining._
import scala.concurrent.duration._
import DownloadItemAccumulatorActorProtocol._
import DownloadItemActorProtocol._
import spray.client.pipelining._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

import akka.event.Logging
import akka.actor.ActorLogging

// Transfer Objects
case class UrlItemList(urls: List[UrlItem])
case class UrlItem(url: String)
case class ResultItem(message: String, delay: Int, path: String)

object MyJsonSupport extends DefaultJsonProtocol {
  implicit val urlItemFormat = jsonFormat1(UrlItem)
  implicit val urlItemListFormat = jsonFormat1(UrlItemList)
  implicit val resultItemFormat = jsonFormat3(ResultItem)
}

object SampleActorProtocol {
  case class RequestResults()
  case class ResultsReady(results: List[ResultItem])
  case class GetResults()
  case class Halt()
}

object DownloadItemAccumulatorActorProtocol {
  case class ResultItemReady(item: ResultItem)
  case class SetTake(take: Int)
  case class Cancel()
}

object DownloadItemActorProtocol {
  case class GetItem(url: String)
  case class Cancel()
}

import MyJsonSupport._

object Main extends App with SimpleRoutingApp with SprayJsonSupport {
  import MyJsonSupport._
  import SampleActorProtocol._
  import scala.concurrent.duration._

  implicit val system = ActorSystem("main")
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(60 seconds)

  val log = Logging(system, this.getClass)

  startServer(interface = "localhost", port = 8080) {
    path("take" / IntNumber) { take =>
      get {
        produce(instanceOf[List[ResultItem]]) { completeFunction =>
          requestContext =>

            // i couldn't find a quick solution to stop all child actors from continuing to work, so i opt'd towards the easy
            // route of a quick and dirty way to clean up... shutdown the entire system
            val perRequestSystem = ActorSystem()

            try {

              val sampleActor = perRequestSystem.actorOf(Props(new SampleActor("http://peaceful-falls-6706.herokuapp.com/sample", take)))
              val f = (sampleActor ? RequestResults).mapTo[List[ResultItem]]

              // wait for only 30 seconds to complete
              Await.ready(f, 50 seconds)

              // grab what results are available
              val f2 = (sampleActor ? GetResults).mapTo[List[ResultItem]]
              val result = Await.result(f2, 5 seconds)
              completeFunction(result)

            } catch {
              case e: Throwable => {
                log.error(s"Error encountered: $e")
                completeFunction(List[ResultItem]())
              }
            } finally {
              // shut down all workers
              perRequestSystem.shutdown()
            }
        }
      }
    }
  }

  sys.addShutdownHook(system.shutdown())
}

case class BadResponseException(val statusCode: Int, val url: String) extends Exception

class SampleActor(url: String, take: Int) extends Actor with ActorLogging {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  var originalSender: ActorRef = null

  var done = false

  var retryCounts = 0
  var errorCounts = 0

  val buf = ListBuffer.empty[ResultItem]

  // create up to at most 10 worker actors to download the contents of each url
  val itemActors = context.actorOf(Props[DownloadItemActor]
    .withRouter(RoundRobinRouter(nrOfInstances = Math.min(10, take * 2)))
    .withDispatcher("download-item-dispatcher"))

  // restart the worker for a max of 5 retries within a time span of 60 seconds
  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 5,
    withinTimeRange = 60 seconds) {
      case BadResponseException(400, url) => {
        log.warning(s"$url returned status code 400, restarting actor")
        Restart
      }
      case BadResponseException(500, url) => {
        log.warning(s"$url returned status code 500, ignoring url")
        Resume
      }
      case _: Exception => Escalate
    }

  def receive = {
    case RequestResults => { originalSender = sender; fetchSampleItems(url, take) }
    case GetItem(url) => itemActors ! GetItem(url)
    case ResultItemReady(item) => {
      log.debug(s"result item ready: $item")
      buf += item
      if (!done && buf.length >= take) {
        done = true
        log.info(s"take count satisfied: ${buf.length}")
        originalSender ! buf.toList
      }
    }
    case GetResults => {
      log.debug(s"sending results: ${buf.toList}")
      sender ! buf.toList
    }
  }

  private def fetchSampleItems(url: String, take: Int) = {
    log.debug(s"fetchSample requested for $url 1a take of $take")

    val sample = Await.result({
      val pipeline = sendReceive
      pipeline(Get(url))
    }, 30 seconds)

    val urls = sample.entity.asString.asJson.convertTo[UrlItemList].urls

    log.info(s"Found ${urls.length} urls")

    // fire off messages to the item actors to have them work on downloading the urls
    urls.foreach(u => itemActors ! GetItem(u.url))

    log.debug("done queuing download tasks")
  }
}

class DownloadItemActor extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  def receive = {
    case GetItem(url) => sender ! ResultItemReady(fetchItem(url))
  }

  private def fetchItem(url: String): ResultItem = {
    val pipeline: SendReceive = sendReceive
    val f = pipeline(Get(url))
    log.debug(s"waiting for $url response")
    val response = Await.result(f, 20 seconds)
    response.status.intValue match {
      case 200 => response.entity.asString.asJson.convertTo[ResultItem]
      case code => throw new BadResponseException(code, url)
    }
  }
}