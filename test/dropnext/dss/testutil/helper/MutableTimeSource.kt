package dropnext.dss.testutil.helper

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource


/**
 * A [TimeSource] the test moves by hand, for the elapsed time a delivery report prints. Against the real clock a test
 * can only assert that `took_ms=` is there at all, which passes whatever the number is — including a zero the handler
 * never measured.
 *
 * Written out rather than taken from `kotlin.time.TestTimeSource`, which is an opt-in experimental API: with
 * warnings-as-errors it would need a compiler flag for the whole test source set.
 */
class MutableTimeSource : TimeSource {
  private var elapsed: Duration = Duration.ZERO

  /** Moves every outstanding mark forward by [step], as if that much had passed. */
  operator fun plusAssign(step: Duration) {
    elapsed += step
  }

  override fun markNow(): TimeMark = Mark(elapsed)

  private inner class Mark(private val markedAt: Duration) : TimeMark {
    override fun elapsedNow(): Duration = elapsed - markedAt
  }
}
