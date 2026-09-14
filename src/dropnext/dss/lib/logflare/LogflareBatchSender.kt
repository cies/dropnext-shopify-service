package dropnext.dss.lib.logflare

import dropnext.dss.domain.LogflareApiKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration as JavaDuration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


/**
 * Everything here encodes and parses `JsonObject` trees, where none of the settings a serializer
 * configuration carries would apply, so this borrows neither `AppJson` nor `MonolithJson`:
 * both describe a wire contract this traffic is not part of.
 */
private val jsonMapper = Json

/** How long [LogflareBatchSender.close] may spend on the handshake and the drain together before giving up on the rest. */
private val CLOSE_DRAIN_BUDGET: Duration = 5.seconds

/**
 * A UUID that is a pure function of the source name, so every boot posts under the same one.
 * Version 3 (`nameUUIDFromBytes`) rather than random, because the point is that it can be recomputed.
 */
internal fun derivedSourceToken(sourceName: String): String =
  UUID.nameUUIDFromBytes("logflare-source:$sourceName".toByteArray()).toString()

/** Short timeouts throughout: a log shipper must never be the reason a thread is parked. */
internal fun defaultLogflareClient(): HttpClient = HttpClient.newBuilder()
  .connectTimeout(JavaDuration.ofSeconds(5))
  .build()

/**
 * Everything in [LogflareAppender] that is not Logback: the source-token handshake, the bounded
 * queue, the batch flush, and the flush schedule.
 *
 * The split is the monolith's, kept so a fix found on either side ports to the other: there, the
 * appender's base class is unreachable from tests because `logback-classic` is off that project's
 * test classpath. Here it is reachable and tested — but everything that can fail in an interesting
 * way still lives on this side of the boundary, and this is the half worth reading first.
 *
 * The HTTP client is the JDK's rather than the shared Ktor one: this must keep shipping while the
 * application's clients are being closed at shutdown, and a shipper that logged its own failures
 * through the client it was shipping for would feed itself.
 *
 * [reportError] is where an appender would call `addError`, passed in for the same reason.
 */
