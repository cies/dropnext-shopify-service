package dropnext.dss.boot.warmup

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ServerReady
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
 */
fun Application.startWarmUp(readiness: Readiness, warmUp: WarmUp) {
  val serverBound = CompletableDeferred<Unit>()
  monitor.subscribe(ServerReady) { serverBound.complete(Unit) }
  monitor.subscribe(ApplicationStarted) { started ->
    // Undispatched, so a warm-up with nothing to do has opened the gate before the first request arrives, and a real
    // one is under way up to its first suspension when the event handler returns.
    started.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
          withTimeoutOrNull(warmUp.budget) { warmUp.run(serverBound) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        log.error(e) { "Warm-up threw; taking traffic anyway" }
      } finally {
        readiness.markReady()
      }
    }
  }
}
