package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryEvent
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpRetryEventData
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher


private val log = KotlinLogging.logger {}

// The timeouts in this file line up with two limits set elsewhere. Shopify waits five seconds for a webhook's answer,
// and the handler holds the work behind a delivery to four, plus a short grace for a monolith write already sent
// (`WEBHOOK_MIRROR_BUDGET`, `WEBHOOK_WRITE_GRACE`). The monolith waits thirty seconds
// (its client's read timeout) for the routes it calls; the calls those routes make have to fit inside that.

/**
 * How long opening a connection may take, to Shopify and to the monolith alike. Both answer from well-connected data
 * centres, where a TCP and TLS handshake takes tens of milliseconds; five seconds is a host that is not there, and waiting
 * longer would only eat into the budget of the request that needs the connection.
 */
const val CONNECT_TIMEOUT_MILLIS = 5_000L

/**
 * How long one Shopify call (a Graphql operation, the OAuth code exchange) may take. The Admin API answers in well under
 * a second, a large order query in one or two; ten seconds leaves a wide margin. It has to stay far below the thirty
 * seconds the monolith waits for a `sync-shipments` or a `tracking-update`, which make one to a few of these calls in a
 * row, so a stuck call fails the request while the monolith still waits for the answer.
 */
const val SHOPIFY_REQUEST_TIMEOUT_MILLIS = 10_000L

/**
 * How long one monolith call may take before it is given up on. A healthy monolith answers every
 * endpoint the DSS calls in well under a second: each is one transaction on indexed rows (a store
 * lookup, an order insert plus a queue message, an upsert of at most one product's variants), so five
 * seconds still covers a cold connection pool or a GC pause. The bound that decides it is the monolith's
 * own client: where it calls us and we call it back (`PUT /stores/api-key`, the token lookup behind its sync
 * calls) it waits thirty seconds, and four attempts of five seconds plus the backoff stay under that.
 */
const val MONOLITH_REQUEST_TIMEOUT_MILLIS = 5_000L

/** Connection resets, refused connections and `5xx` answers; a timeout of any kind is not one of these. */
const val MONOLITH_MAX_RETRIES = 3

/**
 * The first pause before a monolith call is repeated. Each next pause doubles, capped at four times this, with up to half
 * of it added at random so retries from concurrent requests do not arrive in lockstep. Half a second, one, two: the three
 * retries wait under five seconds in total, so a webhook's four-second budget still has room for one or two, and a
 * monolith that is restarting gets a moment to come back.
 */
const val MONOLITH_RETRY_BASE_DELAY_MILLIS = 500L

/**
 * OkHttp's dispatcher lets five requests per host through at a time, a browser-like default. Every
 * call this service makes goes to one of two hosts, the monolith and the shop's Shopify domain, and a
 * webhook burst is exactly that many concurrent calls to the same host: the sixth would wait in the
 * dispatcher's queue, holding its webhook open at Shopify meanwhile. Sized for a burst, not for the CPU.
 */
const val MAX_CONCURRENT_REQUESTS_PER_HOST = 32
const val MAX_CONCURRENT_REQUESTS = 64