class LogflareBatchSender(
  private val endpoint: String,
  private val apiKey: LogflareApiKey,
  private val maxBatchSize: Int = 50,
  private val maxQueuedEvents: Int = 10_000,
  private val flushInterval: Duration = 1.seconds,
  private val client: HttpClient = defaultLogflareClient(),
  /**
   * Fields every shipped event carries in its `metadata`, such as which service and which deploy
   * printed it: constant for the life of the process, so the MDC (scoped to a unit of work and
   * cleared after it) is the wrong place for them. A key the event itself carries wins.
   */
  constantFields: Map<String, String> = emptyMap(),
  private val reportError: (String) -> Unit,
  /** A parameter so a test of [close] need not wait the production budget out. */
  private val closeDrainBudget: Duration = CLOSE_DRAIN_BUDGET,
) {

  private val constantMetadata: Map<String, JsonElement> = constantFields.mapValues { (_, value) -> JsonPrimitive(value) }

  /**
   * Bounded, and that is the point: an unbounded queue turns a Logflare outage into heap growth
   * without limit, in a process whose log volume rises exactly when the outage does.
   */
  private val queue = LinkedBlockingQueue<JsonObject>(maxQueuedEvents)

  /** Reported and reset on the next flush, so an operator learns that logs were lost rather than delayed. */
  private val droppedSinceLastReport = AtomicLong()

  private var scheduledFlushExecutor: ScheduledExecutorService? = null

  /** The source-token handshake [start] put on the flush thread, which [close] has to wait for before it can ship. */
  private var handshake: Future<*>? = null

  /** Set while a requested flush waits on the executor, so a burst of appends submits one task, not one per line. */
  private val flushRequested = AtomicBoolean(false)

  /** The token batches post under; empty until the handshake settled on one, which keeps [flush] from posting to `?source=`. */
  @Volatile
  var sourceToken: String = ""
    private set

  /** True when the handshake could not list the sources and made the token up; a test's way of telling the two paths apart. */
  @Volatile
  var shipsUnderDerivedToken: Boolean = false
    private set

  val queuedEventCount: Int get() = queue.size

  /**
   * Looks up the source token by name, creating the source when Logflare does not have it yet.
   * Null when neither worked; [handshake] then falls back to a token of its own making.
   */
  fun resolveSourceToken(sourceName: String): String? {
    val sources = fetchJson("$endpoint/api/sources") ?: return null

    val existing = sources.jsonArray.firstOrNull {
      it.jsonObject["name"]?.jsonPrimitive?.content == sourceName
    }
    val token = if (existing != null) {
      existing.jsonObject["token"]?.jsonPrimitive?.content
    } else {
      val created = postJson("$endpoint/api/sources", buildJsonObject { put("name", sourceName) })
      if (created == null) {
        reportError("Logflare: failed to create source '$sourceName'")
        return null
      }
      created.jsonObject["token"]?.jsonPrimitive?.content
    }
    sourceToken = token ?: return null
    return sourceToken
  }

  /**
   * Makes the token up when Logflare would not hand one out and creates the source under it.
   *
   * The Logflare inside a local `supabase start` answers `GET /api/sources` with a 500 on its
   * Postgres backend, which used to silence the shipper for the life of the process.
   * Shipping by `?source_name=` instead is not an option:
   * a name Logflare is asked about before the source exists is cached as missing for an hour,
   * and creating the source without a token of our own makes a duplicate on every boot,
   * after which the name resolves to nothing at all.
   * A token derived from the name is stable across boots, the create-call is a no-op once the source exists
   * (`422`, the token is unique), and the batches post under a token, the one lookup that is never poisoned.
   *
   * The create-response is ignored on purpose:
   * locally it is a 500 from the same bug even though the row was written.
   */
  private fun handshake(sourceName: String) {
    if (resolveSourceToken(sourceName) != null) return
    val token = derivedSourceToken(sourceName)
    val created = createSourceQuietly(sourceName, token)
    reportError("Logflare: could not resolve a token for source '$sourceName', shipping under derived token $token (create answered $created)")
    sourceToken = token
    shipsUnderDerivedToken = true
  }

  /**
   * Begins the periodic flush.
   *
   * Pass [sourceName] to have the token handshake run on the flush thread, before the first flush:
   * it is one or two blocking HTTP calls to Logflare, and doing them on the calling thread would put
   * them in front of everything else the process still has to boot.
   * Logging works from this call onward: events queue while the handshake is in flight.
   * Callers that resolved the token themselves (the tests do) leave it null.
   */
  fun start(sourceName: String? = null) {
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "logflare-flush").apply { isDaemon = true }
    }
    scheduledFlushExecutor = executor
    if (sourceName != null) handshake = executor.submit { handshake(sourceName) }
    executor.scheduleAtFixedRate(
      ::flush, flushInterval.inWholeMilliseconds, flushInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS
    )
  }

  /**
   * Queues one prepared entry, dropping it when the queue is full.
   *
   * Dropping the newest rather than blocking: the alternative is a logging call that parks the
   * request thread it was made from, which is a worse failure than a lost log line.
   *
   * The drop path deliberately does *not* ask for an early flush. It used to, in the monolith, and
   * that turned the bounded queue this class exists for into an unbounded one: the scheduled
   * executor's own task queue has no limit, so every dropped event allocated a task on another queue
   * that grew for as long as the overflow lasted — the situation the cap is meant to prevent. Since
   * [flush] is `@Synchronized`, those tasks then serialized behind each other and drained slower
   * than they arrived. The scheduled flush already runs every [flushInterval].
   */
  fun enqueue(entry: JsonObject) {
    if (queue.offer(entry.withConstantMetadata())) return
    droppedSinceLastReport.incrementAndGet()
  }

  private fun JsonObject.withConstantMetadata(): JsonObject {
    if (constantMetadata.isEmpty()) return this
    val ownMetadata = this["metadata"] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(this + ("metadata" to JsonObject(constantMetadata + ownMetadata)))
  }

  /**
   * Asks for an out-of-band flush; answers whether this call submitted one. A no-op before [start],
   * after [close], and while a requested flush is still waiting to run: the appender asks on every
   * event above the batch size, and without the gate each ask was a task on the executor's unbounded
   * queue while each flush could block on Logflare for five seconds, so a slow Logflare grew that
   * queue by one task per log line.
   */
  fun requestFlush(): Boolean {
    val executor = scheduledFlushExecutor ?: return false
    if (!flushRequested.compareAndSet(false, true)) return false
    try {
      executor.submit {
        // Cleared as the task starts, not as it ends: events that arrive while this flush is on the
        // wire may queue exactly one more, which is what keeps a burst draining back to back.
        flushRequested.set(false)
        flush()
      }
    } catch (_: RejectedExecutionException) {
      flushRequested.set(false) // `close` shut the executor down between the null check and the submit.
      return false
    }
    return true
  }

  @Synchronized
  fun flush() {
    // Before the handshake completes there is nothing to post under, and the events stay queued.
    val token = sourceToken.ifEmpty { return }

    reportDroppedEvents()

    val events = buildList {
      while (size < maxBatchSize) {
        queue.poll()?.let(::add) ?: break
      }
    }
    if (events.isEmpty()) return

    val body = jsonMapper.encodeToString(buildJsonObject { put("batch", JsonArray(events)) })
    val request = requestTo("$endpoint/api/logs?source=$token")
      .header("X-API-KEY", apiKey.value)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    try {
      val response = client.send(request, BodyHandlers.ofString())
      if (response.statusCode() !in 200..299) {
        reportError("Logflare flush failed: ${response.statusCode()} ${response.body()}")
      }
    } catch (e: Exception) {
      reportError("Logflare flush error: ${e.message}")
    }
  }

  private fun reportDroppedEvents() {
    val dropped = droppedSinceLastReport.getAndSet(0)
    if (dropped > 0) {
      reportError("Logflare: dropped $dropped log events, the $maxQueuedEvents-event queue is full")
    }
  }

  /**
   * Stops the schedule, ships whatever is still queued, and releases the HTTP client.
   *
   * Nothing ships before the handshake has settled on a token or a name, so one still in flight is waited for first,
   * with the same budget. Waited for, not looped over: [flush] returns at once until then, and a drain loop around it used to
   * spin a core for the whole budget whenever the process stopped during the handshake (a task killed at boot by a
   * failing health check, a rollback seconds after start, a slow Logflare), inside the shutdown hook where ECS is
   * counting down its thirty seconds.
   *
   * Drains in a loop because [flush] ships at most [maxBatchSize] events per call: a single call
   * would have posted 50 of a queued 10,000 and dropped the rest, at exactly the moment those lines
   * are most worth having. Bounded by [closeDrainBudget] because this runs from the shutdown hook,
   * where an unreachable Logflare must not be able to hang the JVM — 10,000 events at 50 per round
   * trip is 200 sequential HTTP calls. What could not be shipped is reported rather than lost quietly.
   */
  fun close() {
    scheduledFlushExecutor?.shutdown()
    val deadline = System.nanoTime() + closeDrainBudget.inWholeNanoseconds
    runCatching { handshake?.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
    while (queue.isNotEmpty() && sourceToken.isNotEmpty() && System.nanoTime() < deadline) flush()
    if (queue.isNotEmpty()) {
      reportError("Logflare: ${queue.size} events still queued after $closeDrainBudget at shutdown")
    }
    scheduledFlushExecutor?.awaitTermination(5, TimeUnit.SECONDS)
    scheduledFlushExecutor = null
    client.close()
  }

  private fun requestTo(url: String): HttpRequest.Builder = HttpRequest.newBuilder()
    .uri(URI.create(url))
    .timeout(JavaDuration.ofSeconds(5))

  /** Answers what the create call got — a status, or the failure's message — for the fallback's report line, and nothing else. */
  private fun createSourceQuietly(sourceName: String, token: String): String {
    val body = jsonMapper.encodeToString(buildJsonObject { put("name", sourceName); put("token", token) })
    val request = requestTo("$endpoint/api/sources")
      .header("Authorization", "Bearer ${apiKey.value}")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    return try {
      client.send(request, BodyHandlers.discarding()).statusCode().toString()
    } catch (e: Exception) {
      e.message ?: e.javaClass.simpleName
    }
  }

  private fun fetchJson(url: String): JsonElement? =
    executeForJson(url, requestTo(url).header("Authorization", "Bearer ${apiKey.value}").GET().build())

  private fun postJson(url: String, body: JsonObject): JsonElement? = executeForJson(
    url,
    requestTo(url)
      .header("Authorization", "Bearer ${apiKey.value}")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.encodeToString(body)))
      .build(),
  )

  private fun executeForJson(url: String, request: HttpRequest): JsonElement? =
    try {
      val response = client.send(request, BodyHandlers.ofString())
      if (response.statusCode() !in 200..299) {
        reportError("Logflare API $url: ${response.statusCode()} ${response.body()}")
        null
      } else {
        jsonMapper.parseToJsonElement(response.body())
      }
    } catch (e: Exception) {
      reportError("Logflare API $url: ${e.message}")
      null
    }
}
