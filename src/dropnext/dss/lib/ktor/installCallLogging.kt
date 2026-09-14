package dropnext.dss.lib.ktor

import dropnext.dss.lib.slf4j.METHOD_MDC_KEY
import dropnext.dss.lib.slf4j.ROUTE_MDC_KEY
import dropnext.dss.lib.slf4j.TOPIC_MDC_KEY
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.WEBHOOK_ID_MDC_KEY
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level


/**
 * Always installed, because it is what puts the request's context in the MDC: the trace id, and what the request line
 * and headers say before any handler runs. With an MDC entry configured, Ktor wraps the rest of the pipeline in
 * `MDCContext`, so the values survive every suspension point and reach the `StatusPages` handler too. The MDC is
 * thread-local and a suspended handler resumes on whatever thread is free; a plain `MDC.put` would only cover the log
 * lines before the first outbound call. A value a handler computes (the shop) goes in through `withMdcEntries` instead.
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
    // The path and never the URI, for the reason the format below gives.
    mdc(ROUTE_MDC_KEY) { call -> call.request.path() }
    mdc(METHOD_MDC_KEY) { call -> call.request.httpMethod.value }
    // Null, and so absent, on every request but a webhook delivery. The raw header rather than the parsed topic: an
    // unknown topic is exactly the one an operator searches for by the string Shopify sent. Both are anonymous input
    // until the handler has checked the HMAC, and land on every line of the call, so they are capped like an adopted
    // trace id; a real topic or webhook id is far shorter.
    mdc(TOPIC_MDC_KEY) { call -> call.request.headers["X-Shopify-Topic"]?.take(MAX_TRACE_ID_LENGTH) }
    mdc(WEBHOOK_ID_MDC_KEY) { call -> call.request.headers["X-Shopify-Webhook-Id"]?.take(MAX_TRACE_ID_LENGTH) }
    filter { enabled }
    format { call ->
      val status = call.response.status()?.value?.toString() ?: "no-status"
      "${call.request.httpMethod.value} ${call.request.path()} -> $status"
    }
  }
}
