package org.w3.vs.model

import org.w3.vs.actor._

object JobData {

  def apply(data: RunData): JobData = JobData(
    jobId = data.jobId,
    runId = data.runId,
    activity = data.activity,
    explorationMode = data.explorationMode,
    resources = data.numberOfKnownUrls,
    oks = data.oks,
    errors = data.errors,
    warnings = data.warnings)

}

case class JobData(
    jobId: JobId,
    runId: RunId,
    activity: RunActivity,
    explorationMode: ExplorationMode,
    resources: Int,
    oks: Int,
    errors: Int,
    warnings: Int
)
