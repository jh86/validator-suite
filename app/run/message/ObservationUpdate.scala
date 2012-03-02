package org.w3.vs.run.message

import org.w3.util.URL
import org.w3.vs.assertor.AssertorId
import org.w3.vs.model._
import org.w3.vs.run.RunState
import play.api.libs.json._
import org.w3.vs.model._
import play.api.libs.json.JsNumber
import scalaz._


/**
 * An update that happened on an Observation.
 * 
 * Listening to updates lets you know everything about an Observation.
 */
sealed trait ObservationUpdate {
  def toJS: JsValue
}

/**
 * The Observation has finished, there should be no more updates after that.
 * 
 * Note: this may go away in next versions.
 */
case object Done extends ObservationUpdate {
  def toJS: JsValue = JsArray(List(JsString("OBS_FINISHED")))
}

/**
 * The user has stopped the observation
 */
case object Stopped extends ObservationUpdate {
  def toJS: JsValue = JsArray(List(JsString("STOPPED")))
}

/**
 * A new Response was received during the exploration
 */
case class NewResourceInfo(resourceInfo: ResourceInfo) extends ObservationUpdate {
  def toJS: JsValue = {
    resourceInfo.result match {
      case ResourceInfoError(why) => 
        JsArray(List(
          JsString("ERR"),
          JsString(why),
          JsString(resourceInfo.url.toString)
        ))
      case Fetch(status, headers, extractedLinks) => resourceInfo.action match {
        case GET => 
          JsArray(List(
            JsString("GET"),
            JsNumber(status),
            JsString(resourceInfo.url.toString)
          ))
        case HEAD => 
          JsArray(List(
            JsString("HEAD"),
            JsNumber(status),
            JsString(resourceInfo.url.toString)
          ))
        case _ => sys.error("TODO you should change the type :-)")
      }
    }
  }
}

/**
 * A new Assertion was received
 */
case class NewAssertorResult(result: AssertorResult) extends ObservationUpdate {
  
  def toJS: JsValue = result match {
    case assertions: Assertions =>
      JsArray(List(
        JsString("OBS"),
        JsString(assertions.url.toString),
        JsString(assertions.assertorId.toString),
        JsNumber(assertions.numberOfErrors),
        JsNumber(assertions.numberOfWarnings)
      ))
    case fail: AssertorFail =>
      JsArray(List(
        JsString("OBS_ERR"),
        JsString(fail.url.toString)
      ))
  }
  
}

