package com.patrick.test.jsonpoller

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import MyJsonSupport.listFormat
import SampleActorProtocol.GetResults
import SampleActorProtocol.RequestResults
import akka.actor.ActorSystem
import akka.actor.Props
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import spray.httpx.SprayJsonSupport
import spray.routing.Directive.pimpApply
import spray.routing.SimpleRoutingApp
import java.util.concurrent.TimeoutException

object Main extends App with SimpleRoutingApp with SprayJsonSupport {
  import MyJsonSupport._
  import SampleActorProtocol._
  import scala.concurrent.duration._

  implicit val system = ActorSystem("main")
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(120 seconds)

  val log = Logging(system, this.getClass)

  startServer(interface = "localhost", port = 8080) {
    path("take" / IntNumber) { take =>
      get {
        produce(instanceOf[List[ResultItem]]) { completeFunction =>
          requestContext =>

            completeFunction {
              // i couldn't find a quick solution to stop all child actors from continuing to work, so i opt'd towards the easy
              // route of a quick and dirty way to clean up... shutdown the entire system
              // have to spend more time looking at actor supervision of akka
              implicit val perRequestSystem = ActorSystem()
              implicit val ec = perRequestSystem.dispatcher

              try {

                val sampleActor = perRequestSystem.actorOf(Props(new SampleActor("http://peaceful-falls-6706.herokuapp.com/sample", take)))
                val f = (sampleActor ? RequestResults).mapTo[List[ResultItem]]

                try {
                  // wait for at most 50 seconds to complete
                  Await.ready(f, 50 seconds)
                } catch {
                  case toe: TimeoutException => log.warning("Continuing past timeout", toe)
                }

                log.warning("TIME EXPIRED, GRABBIG PARTIAL RESULTS")

                // time has expired, we'll grab what results are available and return it to the user
                val f2 = (sampleActor ? GetResults).mapTo[List[ResultItem]]
                val result = Await.result(f2, 5 seconds)
                result
              } catch {
                case t: Throwable =>
                  log.error(s"Error encountered", t)
                  List[ResultItem]()
              } finally {
                // shut down all workers
                perRequestSystem.shutdown()
              }
            }
        }
      }
    }
  }

  sys.addShutdownHook(system.shutdown())
}
