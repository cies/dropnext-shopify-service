package dropnext.dss.boot.warmup

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.errorLabel
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.graphql.errorLabel
import dropnext.dss.lib.shopify.token.ShopLookup
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.random.Random
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
 * The shop both parts name when nothing is seeded (production): a domain that parses, so the check runs the token
 * lookup for real, and that the monolith answers `404` for, so nothing is found and nothing is cached.
 */
val WARM_UP_PLACEHOLDER_SHOP: ShopDomain = ShopDomain.parse("dss-warm-up.myshopify.com")!!

/**
 * Pays the JVM's one-time costs before the task takes traffic, so the first real webhook is answered like the
 * hundredth. At a quarter vCPU the first outbound HTTPS call alone costs seconds (client setup, the TLS provider, the
 * trust store, the handshake), which is more than a webhook's whole budget; a rolling deploy gives the new task the
 * time to pay it while the old one still serves, provided `/health` says it is not ready yet.
 *
 * Two parts, in order, each ending in one log line. The **outbound** part needs nothing but the dependency graph: one
 * store lookup on the monolith, which is the step that pays the big cost for every later HTTPS call, and one identity
 * query to Shopify for a seeded shop, when there is one. The **inbound** part waits for [serverBound] and then sends
 * the service two requests over loopback, so the pipeline every request runs has run once end to end.
 *
 * Changes nothing: no token is remembered or forgotten by naming a seeded shop or the placeholder, the scan behind
 * the check is read-only, and the delivery is acknowledged without work. Never throws for an upstream failure; each
 * step reports instead, and a step [budget] never reaches is skipped for that reason.
 */
suspend fun warmUpBeforeTakingTraffic(
  monolith: MonolithService,
  shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  loopback: WarmUpLoopbackService,
  seededShop: ShopDomain?,
  serverBound: Deferred<Unit>,
  budget: Duration = WARM_UP_BUDGET,
): WarmUpReport {
  val traceId = newWarmUpTraceId()
  // The warm-up runs outside any request, so nothing has put a trace id in the MDC for it. Put there this way, it survives
  // every suspension, so the clients' failure and retry lines carry the id too, and the three lines of a start (the two
  // below and the delivery's own `Webhook done`) can be found together.
  return withMdcEntries(TRACE_ID_MDC_KEY to traceId) {
    warmUp(monolith, shopifyGraphqlServiceFactory, loopback, seededShop, serverBound, traceId, budget)
  }
}

private suspend fun warmUp(
  monolith: MonolithService,
  shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  loopback: WarmUpLoopbackService,
  seededShop: ShopDomain?,
  serverBound: Deferred<Unit>,
  traceId: String,
  budget: Duration,
): WarmUpReport {
  val deadline = TimeSource.Monotonic.markNow() + budget
  val shop = seededShop ?: WARM_UP_PLACEHOLDER_SHOP

  val outboundStarted = TimeSource.Monotonic.markNow()
  val monolithOutcome = deadline.withinBudget { warmMonolith(monolith, shop) }
  val shopifyOutcome =
    if (seededShop == null) WarmUpStepOutcome.Skipped("no_seeded_shop")
    else deadline.withinBudget { warmShopify(shopifyGraphqlServiceFactory, seededShop) }
  val outbound = WarmUpOutboundReport(monolithOutcome, shopifyOutcome, outboundStarted.elapsedNow().inWholeMilliseconds)
  outbound.log(outbound.anyFailed, outbound.logLine())

  val inboundStarted = TimeSource.Monotonic.markNow()
  val bound = serverBound.isCompleted || deadline.withinBudget { serverBound.await(); WarmUpStepOutcome.Ok() } is WarmUpStepOutcome.Ok
  val apiCheckOutcome =
    if (!bound) WarmUpStepOutcome.Skipped("server_not_bound")
    else deadline.withinBudget { loopback.apiCheck(shop, traceId).toStepOutcome() }
  val webhookOutcome =
    if (!bound) WarmUpStepOutcome.Skipped("server_not_bound")
    else deadline.withinBudget { loopback.webhookDelivery(shop, traceId).toStepOutcome() }
  val inbound = WarmUpInboundReport(apiCheckOutcome, webhookOutcome, inboundStarted.elapsedNow().inWholeMilliseconds)
  inbound.log(inbound.anyFailed, inbound.logLine())
  return WarmUpReport(outbound, inbound)
}

