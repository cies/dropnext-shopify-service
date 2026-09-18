package dropnext.dss.workflow

import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order


/**
 * What the cancels of a run leave for the plan of its creates.
 *
 * [orderBeforeCancels] decides two things.
 * Whom Shopify emails: a number the order carried before the run was mailed about already.
 * And which shipments are matched first: the ones that order has carried under their number, whatever became of that
 * fulfillment, because Shopify has shown their package to the customer and a shipment that never fit must not take
 * their room.
 *
 * [canceledFulfillmentIds] are the fulfillments this run canceled, or found gone, which count as canceled whatever a
 * reload shows.
 * [blockingFulfillments] are the stale fulfillments still live, kept because their shipment would not fit or because
 * Shopify refused their cancel: nothing is created under their numbers.
 * [orderChanged] says whether Shopify canceled anything, which is when the order has to be loaded again.
 */
data class CancelAftermath(
  val orderBeforeCancels: Order,
  val canceledFulfillmentIds: Set<String>,
  val blockingFulfillments: List<Fulfillment>,
  val orderChanged: Boolean,
) {
  companion object {
    /** For a run whose order carried no retired number, and for the create plan of a test that cancels nothing. */
    fun nothingCanceled(order: Order): CancelAftermath =
      CancelAftermath(order, emptySet(), emptyList(), orderChanged = false)
  }
}
