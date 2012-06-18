package org.w3.vs.actor.message

import org.w3.util.URL
import org.w3.vs.assertor._
import org.w3.vs.model._
import org.w3.vs.actor._
import play.api.libs.json._
import org.w3.vs.model._
import scalaz._
import org.joda.time._

/**
 * An update that happened on an Observation.
 * 
 * Listening to updates lets you know everything about an Observation.
 */
sealed trait RunUpdate 

case class UpdateData(data: JobData, activity: RunActivity) extends RunUpdate

/**
 * A new Response was received during the exploration
 */
case class NewResource(resource: ResourceResponse) extends RunUpdate

case class NewAssertorResult(result: AssertorResult) extends RunUpdate