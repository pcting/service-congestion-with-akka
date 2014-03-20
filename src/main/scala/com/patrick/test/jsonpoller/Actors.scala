package com.patrick.test.jsonpoller

import java.util.concurrent.TimeoutException

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import MyJsonSupport.resultItemFormat
import MyJsonSupport.urlItemListFormat
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy.Escalate
import akka.actor.SupervisorStrategy.Restart
import akka.actor.SupervisorStrategy.Resume
import akka.actor.actorRef2Scala
import akka.routing.RoundRobinRouter
import akka.util.Timeout
import spray.client.pipelining.Get
import spray.client.pipelining.SendReceive
import spray.client.pipelining.sendReceive
import spray.json.pimpString

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

case class BadResponseException(statusCode: Int, url: String, inner: Option[Exception] = None) extends Exception(s"Status Code: $statusCode, Url: $url", inner.getOrElse(null))

class SampleActor(url: String, take: Int) extends Actor with ActorLogging {

  import SampleActorProtocol._
  import DownloadItemAccumulatorActorProtocol._
  import DownloadItemActorProtocol._
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  var originalSender: ActorRef = null

  var done = false

  var retryCounts = 0
  var statusCode500Count = 0
  var miscErrorCount = 0

  val buf = ListBuffer.empty[ResultItem]

  // create up to at most 10 worker actors to download the contents of each url
  val itemActors = context.actorOf(Props[DownloadItemActor]
    .withRouter(RoundRobinRouter(nrOfInstances = Math.min(3, take * 2)))
    .withDispatcher("download-item-dispatcher"))

  // restart the worker for a max of 5 retries within a time span of 60 seconds
  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 5,
    withinTimeRange = 60 seconds) {
      case BadResponseException(400, url, None) =>
        log.warning(s"400 STATUS CODE FOUND, RESTARTING ACTOR, $url")
        retryCounts += 1
        Restart

      case BadResponseException(500, url, None) =>
        log.warning(s"500 STATUS CODE FOUND, NOOP")
        statusCode500Count += 1
        Resume

      case BadResponseException(code, url, None) =>
        log.warning(s"$code STATUS CODE FOUND, NOOP")
        miscErrorCount += 1
        Resume

      case _: TimeoutException =>
        log.warning(s"400 STATUS CODE FOUND, RESTARTING ACTOR, $url")
        retryCounts += 1
        Restart

      case _: Exception =>
        miscErrorCount += 1
        Escalate
    }

  def receive = {
    case RequestResults =>
      originalSender = sender
      fetchSampleItems(url, take)
    case GetItem(url) => itemActors ! GetItem(url)
    case ResultItemReady(item) =>
      log.debug(s"result item ready: $item")
      buf += item
      if (!done && buf.length >= take) {
        done = true
        log.info(s"take count satisfied: ${buf.length}")
        originalSender ! buf.toList
      }
    case GetResults =>
      log.debug(s"sending results: ${buf.toList}")
      sender ! buf.toList

  }

  private def fetchSampleItems(url: String, take: Int) = {
    log.debug(s"fetchSample requested for $url 1a take of $take")

    val sample = Await.result({
      val pipeline = sendReceive
      pipeline(Get(url))
    }, 30 seconds)

    val urls = sample.entity.asString.asJson.convertTo[UrlItemList].urls

    log.info(s"Found ${urls.length} urls")

    // queue up all 100 urls into the mailboxes of DownloadItemActor to be worked on
    urls.foreach(u => itemActors ! GetItem(u.url))

    log.debug("done queuing download tasks")
  }
}

class DownloadItemActor extends Actor with ActorLogging {

  import DownloadItemAccumulatorActorProtocol._
  import DownloadItemActorProtocol._
  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  def receive = {
    case GetItem(url) => sender ! ResultItemReady(fetchItem(url))
  }

  private def fetchItem(url: String): ResultItem = {
    val pipeline: SendReceive = sendReceive
    val f = pipeline(Get(url))
    log.debug(s"waiting for $url response")
    try {
      val response = Await.result(f, 20 seconds)
      response.status.intValue match {
        case 200 => response.entity.asString.asJson.convertTo[ResultItem]
        case code => throw new BadResponseException(code, url)
      }
    } catch {
      case toe: TimeoutException => throw toe
      case t: Throwable => throw t
    }
  }
}