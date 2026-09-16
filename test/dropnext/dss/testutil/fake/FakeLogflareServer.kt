package dropnext.dss.testutil.fake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * A stand-in for the Logflare HTTP API, on loopback.
 *
 * A real server rather than a stubbed HTTP client: the appender's failure modes are HTTP ones —
 * a non-2xx on the source handshake, a batch the server rejects — and only a real one exercises the
 * client the appender actually ships with. `com.sun.net.httpserver` is in the JDK, so this costs no
 * dependency.
 *
 * Recording is concurrent because the flush runs on the appender's own background thread.
 */
class FakeLogflareServer : AutoCloseable {

  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

  /** Every batch the sender posted, in arrival order, as the `batch` array's entries flattened. */
  private val received = ConcurrentLinkedQueue<JsonObject>()

  private val sourceRequestBodies = ConcurrentLinkedQueue<String>()

  /**
   * The key as each endpoint received it, apart: Logflare wants a bearer token on the management API and a raw
   * `X-API-KEY` on the ingest one, and one list for both could not tell a swap of the two from the real thing.
   */
  private val sourcesAuthorizations = ConcurrentLinkedQueue<String>()

  private val ingestApiKeys = ConcurrentLinkedQueue<String>()

  /** The query string of every `/api/logs` post: which token the sender shipped under. */
  private val ingestQueries = ConcurrentLinkedQueue<String>()

  // The settings below are volatile because the server's own thread reads them, and a test may change one while a
  // request is on its way (a stall lifted so `close` can drain quickly).

  /** Set before `start()` to make the source handshake fail, or the batch endpoint reject. */
  @Volatile
  var sourcesStatusCode: Int = 200

  @Volatile
  var logsStatusCode: Int = 200

  /** The sources the fake knows about; empty means the sender has to create one. */
  @Volatile
  var knownSourceName: String? = null

  /** How long `/api/logs` holds a batch before answering, so a test can watch the sender while a flush is on the wire. */
  @Volatile
  var logsStallMillis: Long = 0

  /** How long `/api/sources` holds the handshake before answering: what a sender closed during it has to deal with. */
  @Volatile
  var sourcesStallMillis: Long = 0

  val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

  val receivedEvents: List<JsonObject> get() = received.toList()

  /** The `Authorization` header of every `/api/sources` call, in arrival order; empty when none was sent. */
  val receivedSourcesAuthorizations: List<String> get() = sourcesAuthorizations.toList()

  /** The `X-API-KEY` header of every `/api/logs` post, in arrival order; empty when none was sent. */
  val receivedIngestApiKeys: List<String> get() = ingestApiKeys.toList()

  val receivedIngestQueries: List<String> get() = ingestQueries.toList()

  val createdSourceNames: List<String>
    get() = sourceRequestBodies.map { Json.parseToJsonElement(it).jsonObject["name"]!!.jsonPrimitive.content }

  /** The token each create carried, or null: the fallback brings its own, the normal handshake lets Logflare mint one. */
  val createdSourceTokens: List<String?>
    get() = sourceRequestBodies.map { Json.parseToJsonElement(it).jsonObject["token"]?.jsonPrimitive?.content }

  private val batchArrived = AtomicReference(CountDownLatch(1))

  private val batchStarted = AtomicReference(CountDownLatch(1))

  init {
    server.createContext("/api/sources") { exchange -> handleSources(exchange) }
    server.createContext("/api/logs") { exchange -> handleLogs(exchange) }
    // Handlers run on the server's own thread. Nothing here blocks unless a test asked for a stalled
    // batch, and then blocking the one thread is the point: the sender sees one slow Logflare.
    server.executor = null
    server.start()
  }

  /**
   * Blocks until a batch this call has not already seen arrives, or the timeout passes. The latch is
   * replaced once it fires, so a second `awaitBatch` waits for a second batch instead of returning
   * true on the first one all over again.
   */
  fun awaitBatch(timeout: Duration = 5.seconds): Boolean = await(batchArrived, timeout)

  /** Like [awaitBatch], but fires when the batch request arrives rather than when it is answered: what a stalled flush looks like. */
  fun awaitBatchStarted(timeout: Duration = 5.seconds): Boolean = await(batchStarted, timeout)

  private fun await(signal: AtomicReference<CountDownLatch>, timeout: Duration): Boolean {
    val latch = signal.get()
    val fired = latch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    if (fired) signal.compareAndSet(latch, CountDownLatch(1))
    return fired
  }

  /**
   * Gives a background flush the chance to ship something it should not, then reports that nothing
   * came. Pair it with an assertion on [receivedEvents]: this call bounds the wait, the other states
   * the fact.
   */
  fun awaitNoBatch(timeout: Duration = 1.seconds): Boolean = !awaitBatch(timeout)

  private fun handleSources(exchange: HttpExchange) {
    sourcesAuthorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
    if (sourcesStallMillis > 0) Thread.sleep(sourcesStallMillis)
    // Recorded before the forced status: the local Logflare writes the row and then answers 500,
    // and a test of the fallback wants to see what was posted.
    val postedBody = if (exchange.requestMethod == "GET") null else exchange.requestBody.readBytes().decodeToString()
    postedBody?.let { sourceRequestBodies += it }
    if (sourcesStatusCode != 200) return exchange.respond(sourcesStatusCode, """{"error":"nope"}""")

    if (postedBody == null) {
      val known = knownSourceName
      val body = if (known == null) "[]" else """[{"name":"$known","token":"token-for-$known"}]"""
      exchange.respond(200, body)
    } else {
      val name = Json.parseToJsonElement(postedBody).jsonObject["name"]!!.jsonPrimitive.content
      exchange.respond(200, """{"name":"$name","token":"token-for-$name"}""")
    }
  }

  private fun handleLogs(exchange: HttpExchange) {
    ingestApiKeys += exchange.requestHeaders.getFirst("X-API-KEY").orEmpty()
    ingestQueries += exchange.requestURI.rawQuery.orEmpty()
    batchStarted.get().countDown()
    if (logsStallMillis > 0) Thread.sleep(logsStallMillis)
    val body = exchange.requestBody.readBytes().decodeToString()
    Json.parseToJsonElement(body).jsonObject["batch"]!!.jsonArray.forEach { received += it.jsonObject }
    exchange.respond(logsStatusCode, """{"message":"ok"}""")
    batchArrived.get().countDown()
  }

  private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }

  override fun close() {
    server.stop(0)
  }
}
