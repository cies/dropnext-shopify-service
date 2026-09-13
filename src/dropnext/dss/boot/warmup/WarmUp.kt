package dropnext.dss.boot.warmup

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlinx.coroutines.Deferred


/**
 * What runs between the application starting and the task taking traffic, and how long it may.
 * `/health` answers `503` until [run] returns, throws or [budget] ends, whichever comes first;
 * a budget of zero opens the gate at once, whatever [run] is.
 *
 * [run] is handed the deferred that completes once the server has bound its socket (Ktor's `ServerReady`),
 * so a warm-up that sends the service requests over loopback knows when it can.
 */
class WarmUp(
  val budget: Duration,
  val run: suspend (serverBound: Deferred<Unit>) -> Unit,
) {
  companion object {
    /** Ready at once: what the tests install, so no test sees a warm-up's outbound calls. */
    val NONE = WarmUp(Duration.ZERO) {}
  }
}

