package dropnext.dss.workflow

import dropnext.dss.lib.ktor.SHOPIFY_REQUEST_TIMEOUT_MILLIS
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.helper.MutableTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


class ShipmentSyncBudgetTest {

  /** A Shopify call may take ten seconds, so the last one starts ten seconds before the deadline. */
  @Test
  fun `a call is allowed while it can finish before the deadline`() {
    val clock = MutableTimeSource()
    val budget = ShipmentSyncBudget.startedAt(clock.markNow(), 25.seconds)

    clock += 14.seconds
    assert(budget.allowsAnotherCall())
    clock += 2.seconds
    assert(!budget.allowsAnotherCall())
  }

  @Test
  fun `a budget shorter than one call allows none`() {
    val budget = ShipmentSyncBudget.startedAt(MutableTimeSource().markNow(), 5.seconds)
    assert(!budget.allowsAnotherCall())
  }

  @Test
  fun `the timeout names the work and the budget`() {
    val budget = ShipmentSyncBudget.startedAt(MutableTimeSource().markNow(), 25.seconds)
    assert(budget.timedOut() == ShopifyError.TimedOut("syncing the shipments", 25.seconds))
  }

  @Test
  fun `an unlimited budget always allows another call`() {
    val budget = ShipmentSyncBudget.unlimited()
    assert(budget.allowsAnotherCall())
    assert(budget.timedOut().budget == Duration.INFINITE)
  }

  /** The budget has to hold a Shopify call after its last allowed start, or the run outlives what it promised. */
  @Test
  fun `the last call the default budget allows still ends inside it`() {
    val clock = MutableTimeSource()
    val budget = ShipmentSyncBudget.startedAt(clock.markNow())
    val oneCall = SHOPIFY_REQUEST_TIMEOUT_MILLIS.milliseconds

    clock += SHIPMENT_SYNC_BUDGET - oneCall - 1.milliseconds
    assert(budget.allowsAnotherCall())
    clock += oneCall
    assert(!budget.allowsAnotherCall())
  }
}
