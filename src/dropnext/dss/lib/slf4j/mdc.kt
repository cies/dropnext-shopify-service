package dropnext.dss.lib.slf4j

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC


// The MDC keys that this service logs under.
// Shared with the monolith (spelled `snake_case`, like `trace_id`),
// so a Logflare query on any of them returns the lines of both services: never rename one side only.

/** The MDC key `logback.xml` prints; `installCallLogging` fills it per request through Ktor's `callIdMdc`. */
const val TRACE_ID_MDC_KEY = "trace_id"

/** The normalized host of the shop a handler resolved, on every line from there on: the shop is this service's tenant. */
const val SHOP_MDC_KEY = "shop"

/** The request's path (every inbound path is a constant without parameters, so it is the route) and verb, on every line of a request. */
const val ROUTE_MDC_KEY = "route"
const val METHOD_MDC_KEY = "method"

/** Shopify's `X-Shopify-Topic` and `X-Shopify-Webhook-Id` as sent (cut to the trace id's length cap), on every line of a webhook delivery. */
const val TOPIC_MDC_KEY = "topic"
const val WEBHOOK_ID_MDC_KEY = "webhook_id"

/**
 * The current request's trace id, or `null` outside a request.
 * Read through the MDC so any layer can correlate without a `call`.
 */
fun currentTraceId(): String? = MDC.get(TRACE_ID_MDC_KEY)

/**
 * Runs [block] with every entry that has a value added to the MDC, for a value Ktor's call-logging providers cannot supply:
 * one a handler learns (the shop, parsed from an `X-Shopify-Shop-Domain` header, a query parameter, or the body),
 * or the warm-up's own trace id outside any request.
 *
 * The MDC is thread-local and a suspended block resumes on whatever thread is free,
 * so a plain `MDC.put` would cover the lines up to the first suspension and then leak into whatever that thread runs next.
 *
 * [kotlinx.coroutines.slf4j.MDCContext] installs the map on every coroutine `resume` and
 * puts back the one it replaced when the block ends, normally or not:
 * the keys are gone afterward, an outer value a nested call narrowed is back, and
 * what the map already held (the trace id) stays.
 *
 * A null value is skipped, so a caller can pass an optional without branching.
 */
suspend fun <T> withMdcEntries(vararg entries: Pair<String, String?>, block: suspend () -> T): T {
  val contextMap = MDC.getCopyOfContextMap().orEmpty() +
    entries.mapNotNull { (key, value) -> value?.let { key to it } }
  return withContext(MDCContext(contextMap)) { block() }
}
