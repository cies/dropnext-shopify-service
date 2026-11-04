package dropnext.dss.boot.warmup

import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ServerReady
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * Two Ktor events, because they fire at different moments:
 *
 * - `ApplicationStarted` is raised by the embedded server right after the modules are loaded, *before* the engine binds its socket.
 * - `ServerReady` by the CIO engine **after** the bind.
 *
 * The outbound half of a warm-up needs only the first; a request to ourselves needs the second,
 * which the test engine never raises.
 *
 * Whatever the warm-up does, [readiness] is marked in a `finally`:
 * when it returns, when its budget ends, when it throws, and when the application is stopped underneath it.
 * The one exception is a warm-up that answers [WarmUpVerdict.RefuseTraffic]: a check proved a shared secret wrong,
 * so every call the task would make fails, and the gate stays closed while [WarmUp.refuseTraffic] ends the process.
 *
 * Once the gate is open, [WarmUp.afterReady] starts on a coroutine of its own, on the trace id minted here for both.
 */
fun Application.startWarmUp(readiness: Readiness, warmUp: WarmUp) {
  val serverBound = CompletableDeferred<Unit>()
  val traceId = newWarmUpTraceId()
  monitor.subscribe(ServerReady) { serverBound.complete(Unit) }
  monitor.subscribe(ApplicationStarted) { started ->
    // Undispatched, so a warm-up with nothing to do has opened the gate before the first request arrives, and a real
    // one is under way up to its first suspension when the event handler returns.
    started.launch(start = CoroutineStart.UNDISPATCHED) {
      var verdict: WarmUpVerdict = WarmUpVerdict.TakeTraffic
      try {
        verdict = withTimeoutOrNull(warmUp.budget) { warmUp.run(serverBound, traceId) } ?: WarmUpVerdict.TakeTraffic
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        log.error(e) { "Warm-up threw; taking traffic anyway" }
      } finally {
        when (val decided = verdict) {
          is WarmUpVerdict.RefuseTraffic -> {
            log.error { "Warm-up refuses traffic: ${decided.reason}" }
            warmUp.refuseTraffic(decided.reason)
          }
          WarmUpVerdict.TakeTraffic -> {
            readiness.markReady()
            // In the application's scope, so a stop cancels it; launched on a stopping application, it never starts.
            started.launch { runAfterReady(warmUp, traceId) }
          }
        }
      }
    }
  }
}

/** What runs after readiness only reports, so a failure of its own is a `warn`, never a reason to touch the gate. */
private suspend fun runAfterReady(warmUp: WarmUp, traceId: String) = withMdcEntries(TRACE_ID_MDC_KEY to traceId) {
  try {
    warmUp.afterReady(traceId)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    log.warn(e) { "Warm-up step after readiness threw" }
  }
}

/** Sixteen hex characters, the shape `CallId` mints, prefixed so the id says what it belongs to. */
internal fun newWarmUpTraceId(): String = "warmup-" + Random.nextBytes(8).joinToString("") { "%02x".format(it) }
