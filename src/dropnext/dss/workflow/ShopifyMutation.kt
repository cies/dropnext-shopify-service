package dropnext.dss.workflow

import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.FulfillmentLine


/** One Shopify write the shipments sync has decided on; `calculate` plans them, `effect` runs them. */
sealed interface ShopifyMutation {

  /** [trackingNumber] is the retired number the cancel is for, trimmed: the report names the cancel by it. */
  data class FulfillmentCancel(
    val fulfillmentId: ShopifyFulfillmentId,
    val trackingNumber: String,
  ) : ShopifyMutation

  /**
   * [carrier] and [trackingUrl] are forwarded from the shipment payload when present.
   *
   * [notifyCustomer] has Shopify send its shipping email: `true` when the order has never carried [trackingNumber], in
   * any fulfillment status, so the customer hears of every new package once and of a re-created one not again.
   */
  data class FulfillmentCreate(
    val lineItems: List<FulfillmentLine>,
    val trackingNumber: String,
    val carrier: String?,
    val trackingUrl: String?,
    val notifyCustomer: Boolean,
  ) : ShopifyMutation
}
