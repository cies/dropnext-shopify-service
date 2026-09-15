package dropnext.dss.domain.fulfillment

import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order


/**
 * The order's fulfillments that carry [trackingNumber], cancelled ones left out: a cancelled fulfillment keeps its
 * tracking number, and the package it named is no longer on the order. Both sides are trimmed; case is kept, because
 * carriers differ on whether it is significant.
 */
fun liveFulfillmentsWithTrackingNumber(order: Order, trackingNumber: String): List<Fulfillment> {
  val wanted = trackingNumber.trim()
  return order.fulfillments.filter { fulfillment ->
    fulfillment.status != FulfillmentStatus.CANCELLED && fulfillment.trackingInfo.any { it.number?.trim() == wanted }
  }
}
