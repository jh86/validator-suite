package org.w3.vs.model

import java.util.UUID
import org.joda.time.DateTime
import org.w3.vs.run._
import akka.dispatch.Await
import akka.pattern.AskTimeoutException
import akka.util.duration._

case class JobData(
    state: RunState,
    resources: Int,
    oks: Int,
    errors: Int,
    warnings: Int
)
