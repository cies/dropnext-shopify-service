package dropnext.dss.lib.logflare

import ch.qos.logback.classic.Logger as LogbackLogger
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.testutil.fake.FakeLogflareServer
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.logbackContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.MDC


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
    emit: (LogbackLogger) -> Unit,
  ): List<JsonObject> {
    val appender = LogflareAppender().apply {
      this.service = service
      this.version = version
      sourceName = "dss-test"
      apiKey = LogflareApiKey("test-logflare-key")
      endpoint = server.endpoint
      flushInterval = 50.milliseconds
      context = loggerContext
    }
    val logger = loggerContext.getLogger("dropnext.dss.test.logflare")
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
        assert(awaitUntil { "[logflare] Logflare flush failed: 400" in captured.toString() })
      } finally {
        System.setErr(originalErr)
      }
    }
  }

  /** The report lands on the flush thread a moment after the fake answered, so the assertion polls briefly. */
  private fun awaitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return true
      Thread.sleep(20)
    }
    return condition()
  }

  @Test
  fun `refuses to start without a source name or an api key, and drops what is logged to it`() {
    FakeLogflareServer().use { server ->
      val appender = LogflareAppender().apply {
        apiKey = LogflareApiKey("test-logflare-key") // no sourceName
        endpoint = server.endpoint
        context = loggerContext
      }

      appender.start()

      // Not started, so `append` is a no-op rather than a queue that grows behind a shipper that
      // will never have a token to post under.
      assert(!appender.isStarted)
      assert(server.awaitNoBatch())
      assert(server.receivedEvents.isEmpty())
    }
  }
}
