package org.w3.vs.model

import org.w3.vs._
import org.w3.util._
import org.w3.vs.assertor._
import scalaz.Scalaz._
import scalaz._
import org.joda.time._
import org.w3.banana._
import org.w3.banana.util._
import org.w3.banana.LinkedDataStore._
import org.w3.vs.store.Binders._
import org.w3.vs.diesel._
import org.w3.vs.sparql._
import org.w3.banana.util._
import org.w3.vs.actor.AssertorCall

object Run {

  def bananaGet(orgId: OrganizationId, jobId: JobId, runId: RunId)(implicit conf: VSConfiguration): BananaFuture[Run] =
    bananaGet((orgId, jobId, runId).toUri)

  def get(orgId: OrganizationId, jobId: JobId, runId: RunId)(implicit conf: VSConfiguration): FutureVal[Exception, Run] =
    get((orgId, jobId, runId).toUri)

  def get(runUri: Rdf#URI)(implicit conf: VSConfiguration): FutureVal[Exception, Run] =
    bananaGet(runUri).toFutureVal

  def bananaGet(runUri: Rdf#URI)(implicit conf: VSConfiguration): BananaFuture[Run] = {
    import conf._
    store.get(runUri) flatMap { _.resource.as[Run] }
  }

  def getFor(orgId: OrganizationId, jobId: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Run]] =
    getFor((orgId, jobId).toUri)
    
  def getFor(jobUri: Rdf#URI)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Run]] = {
    import conf._
    val query = """
CONSTRUCT {
  ?job ont:run ?run .
  ?s1 ?p1 ?o1 .
} WHERE {
  BIND (iri(strbefore(str(?job), "#")) AS ?jobG) .
  graph ?jobG { ?job ont:run ?run } .
  BIND (iri(strbefore(str(?run), "#")) AS ?runG) .
  graph ?runG { ?s1 ?p1 ?o1 }
}
"""
    val construct = ConstructQuery(query, ont)
    val r = for {
      graph <- store.executeConstruct(construct, Map("job" -> jobUri))
      pointedJob = PointedGraph[Rdf](jobUri, graph)
      runs <- (pointedJob / ont.run).asSet[Run]
    } yield runs
    r.toFutureVal
  }

  def save(run: Run)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] = {
    import conf._
    val jobUri = (run.id._1, run.id._2).toUri
    val r = for {
      _ <- store.put(run.ldr)
      _ <- store.append(jobUri, jobUri -- ont.run ->- run.runUri)
    } yield ()
    r.toFutureVal
  }

  def delete(run: Run)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] =
    sys.error("")

  /* Assertion */

//  def addAssertion(orgId: OrganizationId, jobId: JobId, runId: RunId, assertion: Assertion)(implicit conf: VSConfiguration): BananaFuture[Unit] =
//    addAssertion((orgId, jobId, runId).toUri, assertion)
//
//  def addAssertion(runUri: Rdf#URI, assertion: Assertion)(implicit conf: VSConfiguration): BananaFuture[Unit] = {
//    import conf._
//    store.append(runUri, runUri -- ont.assertion ->- assertion.toPG)
//  }
//

  def apply(id: (OrganizationId, JobId, RunId), strategy: Strategy): Run =
    new Run(id, strategy)

  def apply(id: (OrganizationId, JobId, RunId), strategy: Strategy, createdAt: DateTime): Run =
    new Run(id, strategy, createdAt)

  def initialRun(id: (OrganizationId, JobId, RunId), strategy: Strategy, createdAt: DateTime): (Run, List[URL]) = {
    new Run(id = id, strategy = strategy, createdAt = createdAt)
      .withNewUrlsToBeExplored(List(strategy.entrypoint))
      .takeAtMost(Strategy.maxUrlsToFetch)
  }

  def freshRun(orgId: OrganizationId, jobId: JobId, strategy: Strategy): (Run, List[URL]) = {
    new Run(id = (orgId, jobId, RunId()), strategy = strategy)
      .withNewUrlsToBeExplored(List(strategy.entrypoint))
      .takeAtMost(Strategy.maxUrlsToFetch)
  }

  /* addResourceResponse */

  def saveEvent(runUri: Rdf#URI, event: RunEvent)(implicit conf: VSConfiguration): BananaFuture[Unit] = {
    import conf._
    store.append(runUri, runUri -- ont.event ->- event.toPG)
  }

  /* other events */

  def completedAt(runUri: Rdf#URI, at: DateTime)(implicit conf: VSConfiguration): BananaFuture[Unit] = {
    import conf._
    store.append(runUri, runUri -- ont.completedAt ->- at)
  }

}

