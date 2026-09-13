package dropnext.dss.lib.ktor

import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import io.ktor.callid.withCallId
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/** The shortest backoff the plugin accepts: the production one would put seconds of sleep into every retry case. */
private const val NO_BACKOFF_MILLIS = 1L

/**
 * The two differences between the clients, and the reasons they exist: the monolith client retries a
 * dropped connection, the shared client never does, because a retried `fulfillmentCreate` would ship the
 * same parcel twice and Shopify's mutations carry no idempotency key. And the monolith client forwards
 * the request's trace id, the shared client does not, because Shopify has no use for it.
 */
class HttpClientBuildersTest {

  @Test
  fun `the monolith client forwards the trace id from the coroutine context as X-Trace-Id`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createMonolithHttpClient(createSharedHttpClient())
    try {
      runBlocking { withCallId("trace-7") { client.get("http://localhost:$port/probe") } }
      assert(upstream.requests.single().headers["X-Trace-Id"] == listOf("trace-7"))
    } finally {
      client.close()
      upstream.stop()
    }
  }

  @Test
  fun `the shared client sends no X-Trace-Id even inside a request`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createSharedHttpClient()
    try {
      runBlocking { withCallId("trace-7") { client.get("http://localhost:$port/probe") } }
      assert(upstream.requests.single().headers["X-Trace-Id"] == null)
    } finally {
      client.close()
      upstream.stop()
    }
  }

  @Test
  fun `the monolith client retries a dropped connection and succeeds`() {
    FakeFlakyServer(failFirstConnections = 1).use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient(), retryBaseDelayMillis = NO_BACKOFF_MILLIS)
      try {
        val response: HttpResponse = runBlocking { client.get(upstream.baseUrl) }
        assert(response.status == HttpStatusCode.OK)
        assert(upstream.connectionCount == 2)
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun `the monolith client gives up after three retries of a dropped connection`() {
    FakeFlakyServer().use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient(), retryBaseDelayMillis = NO_BACKOFF_MILLIS)
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.isFailure)
        assert(upstream.connectionCount == 1 + MONOLITH_MAX_RETRIES)
      } finally {
        client.close()
      }
    }
  }

  /** Every monolith endpoint the DSS calls is idempotent, so a `5xx` from a deploy window is worth a second try. */
  @Test
  fun `the monolith client retries a server error and succeeds`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createMonolithHttpClient(createSharedHttpClient(), retryBaseDelayMillis = NO_BACKOFF_MILLIS)
    try {
      upstream.enqueue(HttpStatusCode.ServiceUnavailable, "")
      upstream.enqueue(HttpStatusCode.OK, "{}")
      val response: HttpResponse = runBlocking { client.get("http://localhost:$port/probe") }
      assert(response.status == HttpStatusCode.OK)
      assert(upstream.requests.size == 2)
    } finally {
      client.close()
      upstream.stop()
    }
  }

  /** Only the last attempt's failure reaches the caller: the ones before it are on record through this line or not at all. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the monolith client logs the failure of every attempt it repeats, without the query string`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createMonolithHttpClient(createSharedHttpClient(), retryBaseDelayMillis = NO_BACKOFF_MILLIS)
    try {
      upstream.enqueue(HttpStatusCode.InternalServerError, "")
      upstream.enqueue(HttpStatusCode.OK, "{}")
      val lines = capturingLogs { runBlocking { client.get("http://localhost:$port/stores?shopify_subdomain=acme") } }
      val retryLine = lines.single { "Monolith call failed, retrying" in it }
      assert(retryLine.startsWith("WARN "))
      assert("method=GET path=/stores status=500" in retryLine)
      assert("retry=1/$MONOLITH_MAX_RETRIES" in retryLine)
      assert("acme" !in retryLine)
    } finally {
      client.close()
      upstream.stop()
    }
  }

  /**
   * A webhook's budget can end during the backoff; by then the failure that started the wait must already be logged, under
   * the trace id the server's plugins put in the MDC, so it sits next to the delivery's `timed_out` line.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the monolith client logs a failure before the backoff, so a cancelled retry keeps its cause and trace id`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val monolithClient = createMonolithHttpClient(createSharedHttpClient(), retryBaseDelayMillis = 60_000)
    try {
      upstream.enqueue(HttpStatusCode.ServiceUnavailable, "")
      val lines = capturingLogs {
        testApplication {
          application {
            installCallId()
            installCallLogging(enabled = false)
            routing {
              get("/probe") {
                val upstreamResponse = withTimeoutOrNull(500.milliseconds) { monolithClient.get("http://localhost:$port/orders") }
                call.respondText(if (upstreamResponse == null) "cancelled" else "answered")
              }
            }
          }
          val answer = client.get("/probe") { header(TRACE_ID_HEADER, "trace-retry") }.bodyAsText()
          assert(answer == "cancelled")
        }
      }
      val retryLine = lines.single { "Monolith call failed, retrying" in it }
      assert("status=503" in retryLine)
      assert("$TRACE_ID_MDC_KEY=trace-retry" in retryLine)
      assert(upstream.requests.size == 1)
    } finally {
      monolithClient.close()
      upstream.stop()
    }
  }

  /** A timeout is the budget running out, not the monolith failing: repeating it would only hold the webhook longer. */
  @Test
  fun `the monolith client does not retry a request that timed out`() {
    FakeFlakyServer(stallFirstConnections = Int.MAX_VALUE).use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient(), requestTimeoutMillis = 300)
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.exceptionOrNull() is HttpRequestTimeoutException)
        assert(upstream.connectionCount == 1)
      } finally {
        client.close()
      }
    }
  }

  /** OkHttp's default dispatcher lets five requests per host through and queues the rest; a webhook burst is larger. */
  @Test
  fun `the shared client keeps more than five requests to one host in flight at once`() {
    FakeFlakyServer(stallFirstConnections = Int.MAX_VALUE).use { upstream ->
      val client = createSharedHttpClient()
      try {
        runBlocking {
          val inFlight = (1..8).map { async(Dispatchers.IO) { runCatching { client.get(upstream.baseUrl) } } }
          val deadline = System.nanoTime() + 3_000_000_000L
          while (upstream.connectionCount < 8 && System.nanoTime() < deadline) delay(20)
          // Under the default limit the sixth to eighth request wait in the dispatcher until a timeout frees a slot.
          assert(upstream.connectionCount == 8)
          inFlight.forEach { it.cancel() }
        }
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun `the shared client does not retry a dropped connection`() {
    FakeFlakyServer().use { upstream ->
      val client = createSharedHttpClient()
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.isFailure)
        assert(upstream.connectionCount == 1)
      } finally {
        client.close()
      }
    }
  }
}
