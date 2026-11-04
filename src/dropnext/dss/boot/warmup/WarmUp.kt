package dropnext.dss.boot.warmup

import kotlin.time.Duration
import kotlinx.coroutines.Deferred


/**
 * What runs between the application starting and the task taking traffic, and how long it may.
 * `/health` answers `503` until [run] returns, throws or [budget] ends, whichever comes first;
 * a budget of zero opens the gate at once, whatever [run] is.
 *
 * [run] is handed the deferred that completes once the server has bound its socket (Ktor's `ServerReady`),
 * so a warm-up that sends the service requests over loopback knows when it can, and the trace id of this start.
 * It answers whether the task may take traffic. A [WarmUpVerdict.RefuseTraffic] keeps the gate closed for good and
 * hands its reason to [refuseTraffic]: production exits the process ([exitRefusingTraffic]), so ECS replaces the task
 * and a rolling deploy keeps the old one serving. The default does nothing more, which is what a test wants.
 *
 * [afterReady] is for what nothing waits on, such as a report: it starts once the gate is open, on the same trace id,
 * so it can neither use up [budget] nor hold the gate. It never runs for a task that refuses traffic.
 */
class WarmUp(
  val budget: Duration,
  val refuseTraffic: (reason: String) -> Unit = {},
  val afterReady: suspend (traceId: String) -> Unit = {},
  val run: suspend (serverBound: Deferred<Unit>, traceId: String) -> WarmUpVerdict,
) {
  companion object {
    /** Ready at once: what the tests install, so no test sees a warm-up's outbound calls. */
    val NONE = WarmUp(Duration.ZERO) { _, _ -> WarmUpVerdict.TakeTraffic }
  }
}

sealed interface WarmUpVerdict {
  data object TakeTraffic : WarmUpVerdict

  /** [reason] names the secret and the side that refused it, never its value. */
  data class RefuseTraffic(val reason: String) : WarmUpVerdict
}
