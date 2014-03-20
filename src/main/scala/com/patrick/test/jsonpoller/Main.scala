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

import akka.actor.Kill

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
  implicit val timeout = Timeout(30 seconds)

  startServer(interface = "localhost", port = 8080) {
    path("take" / IntNumber) { take =>
      get {
        produce(instanceOf[List[ResultItem]]) { completeFunction =>
          requestContext =>

            // quick and dirty way to clean up
            //            val perRequestSystem = ActorSystem()
            // "http://peaceful-falls-6706.herokuapp.com/sample"
            val sampleActor = system.actorOf(Props(new SampleActor("http://localhost/test.json", take)))
            val f = (sampleActor ? RequestResults()).mapTo[List[ResultItem]]

            Await.ready(f, 30 seconds)

            val result = Await.result(f, 0 seconds)

            //            perRequestSystem.shutdown()

            completeFunction(result)
        }
      }
    }
  }

  sys.addShutdownHook(system.shutdown())
}

case class BadResponseException(url: String) extends Exception

class SampleActor(url: String, take: Int) extends Actor {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  var originalSender: ActorRef = null

  val buf = ListBuffer.empty[ResultItem]

  // create up to at most 10 worker actors to download the contents of each url
  val itemActors = context.actorOf(Props[DownloadItemActor]
    .withRouter(RoundRobinRouter(nrOfInstances = Math.min(10, take * 2)))
    .withDispatcher("download-item-dispatcher"))

  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 100,
    withinTimeRange = 30 seconds) {
      case _: BadResponseException => {
        println("restarting ")
        Restart
      }
      case _: IllegalArgumentException => Stop
      case _: Exception => Escalate
    }

  def receive = {
    case RequestResults => { println("work requested"); originalSender = sender; fetchSampleItems(url, take) }
    case GetItem(url) => itemActors ! GetItem(url)
    case ResultItemReady(item) => {
      println(s"result item ready: $item")
      buf += item
      if (buf.length >= take) {
        println(s"buffer satisfied: ${buf.length}")
        originalSender ! buf.toList
      }
    }
    case GetResults => originalSender ! buf.toList
  }

  private def fetchSampleItems(url: String, take: Int) = {
    println(s"fetchSample requested for $url with a take of $take")

    val sample = Await.result({
      val pipeline = sendReceive
      pipeline(Get(url))
    }, 30 seconds)

    val urls = sample.entity.asString.asJson.convertTo[UrlItemList].urls

    // fire off messages to the item actors to have them work on downloading the urls
    urls.foreach(u => itemActors ! GetItem(u.url))
  }

  override def preStart() = { println("Start SampleActor'"); super.preStart() }
  override def postStop() = { println("Stop SampleActor'"); super.postStop() }
}

class DownloadItemActor extends Actor {

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(30 seconds)

  def receive = {
    case GetItem(url) => sender ! ResultItemReady(fetchItem(url))
  }

  private def fetchItem(url: String): ResultItem = {
    val pipeline: SendReceive = sendReceive
    val f = pipeline(Get(url))

    f onSuccess {
      case r if r.status == 200 => r
      case r if r.status == 400 => throw new BadResponseException(url)
    }
    f onFailure { case r => println(s"$url failed with ${r.getMessage()}") }

    Await.result(f, 20 seconds).entity.asString.asJson.convertTo[ResultItem]
  }

  override def preStart() = { println("Start DownloadItemActor'"); super.preStart() }
  override def postStop() = { println("Stop DownloadItemActor'"); super.postStop() }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    println("Restart DownloadItemActor: ${reason.getClass}")
    super.preRestart(reason, message);
  }
}