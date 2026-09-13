package dropnext.dss.lib.slf4j

import org.slf4j.MDC


/** The MDC key `logback.xml` prints; `installCallLogging` fills it per request through Ktor's `callIdMdc`. */
const val TRACE_ID_MDC_KEY = "trace_id"

/** The current request's trace id, or `null` outside a request. Read through the MDC so any layer can correlate without a `call`. */
fun currentTraceId(): String? = MDC.get(TRACE_ID_MDC_KEY)
