package dropnext.dss.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * What Shopify says is left of the shop's Graphql point bucket, as it reports it on every response.
 *
 * The bucket is per shop and shared with everything this app does for that shop, the live webhook traffic included,
 * so anything that reads a whole shop has to pace itself against the real budget rather than a delay someone picked:
 * emptying it throttles the order webhooks that pay the bills. The numbers are Shopify's own
 * (`extensions.cost.throttleStatus`), which is why the rate never has to be guessed per plan.
 */
data class ShopifyRateBudget(
  val maximumAvailable: Double,
  val currentlyAvailable: Double,
  val restoreRate: Double,
) {

  /** Whether the bucket has fallen under [fraction] of its size: the point at which a loop should wait, not ask again. */
  fun isBelow(fraction: Double): Boolean =
    maximumAvailable > 0.0 && currentlyAvailable < maximumAvailable * fraction

  /**
   * How long Shopify needs to refill the bucket to [fraction] of its size, at the rate it reports. Zero when it is
   * already there, and zero for a nonsensical rate, so a wait can never be unbounded on a number we did not compute.
   */
  fun refillTo(fraction: Double): Duration = refillToHold(maximumAvailable * fraction)

  /**
   * How long Shopify needs before the bucket holds [points], at the rate it reports; zero when it already does. A query
   * that costs more than the whole bucket can only wait for a full one.
   */
  fun refillToHold(points: Double): Duration {
    if (restoreRate <= 0.0 || maximumAvailable <= 0.0) return Duration.ZERO
    val missing = minOf(points, maximumAvailable) - currentlyAvailable
    if (missing <= 0.0) return Duration.ZERO
    return (missing / restoreRate).seconds
  }

  /**
   * How long to wait before asking again for a query Shopify refused for want of points: until the bucket is back at
   * [fraction] of its size and also holds the query's [requestedCost]. Shopify runs a query only when the bucket holds
   * what it requests, so a query costing more than [fraction] leaves would be refused again after the first refill alone.
   */
  fun retryAfterThrottle(fraction: Double, requestedCost: Int?): Duration {
    val forQuery = requestedCost?.let { refillToHold(it.toDouble()) } ?: Duration.ZERO
    return maxOf(refillTo(fraction), forQuery)
  }
}
