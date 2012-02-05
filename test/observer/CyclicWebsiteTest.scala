package org.w3.vs.observer

import org.w3.util._
import org.w3.util.website._
import org.w3.vs.model._
import akka.util.Duration
import java.util.concurrent.TimeUnit._
import org.specs2.mutable.Specification
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Duration
import java.util.concurrent.TimeUnit.SECONDS

object CyclicWebsiteCrawlTest extends Specification {

  val delay = 0
  
  val strategy =
    EntryPointStrategy(
      uuid=java.util.UUID.randomUUID(), 
      name="localhost:8080",
      entrypoint=URL("http://localhost:8080/"),
      distance=11,
      linkCheck=true,
      filter=Filter(include=Everything, exclude=Nothing))
  
  val servers = Seq(unfiltered.jetty.Http(8080).filter(Website.cyclic(10).toPlanify))
  
  "test cyclic(10)" in new ObserverScope(servers)(new org.w3.vs.Production { }) {
    val am = http.authorityManagerFor(URL("http://localhost:8080/")).sleepTime = delay
    val observer = observerCreator.observerOf(ObserverId(), strategy)
    val urls = Await.result(observer.URLs(), Duration(1, SECONDS))
    urls must have size(11)
  }
  
//    val (links, timestamps) = responseDAO.getLinksAndTimestamps(actionId) .unzip
//    val a = Response.averageDelay(timestamps)
//    assert(a >= 200 && a < 250)
  
}