fun createSharedHttpClient(): HttpClient = HttpClient(OkHttp) {
  engine {
    config {
      dispatcher(
        Dispatcher().apply {
          maxRequests = MAX_CONCURRENT_REQUESTS
          maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
        },
      )
      // The same bounds as the plugin below, so a request the plugin leaves alone is not held for OkHttp's own defaults.
      connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      readTimeout(SHOPIFY_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      writeTimeout(SHOPIFY_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }
  }
  install(HttpTimeout) {
    requestTimeoutMillis = SHOPIFY_REQUEST_TIMEOUT_MILLIS
    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
    socketTimeoutMillis = SHOPIFY_REQUEST_TIMEOUT_MILLIS
  }
  install(ContentNegotiation) {
    json(AppJson)
  }
  install(ShopifyDeprecationWarnings)
  // No Logging plugin: avoids accidentally logging Authorization headers or JSON bodies to monolith/Shopify.
}

/**
 * Derives the monolith-specific [HttpClient]: a short request timeout ([MONOLITH_REQUEST_TIMEOUT_MILLIS]),
 * retries with exponential backoff ([MONOLITH_RETRY_BASE_DELAY_MILLIS]) on a dropped or refused connection
 * and on a `5xx`, and none on a timeout. Both are parameters so a test does not have to wait them out. Every
 * monolith endpoint the DSS calls is idempotent, so repeating a call the monolith may already have processed
 * is safe; a timed-out call is not repeated because the time budget, not the outcome, is what ran out, and
 * three more attempts would only hold the request longer. Shopify Graphql and OAuth traffic keep the base
 * [createSharedHttpClient]: its mutations carry no idempotency key, so a retried `fulfillmentCreate` would ship
 * the same parcel twice.
 *
 * It also forwards the request's trace id, read from the coroutine context the server's `CallId` plugin
 * fills, so the monolith's log lines for a call can be found from ours. Shopify does not get the header.
 * The OkHttp engine and connection pool are shared.
 *
 * Every attempt it repeats is logged before the backoff ([logRetriedMonolithFailure]): only the last attempt's
 * failure reaches the caller, and none does when the caller's time budget cancels the wait.
 *
 * [maxRetries] is a parameter because the routes differ in how many attempts fit their budget: a monolith-facing
 * route has thirty seconds, a webhook four (`WEBHOOK_MONOLITH_MAX_RETRIES`).
 */
fun createMonolithHttpClient(
  baseHttpClient: HttpClient,
  requestTimeoutMillis: Long = MONOLITH_REQUEST_TIMEOUT_MILLIS,
  retryBaseDelayMillis: Long = MONOLITH_RETRY_BASE_DELAY_MILLIS,
  maxRetries: Int = MONOLITH_MAX_RETRIES,
): HttpClient = baseHttpClient.config {
  install(HttpTimeout) {
    this.requestTimeoutMillis = requestTimeoutMillis
    socketTimeoutMillis = requestTimeoutMillis
  }
  install(HttpRequestRetry) {
    // Spelled out in full: the plugin's defaults retry server errors and exceptions, and a call to
    // `retryOnExceptionIf` alone replaces only the exception half, leaving the other in place unseen.
    this.maxRetries = maxRetries
    retryOnServerErrors(maxRetries)
    retryOnExceptionIf(maxRetries) { _, cause -> cause is IOException && !cause.isTimeout() }
    exponentialDelay(
      base = 2.0,
      baseDelayMs = retryBaseDelayMillis,
      maxDelayMs = 4 * retryBaseDelayMillis,
      randomizationMs = retryBaseDelayMillis / 2,
    )
  }
  install(CallId) {
    addToHeader(TRACE_ID_HEADER)
  }
}.also { client ->
  // Raised by the plugin before it waits, in the request's coroutine, so the line carries the trace id.
  client.monitor.subscribe(HttpRequestRetryEvent) { retry -> logRetriedMonolithFailure(retry, maxRetries) }
}

/**
 * The failure of an attempt that is about to be repeated. A webhook whose budget ends during the backoff answers
 * `timed_out`, and this line, under the same trace id, is then the only record of what the monolith did. Only the
 * status or the exception's message: the body cannot be read from here, and the path stands in for the URL so the
 * query string stays out of the log.
 */
private fun logRetriedMonolithFailure(retry: HttpRetryEventData, maxRetries: Int) {
  val failure = retry.response?.let { "status=${it.status.value}" } ?: "error=${retry.cause?.message ?: "unknown"}"
  log.warn {
    "Monolith call failed, retrying method=${retry.request.method.value} path=${retry.request.url.encodedPath} " +
      "$failure retry=${retry.retryCount}/$maxRetries"
  }
}

/** Ktor's own three timeout exceptions; `HttpRequestTimeoutException` is an `IOException` too, hence the explicit check. */
private fun Throwable.isTimeout(): Boolean =
  this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException
