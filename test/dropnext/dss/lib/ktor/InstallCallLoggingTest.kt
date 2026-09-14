package dropnext.dss.lib.ktor

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dropnext.dss.boot.config.DssMode
import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.dssDependencies
import dropnext.dss.dssModule
import dropnext.dss.lib.slf4j.METHOD_MDC_KEY
import dropnext.dss.lib.slf4j.ROUTE_MDC_KEY
import dropnext.dss.lib.slf4j.TOPIC_MDC_KEY
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.WEBHOOK_ID_MDC_KEY
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.logbackContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlinx.coroutines.delay
import org.junit.jupiter.api.parallel.ResourceLock


private val log = KotlinLogging.logger {}

/**
 * Two things live in this plugin. The MDC trace id is always on, and is what every other log line of a
 * request relies on. The one-line-per-request log is off in production — the monolith logs no HTTP
 * traffic either — so what these pin is the switch and the shape of the line, in particular that it
 * carries no query string: the OAuth callback's `code` and `hmac` live there, and a log line is exactly
 * the wrong place for a credential.
 */
@ResourceLock(GLOBAL_LOG_REGISTRY)
class InstallCallLoggingTest {

  /** Captures everything logged anywhere while [block] runs; call logging goes through Ktor's own logger. */
  private fun <R> captureLogLines(block: (recorded: List<ILoggingEvent>) -> R): R {
    val rootLogger = logbackContext().getLogger(LogbackLogger.ROOT_LOGGER_NAME)
    val recorder = ListAppender<ILoggingEvent>().apply { start() }
    rootLogger.addAppender(recorder)
    try {
      return block(recorder.list)
    } finally {
      rootLogger.detachAppender(recorder)
      recorder.stop()
    }
  }

  private fun eventsMentioning(recorded: List<ILoggingEvent>, text: String): List<ILoggingEvent> =
    recorded.filter { text in it.formattedMessage }

  @Test
  fun `logs one line per request with the method, the path and the status, and the trace id in the MDC`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = true)
          routing { get("/probe") { call.respondText("ok") } }
        }
        client.get("/probe") { header("X-Trace-Id", "trace-call-log") }
      }

      val event = eventsMentioning(recorded, "/probe").single()
      assert("GET" in event.formattedMessage)
      assert("200" in event.formattedMessage)
      assert(event.mdcPropertyMap[TRACE_ID_MDC_KEY] == "trace-call-log")
    }
  }

  @Test
  fun `never logs the query string, where the OAuth code and hmac travel`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = true)
          routing { get("/oauth/callback") { call.respondText("ok") } }
        }
        client.get("/oauth/callback?code=super-secret-code&hmac=deadbeef&shop=acme.myshopify.com")
      }

      val line = eventsMentioning(recorded, "/oauth/callback").single().formattedMessage
      assert("super-secret-code" !in line)
      assert("deadbeef" !in line)
      assert("code=" !in line)
    }
  }

  /** The switch mutes the line only: a handler's own log line still carries the request's trace id. */
  @Test
  fun `the trace id reaches the MDC of a handler's log line even with call logging off`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = false)
          routing {
            get("/probe") {
              log.info { "handler says hello" }
              call.respondText("ok")
            }
          }
        }
        client.get("/probe") { header("X-Trace-Id", "trace-mdc-only") }
      }

      assert(eventsMentioning(recorded, "/probe").isEmpty())
      assert(eventsMentioning(recorded, "handler says hello").single().mdcPropertyMap[TRACE_ID_MDC_KEY] == "trace-mdc-only")
    }
  }

  @Test
  fun `the application logs no call lines in PROD mode`() {
    captureLogLines { recorded ->
      val deps = dssDependencies(testConfig(mode = DssMode.PROD))
      testApplication {
        application { dssModule(deps, WarmUp.NONE) }
        client.get(Paths.health)
      }

      assert(eventsMentioning(recorded, Paths.health).isEmpty())
    }
  }

  @Test
  fun `the application logs call lines in DEV mode`() {
    captureLogLines { recorded ->
      val deps = dssDependencies(testConfig(mode = DssMode.DEV))
      testApplication {
        application { dssModule(deps, WarmUp.NONE) }
        client.get(Paths.health)
      }

      assert(eventsMentioning(recorded, Paths.health).size == 1)
    }
  }

  /** `path()` and never the URI: the OAuth callback's query string carries its `code` and `hmac`. */
  @Test
  fun `a handler's line after a suspension carries the route without its query string, and the method`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = false)
          routing {
            get("/probe") {
              delay(5)
              log.info { "handler resumed" }
              call.respondText("ok")
            }
          }
        }
        client.get("/probe?secret=1")
      }

      val mdc = eventsMentioning(recorded, "handler resumed").single().mdcPropertyMap
      assert(mdc[ROUTE_MDC_KEY] == "/probe")
      assert(mdc[METHOD_MDC_KEY] == "GET")
    }
  }

  @Test
  fun `a handler's line carries the webhook topic and id when their headers are sent, and neither when not`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = false)
          routing {
            get("/probe") {
              log.info { "handler sent topic=${call.request.headers["X-Shopify-Topic"] != null}" }
              call.respondText("ok")
            }
          }
        }
        client.get("/probe") {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Webhook-Id", "wh-1")
        }
        client.get("/probe")
      }

      val sent = eventsMentioning(recorded, "handler sent topic=true").single().mdcPropertyMap
      assert(sent[TOPIC_MDC_KEY] == "orders/create")
      assert(sent[WEBHOOK_ID_MDC_KEY] == "wh-1")
      val notSent = eventsMentioning(recorded, "handler sent topic=false").single().mdcPropertyMap
      assert(TOPIC_MDC_KEY !in notSent)
      assert(WEBHOOK_ID_MDC_KEY !in notSent)
    }
  }

  /** Both headers are anonymous input until the handler has checked the HMAC, and they land on every line of the call. */
  @Test
  fun `a webhook topic or id longer than the cap is cut to it`() {
    captureLogLines { recorded ->
      testApplication {
        application {
          installCallId()
          installCallLogging(enabled = false)
          routing {
            get("/probe") {
              log.info { "handler saw long headers" }
              call.respondText("ok")
            }
          }
        }
        client.get("/probe") {
          header("X-Shopify-Topic", "t".repeat(MAX_TRACE_ID_LENGTH + 1))
          header("X-Shopify-Webhook-Id", "w".repeat(MAX_TRACE_ID_LENGTH + 1))
        }
      }

      val mdc = eventsMentioning(recorded, "handler saw long headers").single().mdcPropertyMap
      assert(mdc[TOPIC_MDC_KEY] == "t".repeat(MAX_TRACE_ID_LENGTH))
      assert(mdc[WEBHOOK_ID_MDC_KEY] == "w".repeat(MAX_TRACE_ID_LENGTH))
    }
  }
}