/**
 * Run represents a coherent state of for an Run, modelized as an FSM
 * see http://akka.io/docs/akka/snapshot/scala/fsm.html
 */
case class Run private (
    id: (OrganizationId, JobId, RunId),
    strategy: Strategy,
    createdAt: DateTime = DateTime.now(DateTimeZone.UTC),
    // from completion event, None at creation
    completedAt: Option[DateTime] = None,
    // from user event, ProActive by default at creation
    explorationMode: ExplorationMode = ProActive,
    // based on scheduled fetches
    toBeExplored: List[URL] = List.empty,
    fetched: Set[URL] = Set.empty,
    pending: Set[URL] = Set.empty,
    // based on added resources
    knownUrls: Set[URL] = Set.empty,
    resources: Int = 0,
    // based on added assertions
    assertions: Set[Assertion] = Set.empty,
    errors: Int = 0,
    warnings: Int = 0,
    invalidated: Int = 0,
    // based on scheduled assertions
    pendingAssertions: Int = 0) {

  val logger = play.Logger.of(classOf[Run])

  val shortId: String = id._2.shortId + "/" + id._3.shortId

  val runUri = id.toUri

  def jobData: JobData = JobData(resources, errors, warnings, createdAt, completedAt)
  
  def health: Int = jobData.health

  /* combinators */

  def completedAt(at: DateTime): Run = copy(completedAt = Some(at))

  /* methods related to the data */
  
  def ldr: LinkedDataResource[Rdf] = LinkedDataResource(runUri, this.toPG)

  def numberOfKnownUrls: Int = knownUrls.count { _.authority === mainAuthority }

  /**
   * An exploration is over when there are no more urls to explore and no pending url
   */
  def noMoreUrlToExplore = pending.isEmpty && toBeExplored.isEmpty

  def isIdle = noMoreUrlToExplore && pendingAssertions == 0

  def isRunning = !isIdle

  def activity: RunActivity = if (isRunning) Running else Idle

  def state: (RunActivity, ExplorationMode) = (activity, explorationMode)

  private def shouldIgnore(url: URL): Boolean = {
    def notToBeFetched = IGNORE === strategy.getActionFor(url)
    def alreadyKnown = knownUrls contains url
    notToBeFetched || alreadyKnown
  }

  def numberOfRemainingAllowedFetches = strategy.maxResources - numberOfKnownUrls

  val mainAuthority: Authority = strategy.mainAuthority

  /**
   * A consolidated view of all the authorities for the pending urls
   */
  lazy val pendingAuthorities: Set[Authority] = pending map { _.authority }

  /**
   * Returns a couple Observation/Explore.
   *
   * The Explore  is the first one that could be fetched for the main authority.
   *
   * The returned Observation has set this Explore to be pending.
   */
  private def takeFromMainAuthority: Option[(Run, URL)] = {
    val optUrl = toBeExplored find { _.authority == mainAuthority }
    optUrl map { url =>
      (this.copy(
        pending = pending + url,
        toBeExplored = toBeExplored filterNot { _ == url }),
        url)
    }
  }

  /**
   * Returns (if possible, hence the Option) the first Explore that could
   * be fetched for any authority but the main one.
   * Also, this Explore must be the only one with this Authority.
   *
   * The returned Observation has set this Explore to be pending.
   */
  private def takeFromOtherAuthorities: Option[(Run, URL)] = {
    val pendingToConsiderer =
      toBeExplored.view filterNot { url => url.authority == mainAuthority || (pendingAuthorities contains url.authority) }
    pendingToConsiderer.headOption map { url =>
      (this.copy(
        pending = pending + url,
        toBeExplored = toBeExplored filterNot { _ == url }),
        url)
    }
  }

  lazy val mainAuthorityIsBeingFetched = pending exists { _.authority == mainAuthority }

  /**
   * Returns (if possible, hence the Option) the first Explore that could
   * be fetched, giving priority to the main authority.
   */
  def take: Option[(Run, URL)] = {
    val take = if (mainAuthorityIsBeingFetched) {
      takeFromOtherAuthorities
    } else {
      takeFromMainAuthority orElse takeFromOtherAuthorities
    }
    take
  }

  /**
   * Returns as many Explores as possible to be fetched.
   *
   * The returned Observation has all the Explores marked as being pending.
   */
  def takeAtMost(n: Int): (Run, List[URL]) = {
    var current: Run = this
    var urls: List[URL] = List.empty
    for {
      i <- 1 to (n - pending.size)
      (run, url) <- current.take
    } {
      current = run
      urls ::= url
    }
    (current, urls.reverse)
  }

  /**
   * Returns an Observation with the new urls to be explored
   */
  private def withNewUrlsToBeExplored(urls: List[URL]): Run = {
    val filteredUrls = urls.filterNot{ url => shouldIgnore(url) }.distinct.take(numberOfRemainingAllowedFetches)
    if (! filteredUrls.isEmpty)
      logger.debug("%s: Found %d new urls to explore. Total: %d" format (shortId, filteredUrls.size, this.numberOfKnownUrls))
    val run = this.copy(
      toBeExplored = toBeExplored ++ filteredUrls,
      knownUrls = knownUrls ++ filteredUrls
    )
    run
  }

  private def runWithResponse(response: ResourceResponse): Run = {
    this.copy(
      pending = pending - response.url,
      fetched = fetched + response.url,
      resources = resources + 1
    )      
  }

  def withHttpResponse(httpResponse: HttpResponse): (Run, List[URL], List[AssertorCall]) = {
    // add the new response
    val runWithResponse = this.runWithResponse(httpResponse)
    // extract the urls to be explored
    val (runWithPendingFetches, urlsToFetch) =
      if (explorationMode === ProActive)
        runWithResponse.withNewUrlsToBeExplored(httpResponse.extractedURLs).takeAtMost(Strategy.maxUrlsToFetch)
      else
        (runWithResponse, List.empty)
    // extract the calls to the assertor to be made
    val assertorCalls =
      if (explorationMode === ProActive && httpResponse.action === GET) {
        val assertors = strategy.getAssertors(httpResponse)
        assertors map { assertor => AssertorCall(this.id, assertor, httpResponse) }
      } else {
        List.empty
      }
    val runWithPendingAssertorCalls =
      runWithPendingFetches.copy(pendingAssertions = runWithPendingFetches.pendingAssertions + assertorCalls.size)
    (runWithPendingAssertorCalls, urlsToFetch, assertorCalls)
  }

  def withErrorResponse(errorResponse: ErrorResponse): Run = {
    this.copy(
      pending = pending - errorResponse.url,
      fetched = fetched + errorResponse.url
    )
  }

  def withAssertorResult(result: AssertorResult): Run = {
    val (nbErrors, nbWarnings) = Assertion.countErrorsAndWarnings(result.assertions)
    this.copy(
      assertions = this.assertions ++ result.assertions,
      errors = this.errors + nbErrors,
      warnings = this.warnings + nbWarnings,
      pendingAssertions = pendingAssertions - 1) // lower bound is 0
  }

  def withAssertorFailure(fail: AssertorFailure): Run = {
    // TODO? should do something about that
    this.copy(pendingAssertions = pendingAssertions - 1)
  }

  def stopMe(): Run =
    this.copy(explorationMode = Lazy, toBeExplored = List.empty)

  def withMode(mode: ExplorationMode) = this.copy(explorationMode = mode)
    
}

