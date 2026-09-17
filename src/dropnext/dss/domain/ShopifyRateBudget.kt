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
  fun refillTo(fraction: Double): Duration {
    if (restoreRate <= 0.0 || maximumAvailable <= 0.0) return Duration.ZERO
    val missing = maximumAvailable * fraction - currentlyAvailable
    if (missing <= 0.0) return Duration.ZERO
    return (missing / restoreRate).seconds
  }
}
