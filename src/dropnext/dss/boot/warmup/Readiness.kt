package dropnext.dss.boot.warmup

import java.util.concurrent.atomic.AtomicBoolean


/**
 * Whether the service wants traffic yet. `/health` answers `503` until [markReady] is called, which keeps a task that
 * is still warming up out of the load balancer's rotation while the task it replaces keeps serving. The flag only ever
 * goes from not ready to ready: a task that has taken traffic once has nothing left to warm.
 */
class Readiness {
  private val ready = AtomicBoolean(false)

  val isReady: Boolean get() = ready.get()

  fun markReady() {
    ready.set(true)
  }
}
