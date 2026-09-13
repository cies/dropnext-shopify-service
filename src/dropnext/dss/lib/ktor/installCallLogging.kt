package dropnext.dss.lib.ktor

import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level


/**
 * Always installed, because it is what puts the trace id in the MDC: with an MDC entry configured, Ktor
 * wraps the rest of the pipeline in `MDCContext`, so the id survives every suspension point and reaches the
 * `StatusPages` handler too. The MDC is thread-local and a suspended handler resumes on whatever thread is
 * free; a plain `MDC.put` would only cover the log lines before the first outbound call.
 *
 * The one-line-per-request log is a separate matter, on in `DSS_MODE=DEV` only (the [enabled] filter). The
 * monolith draws the same line with its `MONOLITH_MODE`: its request log and query log exist in `DEV` and not
 * in `PROD`, for the same reason — useful while working on a webhook, noise in production.
 *
 * The format is spelled out rather than left to the plugin's default, which logs the full URI: the OAuth
 * callback carries `code` and `hmac` in its query string, and those are credentials. Method, path and
 * status are what a developer needs; the trace id is on the line through the MDC.
 */
fun Application.installCallLogging(enabled: Boolean) {
  install(CallLogging) {
    level = Level.INFO
    callIdMdc(TRACE_ID_MDC_KEY)
    filter { enabled }
    format { call ->
      val status = call.response.status()?.value?.toString() ?: "no-status"
      "${call.request.httpMethod.value} ${call.request.path()} -> $status"
    }
  }
}
