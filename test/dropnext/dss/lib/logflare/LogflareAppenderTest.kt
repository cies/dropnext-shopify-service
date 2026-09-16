package dropnext.dss.lib.logflare

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.LoggingEvent
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.testutil.fake.FakeLogflareServer
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.awaitUntilBlocking
import dropnext.dss.testutil.helper.logbackContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.MDC
import org.slf4j.event.KeyValuePair


/**
 * The Logback half, which the monolith's copy of this appender cannot test: `logback-classic` is off
 * its test classpath. Here it is on the classpath, so these run the real path — a real logger, the
 * real appender, a real HTTP server — and pin the one thing the feature exists for: the request's
 * `trace_id` arrives at Logflare as a queryable field rather than as text inside the message.
 */
class LogflareAppenderTest {

  private val loggerContext = logbackContext()

  /** Logs [emit] through an appender pointed at [server], on a logger of its own so no other test sees it. */
  private fun shippedBy(
    server: FakeLogflareServer,
    service: String = "",
    version: String = "",
    maxBatchSize: Int = 50,
    flushInterval: Duration = 50.milliseconds,
    emit: (LogbackLogger) -> Unit,
  ): List<JsonObject> {
    val appender = LogflareAppender().apply {
      this.service = service
      this.version = version
      this.maxBatchSize = maxBatchSize
      this.flushInterval = flushInterval
      sourceName = "dss-test"
      apiKey = LogflareApiKey("test-logflare-key")
      endpoint = server.endpoint
      context = loggerContext
    }
    val logger = testLogger()
    logger.addAppender(appender)
    logger.isAdditive = false // Keeps these lines out of the console the suite is printing to.
    appender.start()
    try {
      emit(logger)
      assert(server.awaitBatch())
      return server.receivedEvents
    } finally {
      appender.stop()
      logger.detachAppender(appender)
      logger.isAdditive = true
    }
  }

