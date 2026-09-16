package dropnext.dss.lib.ktor

import dropnext.dss.lib.slf4j.currentTraceId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test


/**
 * The handler under test echoes the MDC value, so every test also proves the id is readable from the log
 * context. Call logging is installed alongside because that is what copies the id into the MDC; the line
 * itself stays off, as in production.
 */
class InstallCallIdTest {

  private fun echoApp(handlerDelay: Long = 0, block: suspend (get: suspend (String?, String?) -> Pair<String?, String>) -> Unit) =
    testApplication {
      application {
        installCallId()
        installCallLogging(enabled = false)
        routing {
          get("/echo") {
            if (handlerDelay > 0) delay(handlerDelay)
            call.respondText(currentTraceId().orEmpty())
          }
        }
      }
      block { requestId, traceId ->
        val response = client.get("/echo") {
          requestId?.let { header("X-Request-Id", it) }
          traceId?.let { header("X-Trace-Id", it) }
        }
        response.headers["X-Trace-Id"] to response.bodyAsText()
      }
    }

  @Test
  fun `echoes incoming X-Trace-Id back on the response`() = echoApp { get ->
    val (header, body) = get(null, "trace-abc-123")
    assert(header == "trace-abc-123")
    assert(body == "trace-abc-123")
  }

  @Test
  fun `X-Request-Id takes precedence over X-Trace-Id`() = echoApp { get ->
    val (header, body) = get("req-id-wins", "trace-loses")
    assert(body == "req-id-wins")
    assert(header == "req-id-wins")
  }

  /** The plugin's default dictionary would drop this one and mint a replacement; an id the monolith chose must survive. */
  @Test
  fun `an id with uppercase letters and punctuation is kept as is`() = echoApp { get ->
    val (header, body) = get(null, "Monolith:Trace/42")
    assert(header == "Monolith:Trace/42")
    assert(body == "Monolith:Trace/42")
  }

  @Test
  fun `generates a 16-char hex id when no header is provided`() = echoApp { get ->
    val (header, id) = get(null, null)
    assert(id.length == 16)
    assert(id.all { it in '0'..'9' || it in 'a'..'f' })
    assert(header == id)
  }

  @Test
  fun `blank trace header is treated as missing`() = echoApp { get ->
    val (_, id) = get(null, "   ")
    assert(id.length == 16)
    assert(id != "   ")
  }

  // ---------- the header is anonymous input: what is adopted lands in every log line, the response and the monolith's wire ----------

  @Test
  fun `an id longer than 64 characters is replaced by a generated one`() = echoApp { get ->
    val (header, id) = get(null, "x".repeat(MAX_TRACE_ID_LENGTH + 1))
    assert(id.length == 16)
    assert(header == id)
  }

  @Test
  fun `an id of exactly 64 characters is kept`() = echoApp { get ->
    val longest = "x".repeat(MAX_TRACE_ID_LENGTH)
    val (header, id) = get(null, longest)
    assert(id == longest)
    assert(header == longest)
  }

  @Test
  fun `an id with a space or a non-ASCII letter is replaced by a generated one`() = echoApp { get ->
    val (_, spaced) = get(null, "trace 42")
    assert(spaced.length == 16)
    val (_, accented) = get(null, "tracé-42")
    assert(accented.length == 16)
  }

  /** A failed check moves on to the next provider, so a junk `X-Request-Id` does not cost the caller its `X-Trace-Id`. */
  @Test
  fun `an unacceptable X-Request-Id falls through to X-Trace-Id`() = echoApp { get ->
    val (header, id) = get("x".repeat(MAX_TRACE_ID_LENGTH + 1), "trace-ok")
    assert(id == "trace-ok")
    assert(header == "trace-ok")
  }

  /**
   * The MDC is thread-local and a suspended handler resumes on any thread: without `MDCContext` the
   * id read after the suspension is empty or — the failure worth fearing — another request's.
   *
   * Concurrent on purpose, and measured against an implementation with no `MDCContext`: one request
   * at a time came back correct in 17 runs out of 20 — a guard that lets a regression read as a flaky
   * test — while this shape, 32 at once, failed 31 of the 32.
   */
  @Test
  fun `every concurrent request keeps its own trace id across a suspension`() = echoApp(handlerDelay = 5) { get ->
    val outcomes = coroutineScope {
      (1..32)
        .map { index ->
          async(Dispatchers.IO) {
            val sent = "trace-%03d".format(index)
            sent to get(null, sent).second
          }
        }
        .awaitAll()
    }

    val crossed = outcomes.filter { (sent, echoed) -> sent != echoed }
    assert(crossed.isEmpty())
  }
}
