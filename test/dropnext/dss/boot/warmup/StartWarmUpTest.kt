package dropnext.dss.boot.warmup

import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.awaitUntil
import dropnext.dss.testutil.helper.awaitUntilBlocking
import dropnext.dss.testutil.helper.capturingLogs
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The one thing this trigger owes the load balancer: [Readiness] is marked whatever the warm-up did.
 * Nothing else in the service marks it, so a way out of the `finally` leaves `/health` answering
 * `503` for the rest of the process and the task out of rotation until someone notices.
 */
class StartWarmUpTest {

  private fun startingWarmUp(readiness: Readiness, warmUp: WarmUp, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { startWarmUp(readiness, warmUp) }
    // `ApplicationStarted` is what launches the warm-up, and the test engine raises it on the first
    // request; a test that sends none has to start the application itself.
    startApplication()
    block()
  }

  /** Launched undispatched, so a warm-up with nothing left to do has opened the gate before the first request arrives. */
  @Test
  fun `a warm-up that returns marks the service ready`() {
    val readiness = Readiness()
    val ran = CompletableDeferred<Unit>()
    startingWarmUp(readiness, WarmUp(5.seconds) { ran.complete(Unit) }) {
      assert(ran.isCompleted)
      assert(readiness.isReady)
    }
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a warm-up that throws marks the service ready and logs what broke`() {
    val readiness = Readiness()
    val lines = capturingLogs {
      startingWarmUp(readiness, WarmUp(5.seconds) { error("the loopback client was never built") }) {
        assert(awaitUntil { readiness.isReady })
      }
    }
    assert(lines.single { "Warm-up threw; taking traffic anyway" in it }.startsWith("ERROR"))
    // The line alone would say the warm-up broke without saying at what, which is the only part worth waking up for.
    assert(lines.any { "the loopback client was never built" in it })
  }

  /** A warm-up that hangs on an upstream that never answers must not keep the task out of rotation. */
  @Test
  fun `a warm-up that outlives its budget marks the service ready when the budget ends`() {
    val readiness = Readiness()
    val entered = CompletableDeferred<Unit>()
    startingWarmUp(
      readiness,
      WarmUp(100.milliseconds) {
        entered.complete(Unit)
        awaitCancellation()
      },
    ) {
      assert(entered.isCompleted)
      assert(awaitUntil(2.seconds) { readiness.isReady })
    }
  }

  /**
   * The case no budget covers: the task is told to shut down while the warm-up is still running, so
   * the coroutine is cancelled rather than timed out. `/health` is read until the last request is
   * served, and a flag left unmarked answers `503` to all of them.
   */
  @Test
  fun `a warm-up still running when the application stops marks the service ready`() {
    val readiness = Readiness()
    val entered = CompletableDeferred<Unit>()
    startingWarmUp(
      readiness,
      WarmUp(10.minutes) {
        entered.complete(Unit)
        awaitCancellation()
      },
    ) {
      assert(entered.isCompleted)
      // The budget is minutes away, so nothing but the stop that ends this block can open the gate.
      assert(!readiness.isReady)
    }
    assert(awaitUntilBlocking { readiness.isReady })
  }

  /** What `WarmUp.NONE` is, and what every test installing it relies on: ready at once, and no fake sees a call. */
  @Test
  fun `a zero budget marks the service ready without running the warm-up`() {
    val readiness = Readiness()
    val ran = CompletableDeferred<Unit>()
    startingWarmUp(readiness, WarmUp(Duration.ZERO) { ran.complete(Unit) }) {
      assert(readiness.isReady)
      assert(!ran.isCompleted)
    }
  }
}
