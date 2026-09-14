package dropnext.dss.lib.logflare

import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.testutil.fake.FakeLogflareServer
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


/**
 * The shipping half of [LogflareAppender], against a real HTTP server on loopback: a fake client
 * would prove the calls were built, not that Logflare's answers are handled, and every interesting
 * failure here is an HTTP one — a non-2xx handshake, a rejected batch, an endpoint that never answers.
 *
 * Ported from the monolith, where this split exists because `logback-classic` is off that project's
 * test classpath and the appender cannot be constructed at all. Here it can, and [LogflareAppenderTest]
 * covers it; the split is kept so a fix on either side still applies to the other.
 */
class LogflareBatchSenderTest {

  private val errors = ConcurrentLinkedQueue<String>()

  private fun senderFor(
    server: FakeLogflareServer,
    maxBatchSize: Int = 50,
    maxQueuedEvents: Int = 10_000,
    flushInterval: Duration = 50.milliseconds,
    closeDrainBudget: Duration = 5.seconds,
    constantFields: Map<String, String> = emptyMap(),
  ) = LogflareBatchSender(
    endpoint = server.endpoint,
    apiKey = LogflareApiKey("test-logflare-key"),
    maxBatchSize = maxBatchSize,
    maxQueuedEvents = maxQueuedEvents,
    flushInterval = flushInterval,
    constantFields = constantFields,
    reportError = { errors += it },
    closeDrainBudget = closeDrainBudget,
  )

  private fun entry(message: String): JsonObject = buildJsonObject { put("message", message) }

  private fun entryWithMetadata(message: String, vararg metadata: Pair<String, String>): JsonObject = buildJsonObject {
    put("message", message)
    put("metadata", buildJsonObject { metadata.forEach { (key, value) -> put(key, value) } })
  }

  private fun metadataOf(server: FakeLogflareServer, message: String): Map<String, String> =
    server.receivedEvents.single { it["message"]!!.jsonPrimitive.content == message }["metadata"]!!.jsonObject
      .mapValues { (_, value) -> value.jsonPrimitive.content }

  private fun messagesOf(server: FakeLogflareServer) =
    server.receivedEvents.map { it["message"]!!.jsonPrimitive.content }

  @Test
  fun `resolves the token of a source Logflare already knows`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dropnext-test"
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("dropnext-test")

