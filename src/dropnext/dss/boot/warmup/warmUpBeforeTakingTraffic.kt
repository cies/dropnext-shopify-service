package dropnext.dss.boot.warmup

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.errorLabel
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How long the warm-up may keep the readiness gate closed. Twenty seconds: a monolith that is down costs the first
 * step at most ~10.5 s (two attempts of five seconds and the backoff between them), which leaves the requests to
 * ourselves their turn; and with the load balancer's 30 s grace period and three failed checks 30 s apart before ECS
 * calls a task unhealthy, twenty seconds cannot get a task killed.
 */
val WARM_UP_BUDGET: Duration = 20.seconds

/**
 * The shop every step names: a domain that parses, so the check runs the token lookup for real, and that the monolith
 * answers `404` for, so nothing is found and nothing is cached.
 */
val WARM_UP_PLACEHOLDER_SHOP: ShopDomain = ShopDomain.parse("dss-warm-up.myshopify.com")!!

/**
 * Pays the JVM's one-time costs before the task takes traffic, so the first real webhook is answered like the
 * hundredth, and checks on the way that the monolith accepts our key. At a quarter vCPU the first outbound HTTPS call
 * alone costs seconds (client setup, the TLS provider, the trust store, the handshake), which is more than a webhook's
 * whole budget; a rolling deploy gives the new task the time to pay it while the old one still serves, provided
 * `/health` says it is not ready yet.
 *
 * Two parts, in order, each ending in one log line. The **outbound** part needs nothing but the dependency graph: one
 * store lookup on the monolith, which pays the big cost for every later HTTPS call and is the check that the monolith
 * accepts our key. The **inbound** part waits for [serverBound] and then sends the service two requests over loopback:
 * the check that our bearer provider is wired to the key it was configured with, and a delivery that runs the webhook
 * pipeline once. The contract drift check is not part of it: it decides nothing, so it runs after the gate has opened
 * ([reportContractDrift]).
 *
 * Changes nothing: no token is remembered or forgotten by naming the placeholder shop, the scan behind
 * the check is read-only, and the delivery is acknowledged without work. Never throws for an upstream failure; each
 * step reports instead, and a step [budget] never reaches is skipped for that reason.
 */
suspend fun warmUpBeforeTakingTraffic(
  monolith: MonolithService,
  loopback: WarmUpLoopbackService,
  serverBound: Deferred<Unit>,
  traceId: String,
  budget: Duration = WARM_UP_BUDGET,
): WarmUpReport =
  // The warm-up runs outside any request, so nothing has put a trace id in the MDC for it. Put there this way, it survives
  // every suspension, so the clients' failure and retry lines carry the id too, and the lines of a start (the two
  // below, the delivery's own `Webhook done`, and the contract check's after readiness) can be found together.
  withMdcEntries(TRACE_ID_MDC_KEY to traceId) {
    warmUp(monolith, loopback, serverBound, traceId, budget)
  }

private suspend fun warmUp(
  monolith: MonolithService,
  loopback: WarmUpLoopbackService,
  serverBound: Deferred<Unit>,
  traceId: String,
  budget: Duration,
): WarmUpReport {
  val deadline = TimeSource.Monotonic.markNow() + budget

  val outboundStarted = TimeSource.Monotonic.markNow()
  val keyAccepted = deadline.withinBudget { checkMonolithAcceptsOurKey(monolith, WARM_UP_PLACEHOLDER_SHOP) }
  val outbound = WarmUpOutboundReport(keyAccepted, outboundStarted.elapsedNow().inWholeMilliseconds)
  logAtLevelOf(listOf(keyAccepted), outbound.logLine())

  val inboundStarted = TimeSource.Monotonic.markNow()
  val bound = serverBound.isCompleted || deadline.withinBudget { serverBound.await(); WarmUpStepOutcome.Ok() } is WarmUpStepOutcome.Ok
  val bearerWiring =
    if (!bound) WarmUpStepOutcome.Skipped("server_not_bound")
    else deadline.withinBudget { checkBearerProviderWiring(loopback, WARM_UP_PLACEHOLDER_SHOP, traceId) }
  val webhook =
    if (!bound) WarmUpStepOutcome.Skipped("server_not_bound")
    else deadline.withinBudget { warmInboundPipeline(loopback, WARM_UP_PLACEHOLDER_SHOP, traceId) }
  val inbound = WarmUpInboundReport(bearerWiring, webhook, inboundStarted.elapsedNow().inWholeMilliseconds)
  logAtLevelOf(listOf(bearerWiring, webhook), inbound.logLine())
  return WarmUpReport(outbound, inbound)
}