  @Test
  fun `ships the message with the level, the logger and the request's trace id`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        MDC.put(TRACE_ID_MDC_KEY, "trace-abc-123")
        try {
          logger.info("webhook accepted")
        } finally {
          MDC.remove(TRACE_ID_MDC_KEY)
        }
      }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert(events.single()["message"]!!.jsonPrimitive.content == "webhook accepted")
      assert(metadata["level"]!!.jsonPrimitive.content == "INFO")
      assert(metadata["logger"]!!.jsonPrimitive.content == "dropnext.dss.test.logflare")
      // The whole point: correlating a DSS line with the monolith line it caused.
      assert(metadata[TRACE_ID_MDC_KEY]!!.jsonPrimitive.content == "trace-abc-123")
    }
  }

  /** Two services ship to one Logflare, and which one and which deploy wrote a line is what a query across them asks first. */
  @Test
  fun `ships the service and the version on every event, next to the trace id`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server, service = "dss", version = "v1") { logger ->
        MDC.put(TRACE_ID_MDC_KEY, "trace-abc-123")
        try {
          logger.info("webhook accepted")
        } finally {
          MDC.remove(TRACE_ID_MDC_KEY)
        }
      }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert(metadata["service"]!!.jsonPrimitive.content == "dss")
      assert(metadata["version"]!!.jsonPrimitive.content == "v1")
      assert(metadata[TRACE_ID_MDC_KEY]!!.jsonPrimitive.content == "trace-abc-123")
    }
  }

  /** Blank is unset, and an unset field is absent rather than an empty string a query would match. */
  @Test
  fun `ships neither field when the service and the version are left blank`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger -> logger.info("unlabelled") }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert("service" !in metadata)
      assert("version" !in metadata)
    }
  }

  @Test
  fun `ships the stack trace of a logged throwable`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        logger.warn("monolith rejected the order", IllegalStateException("boom"))
      }

      val error = events.single()["metadata"]!!.jsonObject["error"]!!.jsonPrimitive.content
      assert("IllegalStateException" in error)
      assert("boom" in error)
    }
  }

  /**
   * The fields a caller attaches to a line rather than writes into it. Shipping one under its own
   * JSON type is the whole reason for attaching it: a number written as text cannot be compared or
   * ranged over in a Logflare query.
   */
  @Test
  fun `ships a key-value field under the JSON type Logflare can query it by`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        val event = LoggingEvent("", logger, Level.INFO, "webhook mirrored", null, null)
        event.addKeyValuePair(KeyValuePair("attempt", 2))
        event.addKeyValuePair(KeyValuePair("retried", true))
        event.addKeyValuePair(KeyValuePair("shop", "acme.myshopify.com"))
        logger.callAppenders(event)
      }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert(metadata["attempt"]!!.jsonPrimitive.int == 2)
      assert(!metadata["attempt"]!!.jsonPrimitive.isString)
      assert(metadata["retried"]!!.jsonPrimitive.boolean)
      // Anything Logflare has no type for travels as its text.
      assert(metadata["shop"]!!.jsonPrimitive.content == "acme.myshopify.com")
      assert(metadata["shop"]!!.jsonPrimitive.isString)
    }
  }

  /**
   * A burst fills the queue faster than the interval drains it, so reaching the batch size asks for a flush of its own.
   * The interval here is a minute: only that request can have shipped the batch within the wait.
   */
  @Test
  fun `a queue that reaches the batch size is flushed without waiting for the interval`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server, maxBatchSize = 2, flushInterval = 1.minutes) { logger ->
        logger.info("first")
        logger.info("second")
      }

      assert(events.map { it["message"]!!.jsonPrimitive.content } == listOf("first", "second"))
    }
  }

  /**
   * The MDC map, the fields and the throwable are the three optional parts of an event, and
   * `ILoggingEvent` is an interface: the appender is handed whatever produced the event. A line that
   * carries none of them must still ship, because an appender that throws loses the line silently.
   */
  @Test
  fun `ships an event that carries no MDC, no fields and no throwable`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        val event = object : LoggingEvent("", logger, Level.INFO, "nothing attached", null, null) {
          // Logback's own event answers an empty map here; another source of events need not.
          override fun getMDCPropertyMap(): Map<String, String>? = null
        }
        logger.callAppenders(event)
      }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert(events.single()["message"]!!.jsonPrimitive.content == "nothing attached")
      assert(metadata["level"]!!.jsonPrimitive.content == "INFO")
      // No throwable, so no stack trace is invented for one.
      assert("error" !in metadata)
    }
  }

  /**
   * The shipper's own complaints cannot go through the logger it ships for, and Logback's status
   * manager has no listener, so they go to standard error, which the container captures.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a refused batch is reported on standard error`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"
      server.logsStatusCode = 400
      val captured = ByteArrayOutputStream()
      val originalErr = System.err
      System.setErr(PrintStream(captured, true))
      try {
        shippedBy(server) { logger -> logger.info("about to be refused") }
        assert(awaitUntilBlocking { "[logflare] Logflare flush failed: 400" in captured.toString() })
      } finally {
        System.setErr(originalErr)
      }
    }
  }

  /**
   * Without a source there is nothing to post under: the appender stays stopped, starts no handshake, and an event
   * handed to it goes nowhere rather than into a queue that grows behind a shipper that will never have a token.
   */
  @Test
  fun `refuses to start without a source name and ships nothing logged to it`() {
    FakeLogflareServer().use { server ->
      val appender = LogflareAppender().apply {
        apiKey = LogflareApiKey("test-logflare-key")
        endpoint = server.endpoint
        context = loggerContext
      }

      appender.start()
      appender.doAppend(LoggingEvent("", testLogger(), Level.INFO, "logged to a stopped appender", null, null))

      assert(!appender.isStarted)
      assert(server.awaitNoBatch())
      assert(server.receivedEvents.isEmpty())
      assert(server.receivedSourcesAuthorizations.isEmpty())
    }
  }

  @Test
  fun `refuses to start without an api key`() {
    FakeLogflareServer().use { server ->
      val appender = LogflareAppender().apply {
        sourceName = "dss-test"
        endpoint = server.endpoint
        context = loggerContext
      }

      appender.start()

      assert(!appender.isStarted)
    }
  }

  /** A logger of this class's own, so no other test sees what these ship. */
  private fun testLogger(): LogbackLogger = loggerContext.getLogger("dropnext.dss.test.logflare")
}
