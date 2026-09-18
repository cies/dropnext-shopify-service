package dropnext.dss.workflow

import dropnext.dss.lib.ktor.SHOPIFY_REQUEST_TIMEOUT_MILLIS
import dropnext.dss.lib.shopify.graphql.ShopifyError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource


/**
 * How long a shipments sync may take, from the moment its request arrives.
 *
 * The monolith waits 30 seconds for the answer; a run still going after that is retried by the monolith while it goes
 * on, and two runs can then each create a fulfillment for the same tracking number.
 */
val SHIPMENT_SYNC_BUDGET: Duration = 25.seconds

/**
 * The deadline of one shipments sync. A Shopify call is started only while it can finish before the deadline, since a
 * call may take [SHOPIFY_REQUEST_TIMEOUT_MILLIS]; the run stops before it instead, and its report says what was done.
 */
class ShipmentSyncBudget(
  private val deadline: TimeMark?,
  private val budget: Duration,
) {
  private val lastCallStart = deadline?.minus(SHOPIFY_REQUEST_TIMEOUT_MILLIS.milliseconds)

  fun allowsAnotherCall(): Boolean = lastCallStart?.hasNotPassedNow() ?: true

  fun timedOut(): ShopifyError.TimedOut = ShopifyError.TimedOut("syncing the shipments", budget)

  companion object {
    fun startedAt(start: TimeMark, budget: Duration = SHIPMENT_SYNC_BUDGET): ShipmentSyncBudget =
      ShipmentSyncBudget(start + budget, budget)

    fun startingNow(budget: Duration = SHIPMENT_SYNC_BUDGET): ShipmentSyncBudget =
      startedAt(TimeSource.Monotonic.markNow(), budget)

    /** For a caller that bounds the time itself, such as a test of one step. */
    fun unlimited(): ShipmentSyncBudget = ShipmentSyncBudget(deadline = null, budget = Duration.INFINITE)
  }
}
