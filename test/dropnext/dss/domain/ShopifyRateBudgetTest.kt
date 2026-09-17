package dropnext.dss.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


class ShopifyRateBudgetTest {

  private val standard = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 1000.0, restoreRate = 50.0)

  @Test
  fun `a full bucket is not below half`() {
    assert(!standard.isBelow(0.5))
  }

  @Test
  fun `a bucket under the fraction is below it`() {
    assert(standard.copy(currentlyAvailable = 499.0).isBelow(0.5))
  }

  /** Exactly at the line is enough to keep going; the walk only waits once it has actually dipped under it. */
  @Test
  fun `a bucket at the fraction is not below it`() {
    assert(!standard.copy(currentlyAvailable = 500.0).isBelow(0.5))
  }

  @Test
  fun `the refill time is the missing points over the restore rate`() {
    // 500 - 200 = 300 points missing, at 50 a second.
    assert(standard.copy(currentlyAvailable = 200.0).refillTo(0.5) == 6.seconds)
  }

  @Test
  fun `a bucket already above the fraction needs no refill`() {
    assert(standard.copy(currentlyAvailable = 800.0).refillTo(0.5) == Duration.ZERO)
  }

  /** A number we did not compute must never turn into an unbounded wait. */
  @Test
  fun `a non-positive restore rate or size never produces a wait`() {
    assert(standard.copy(currentlyAvailable = 0.0, restoreRate = 0.0).refillTo(0.5) == Duration.ZERO)
    assert(standard.copy(maximumAvailable = 0.0, currentlyAvailable = 0.0).refillTo(0.5) == Duration.ZERO)
    assert(!standard.copy(maximumAvailable = 0.0, currentlyAvailable = 0.0).isBelow(0.5))
  }

  @Test
  fun `the refill to hold a number of points is what is missing over the restore rate`() {
    // 800 - 600 = 200 points missing, at 50 a second.
    assert(standard.copy(currentlyAvailable = 600.0).refillToHold(800.0) == 4.seconds)
    assert(standard.copy(currentlyAvailable = 900.0).refillToHold(800.0) == Duration.ZERO)
  }

  /** A query that costs more than the bucket can hold can only wait for a full one. */
  @Test
  fun `the refill to hold more than the bucket's size waits for a full bucket`() {
    assert(standard.copy(currentlyAvailable = 900.0).refillToHold(5_000.0) == 2.seconds)
  }

  @Test
  fun `the wait after a throttle is the longer of the refill to the fraction and to the requested cost`() {
    val aboveHalf = standard.copy(currentlyAvailable = 600.0)
    assert(aboveHalf.retryAfterThrottle(0.5, requestedCost = 800) == 4.seconds)
    assert(aboveHalf.retryAfterThrottle(0.5, requestedCost = null) == Duration.ZERO)

    val belowHalf = standard.copy(currentlyAvailable = 200.0)
    assert(belowHalf.retryAfterThrottle(0.5, requestedCost = 300) == 6.seconds)
  }

  @Test
  fun `a non-positive restore rate never produces a wait to hold a query either`() {
    assert(standard.copy(currentlyAvailable = 0.0, restoreRate = 0.0).retryAfterThrottle(0.5, requestedCost = 800) == Duration.ZERO)
  }
}