/** One store lookup; a `404` is as good as a hit, since only the wire matters here. */
private suspend fun warmMonolith(monolith: MonolithService, shop: ShopDomain): WarmUpStepOutcome =
  when (val result = monolith.getStore(shop.subdomainOnly)) {
    is Success -> WarmUpStepOutcome.Ok()
    is Failure -> {
      logMonolithFailure("getStore", result.reason, "warm_up=true subdomain=${shop.subdomainOnly}")
      WarmUpStepOutcome.Failed(result.reason.errorLabel)
    }
  }

/** Through the production factory, so the token store and the per-shop client are the ones the webhooks will use. */
private suspend fun warmShopify(factory: ShopifyGraphqlServiceFactory, shop: ShopDomain): WarmUpStepOutcome =
  when (val lookup = factory.forShop(shop)) {
    is ShopLookup.Found -> when (val identity = lookup.value.shopIdentity()) {
      is Success -> WarmUpStepOutcome.Ok()
      is Failure -> {
        log.warn { "Warm-up Shopify identity query failed shop=${shop.normalizedShopifyHost} error=${identity.reason.message}" }
        WarmUpStepOutcome.Failed(identity.reason.errorLabel)
      }
    }
    ShopLookup.Missing -> WarmUpStepOutcome.Skipped("no_token")
    // The token store has logged the monolith's failure.
    ShopLookup.Unavailable -> WarmUpStepOutcome.Failed("token_unavailable")
  }

/**
 * A `200`, a `401` (the check for a shop without a token, the production case) and a `502` all mean the pipeline ran
 * end to end; only no answer, or a `5xx` from our own `StatusPages`, says it did not.
 */
private fun Int?.toStepOutcome(): WarmUpStepOutcome = when {
  this == null -> WarmUpStepOutcome.Failed("no_answer")
  this >= 500 && this != 502 -> WarmUpStepOutcome.Failed("http_$this")
  else -> WarmUpStepOutcome.Ok(toString())
}

/**
 * Runs [step] inside what is left before this deadline: not at all once it has passed, and cut short (and reported
 * so) when it passes mid-way. A per-step bound rather than one around the whole warm-up, so the report and its log
 * lines are complete whatever the budget did.
 */
private suspend fun TimeMark.withinBudget(step: suspend () -> WarmUpStepOutcome): WarmUpStepOutcome {
  if (hasPassedNow()) return WarmUpStepOutcome.Skipped("budget")
  return withTimeoutOrNull(-elapsedNow()) { step() } ?: WarmUpStepOutcome.Failed("budget")
}

/** Sixteen hex characters, the shape `CallId` mints, prefixed so the id says what it belongs to. */
private fun newWarmUpTraceId(): String = "warmup-" + Random.nextBytes(8).joinToString("") { "%02x".format(it) }

private fun Any.log(anyFailed: Boolean, line: String) = if (anyFailed) log.warn { line } else log.info { line }

internal fun WarmUpOutboundReport.logLine(): String =
  "Warm-up outbound done took_ms=$tookMillis monolith=${monolith.logFragment()} shopify=${shopify.logFragment()}"

internal fun WarmUpInboundReport.logLine(): String =
  "Warm-up inbound done took_ms=$tookMillis api_check=${apiCheck.logFragment()} webhook=${webhook.logFragment()}"

private fun WarmUpStepOutcome.logFragment(): String = when (this) {
  is WarmUpStepOutcome.Ok -> detail
  is WarmUpStepOutcome.Failed -> "failed error=$label"
  is WarmUpStepOutcome.Skipped -> "skipped reason=$reason"
}