      assert(token == "token-for-dropnext-test")
      assert(sender.sourceToken == "token-for-dropnext-test")
      assert(errors.isEmpty())
    }
  }

  @Test
  fun `creates the source when Logflare does not have it yet`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("brand-new-source")

      assert(server.createdSourceNames == listOf("brand-new-source"))
      assert(token == "token-for-brand-new-source")
    }
  }

  @Test
  fun `reports and refuses to resolve a token when the sources call fails`() {
    FakeLogflareServer().use { server ->
      server.sourcesStatusCode = 500
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("dropnext-test")

      // Null is what makes the handshake fall back to a token of its own; until then the token stays
      // empty so nothing posts to `?source=` and loses the lot.
      assert(token == null)
      assert(sender.sourceToken == "")
      assert(errors.any { "500" in it })
    }
  }

  @Test
  fun `the scheduled flush ships what was queued`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      sender.enqueue(entry("first"))
      sender.enqueue(entry("second"))

      assert(server.awaitBatch())
      sender.close()
      assert(messagesOf(server) == listOf("first", "second"))
    }
  }

  @Test
  fun `starting with a source name resolves the token on the flush thread and ships what queued meanwhile`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dropnext-test"
      val sender = senderFor(server)

      // No `resolveSourceToken` here: logging is available from `start` on, and the handshake — two
      // blocking HTTP calls — happens on the flush thread instead of the booting one.
      sender.start("dropnext-test")
      sender.enqueue(entry("logged before the handshake finished"))

      assert(server.awaitBatch())
      sender.close()
      assert(messagesOf(server) == listOf("logged before the handshake finished"))
      assert(sender.sourceToken == "token-for-dropnext-test")
      assert(server.receivedIngestQueries == listOf("source=token-for-dropnext-test"))
      assert(!sender.shipsUnderDerivedToken)
    }
  }

  /**
   * The Logflare inside a local `supabase start` answers the source list with a 500, and the sender
   * used to go quiet for the life of the process. Shipping by name is no way out (a name looked up
   * before the source exists stays "missing" in Logflare's cache for an hour), so the sender brings
   * its own token: creates the source under it, whatever the answer, and posts under it.
   */
  @Test
  fun `a failed handshake creates the source under a derived token and ships under it`() {
    FakeLogflareServer().use { server ->
      server.sourcesStatusCode = 500
      val sender = senderFor(server)

      sender.start("dropnext.test")
      sender.enqueue(entry("still has somewhere to go"))

      assert(server.awaitBatch())
      sender.close()
      val token = derivedSourceToken("dropnext.test")
      assert(messagesOf(server) == listOf("still has somewhere to go"))
      assert(server.createdSourceNames == listOf("dropnext.test"))
      assert(server.createdSourceTokens == listOf(token))
      assert(server.receivedIngestQueries == listOf("source=$token"))
      assert(sender.sourceToken == token)
      assert(sender.shipsUnderDerivedToken)
      // Both the failure and the fallback are reported, or the console shows a handshake error
      // above lines that did arrive in Logflare and nobody can tell why.
      assert(errors.any { "500" in it })
      assert(errors.any { "derived token $token" in it })
    }
  }

  @Test
  fun `the derived token is a pure function of the source name`() {
    // Two boots must post under one source, so the token cannot carry anything but the name.
    assert(derivedSourceToken("dropnext.app") == derivedSourceToken("dropnext.app"))
    assert(derivedSourceToken("dropnext.app") != derivedSourceToken("dropnext.dss"))
    assert(runCatching { java.util.UUID.fromString(derivedSourceToken("dropnext.app")) }.isSuccess)
  }

  @Test
  fun `a flush ships at most one batch and leaves the rest queued`() {
    FakeLogflareServer().use { server ->
      // No schedule started, so `flush` is the only thing that drains: exactly one batch's worth.
      val sender = senderFor(server, maxBatchSize = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      (1..5).forEach { sender.enqueue(entry("event-$it")) }

      sender.flush()

      assert(messagesOf(server) == listOf("event-1", "event-2"))
      assert(sender.queuedEventCount == 3)
    }
  }

  @Test
  fun `closing ships what is still queued`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()
      sender.enqueue(entry("last words"))

      sender.close()

      // The interval is a minute, so nothing but the stop-flush could have shipped this.
      assert(messagesOf(server) == listOf("last words"))
      assert(sender.queuedEventCount == 0)
    }
  }

  /**
   * `flush` returns at once while the token is empty, so a drain loop around it spun a core for the whole budget
   * whenever the process stopped during the handshake. The closing thread's CPU time is what tells a wait from a spin.
   */
  @Test
  fun `closing during a stalled handshake waits for it, bounded by the budget, without spinning`() {
    FakeLogflareServer().use { server ->
      server.sourcesStallMillis = 800
      val sender = senderFor(server, flushInterval = 1.minutes, closeDrainBudget = 300.milliseconds)
      sender.start("dropnext-test")
      sender.enqueue(entry("too early"))
      val threads = ManagementFactory.getThreadMXBean()
      val cpuBefore = threads.currentThreadCpuTime

      sender.close()

      val cpuMillis = (threads.currentThreadCpuTime - cpuBefore) / 1_000_000
      assert(cpuMillis < 150)
      assert(errors.any { "1 events still queued" in it })
      assert(server.receivedEvents.isEmpty())
    }
  }

  @Test
  fun `closing waits for a handshake that finishes inside the budget and then ships what queued`() {
    FakeLogflareServer().use { server ->
      server.sourcesStallMillis = 200
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.start("dropnext-test")
      sender.enqueue(entry("after the handshake"))

      sender.close()

      assert(messagesOf(server) == listOf("after the handshake"))
      assert(sender.queuedEventCount == 0)
    }
  }

  @Test
  fun `closing drains more than one batch`() {
    FakeLogflareServer().use { server ->
      // One `flush` ships `maxBatchSize`; without a drain loop `close` would leave 6 of these behind.
      val sender = senderFor(server, maxBatchSize = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()
      (1..8).forEach { sender.enqueue(entry("event-$it")) }

      sender.close()

      assert(sender.queuedEventCount == 0)
      assert(messagesOf(server).size == 8)
    }
  }

  /**
   * The drop path used to submit a flush task per dropped event onto the scheduled executor's
   * *unbounded* queue, which reintroduced the heap growth the bounded event queue exists to prevent.
   */
  @Test
  fun `overflow does not schedule work per dropped event`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, maxQueuedEvents = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      (1..5_000).forEach { sender.enqueue(entry("event-$it")) }

      // Nothing was scheduled, so nothing drained on its own: the queue still holds exactly its cap.
      assert(sender.queuedEventCount == 2)
      assert(messagesOf(server).isEmpty())
      sender.close()
    }
  }

  /**
   * The appender asks for a flush on every event above the batch size. Each ask used to be a task on
   * the executor's unbounded queue, while each flush could block on Logflare for five seconds: a slow
   * Logflare grew that queue by one task per log line.
   */
  @Test
  fun `a burst of flush requests during a stalled flush submits one task`() {
    FakeLogflareServer().use { server ->
      server.logsStallMillis = 1_000
      // A batch of fifty, so the drain at the end is six round trips and not three hundred: with the classes running
      // concurrently, three hundred did not always fit in the five seconds `close` gives them.
      val sender = senderFor(server, maxBatchSize = 50, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()
      sender.enqueue(entry("first"))
      assert(sender.requestFlush())
      // The first flush is on the wire and stays there for a second; everything below happens meanwhile.
      assert(server.awaitBatchStarted())

      (1..300).forEach { sender.enqueue(entry("burst-$it")) }
      val submitted = (1..300).count { sender.requestFlush() }

      assert(submitted == 1)
      server.logsStallMillis = 0 // Let `close` drain the burst quickly.
      sender.close()
      assert(sender.queuedEventCount == 0)
    }
  }

  @Test
  fun `requestFlush before start submits nothing`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)
      assert(!sender.requestFlush())
    }
  }

  @Test
  fun `a full queue drops events instead of growing without limit`() {
    FakeLogflareServer().use { server ->
      // Nothing draining: a long Logflare outage looks exactly like this from the queue's side.
      val sender = senderFor(server, maxQueuedEvents = 3, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")

      (1..10).forEach { sender.enqueue(entry("event-$it")) }

      assert(sender.queuedEventCount == 3)
      // The oldest three survive: dropping the newest keeps the enqueueing thread from ever blocking,
      // which is the point — a logging call must not park the request thread that made it.
      sender.flush()
      assert(messagesOf(server) == listOf("event-1", "event-2", "event-3"))
    }
  }

  @Test
  fun `the next flush reports how many events were dropped`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, maxQueuedEvents = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      (1..5).forEach { sender.enqueue(entry("event-$it")) }

      sender.flush()

      // Silent loss would be the worst version of this: an operator reading the logs cannot tell a
      // gap from a quiet period.
      assert(errors.any { "dropped 3 log events" in it })
      // And the count resets, or every later flush re-reports the same drops.
      errors.clear()
      sender.flush()
      assert(errors.none { "dropped" in it })
    }
  }

  @Test
  fun `a rejected batch is reported rather than thrown`() {
    FakeLogflareServer().use { server ->
      server.logsStatusCode = 503
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.enqueue(entry("into the void"))

      sender.flush()

      // A throw here would surface inside whatever thread called `log.info { }`.
      assert(errors.any { "Logflare flush failed: 503" in it })
    }
  }

  @Test
  fun `an unreachable endpoint is reported rather than thrown`() {
    // Port 1 on loopback: nothing listens, so the connect fails rather than the request.
    val sender = LogflareBatchSender(
      endpoint = "http://127.0.0.1:1",
      apiKey = LogflareApiKey("test-logflare-key"),
      flushInterval = 1.minutes,
      reportError = { errors += it },
    )

    assert(sender.resolveSourceToken("dropnext-test") == null)
    assert(errors.any { "/api/sources" in it })
  }

  @Test
  fun `every request carries the api key`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.enqueue(entry("first"))

      sender.flush()

      // Two shapes, deliberately: Logflare wants a bearer token on the management API and a raw
      // `X-API-KEY` on the ingest one, and sending the wrong one is a silent 401 in production.
      assert("Bearer test-logflare-key" in server.receivedApiKeys)
      assert("test-logflare-key" in server.receivedApiKeys)
    }
  }

  @Test
  fun `constant fields are shipped in every event's metadata next to the event's own`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, constantFields = mapOf("service" to "dss", "version" to "v1"))
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      sender.enqueue(entryWithMetadata("with metadata", "level" to "INFO", "trace_id" to "abc"))
      sender.enqueue(entry("without metadata"))

      assert(server.awaitBatch())
      sender.close()
      assert(metadataOf(server, "with metadata") == mapOf("service" to "dss", "version" to "v1", "level" to "INFO", "trace_id" to "abc"))
      assert(metadataOf(server, "without metadata") == mapOf("service" to "dss", "version" to "v1"))
    }
  }

  @Test
  fun `without constant fields the entry ships unchanged`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      sender.enqueue(entryWithMetadata("plain", "level" to "WARN"))

      assert(server.awaitBatch())
      sender.close()
      assert(metadataOf(server, "plain") == mapOf("level" to "WARN"))
      assert(server.receivedEvents.single().keys == setOf("message", "metadata"))
    }
  }

  @Test
  fun `an entry whose metadata already has a constant key keeps its own value`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, constantFields = mapOf("service" to "dss"))
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      sender.enqueue(entryWithMetadata("overridden", "service" to "something-else"))

      assert(server.awaitBatch())
      sender.close()
      assert(metadataOf(server, "overridden") == mapOf("service" to "something-else"))
    }
  }
}