/**
 * One store lookup. Any answer at all, the `404` for the placeholder shop included, proves the monolith accepts our
 * key. Only a `401` proves it does not: a `403` may come from something in front of the monolith, and a monolith that
 * does not answer says nothing about the key either way.
 */
private suspend fun checkMonolithAcceptsOurKey(monolith: MonolithService, shop: ShopDomain): WarmUpStepOutcome =
  when (val result = monolith.getStore(shop.subdomainOnly)) {
    is Success -> WarmUpStepOutcome.Ok()
    is Failure -> {
      val error = result.reason
      logMonolithFailure("getStore", error, "warm_up=true subdomain=${shop.subdomainOnly}")
      if (error is MonolithError.Rejected && error.status == 401) WarmUpStepOutcome.Rejected(error.errorLabel)
      else WarmUpStepOutcome.Failed(error.errorLabel)
    }
  }

/**
 * A request through our own bearer provider with the key it was configured with, which is all the DSS holds. So it
 * proves the wiring between the two (the loopback client and the provider read the same setting) and nothing about
 * the value the monolith holds: a monolith with another key is not noticed here. The provider's challenge is the one
 * answer that proves the wiring broken; the `401` for a shop without an Admin token, the production case for the
 * placeholder shop, means the bearer passed.
 */
private suspend fun checkBearerProviderWiring(
  loopback: WarmUpLoopbackService,
  shop: ShopDomain,
  traceId: String,
): WarmUpStepOutcome {
  val answer = loopback.shopifyStoreStatus(shop, traceId) ?: return WarmUpStepOutcome.Failed("no_answer")
  if (answer.bearerRejected) return WarmUpStepOutcome.Rejected("bearer_rejected")
  return answer.status.toPipelineOutcome()
}

private suspend fun warmInboundPipeline(loopback: WarmUpLoopbackService, shop: ShopDomain, traceId: String): WarmUpStepOutcome =
  loopback.webhookDelivery(shop, traceId)?.toPipelineOutcome() ?: WarmUpStepOutcome.Failed("no_answer")

/** A `200`, a `401` and a `502` all mean the pipeline ran end to end; only a `5xx` from our own `StatusPages` says it did not. */
private fun Int.toPipelineOutcome(): WarmUpStepOutcome =
  if (this >= 500 && this != 502) WarmUpStepOutcome.Failed("http_$this") else WarmUpStepOutcome.Ok(toString())

/**
 * Runs [step] inside what is left before this deadline: not at all once it has passed, and cut short (and reported
 * so) when it passes mid-way. A per-step bound rather than one around the whole warm-up, so the report and its log
 * lines are complete whatever the budget did.
 */
private suspend fun TimeMark.withinBudget(step: suspend () -> WarmUpStepOutcome): WarmUpStepOutcome {
  if (hasPassedNow()) return WarmUpStepOutcome.Skipped("budget")
  return withTimeoutOrNull(-elapsedNow()) { step() } ?: WarmUpStepOutcome.Failed("budget")
}

/** A rejected secret needs a human now; a failed step asks for a look; anything else is the normal shape of a start. */
private fun logAtLevelOf(outcomes: List<WarmUpStepOutcome>, line: String) = when {
  outcomes.any { it is WarmUpStepOutcome.Rejected } -> log.error { line }
  outcomes.any { it is WarmUpStepOutcome.Failed } -> log.warn { line }
  else -> log.info { line }
}

internal fun WarmUpOutboundReport.logLine(): String =
  "Warm-up outbound done took_ms=$tookMillis check_monolith_accepts_our_key=${monolithAcceptsOurKey.logFragment()}"

internal fun WarmUpInboundReport.logLine(): String =
  "Warm-up inbound done took_ms=$tookMillis " +
    "check_bearer_provider_wiring=${bearerProviderWiring.logFragment()} webhook=${webhook.logFragment()}"

private fun WarmUpStepOutcome.logFragment(): String = when (this) {
  is WarmUpStepOutcome.Ok -> detail
  is WarmUpStepOutcome.Failed -> "failed error=$label"
  is WarmUpStepOutcome.Rejected -> "rejected error=$label"
  is WarmUpStepOutcome.Skipped -> "skipped reason=$reason"
}
