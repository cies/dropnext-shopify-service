package dropnext.dss.domain.fulfillment

import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order


/**
 * The order's fulfillments that carry [trackingNumber], canceled ones left out: a canceled fulfillment keeps its
 * tracking number, and the package it named is no longer on the order.
 */
fun liveFulfillmentsWithTrackingNumber(order: Order, trackingNumber: String): List<Fulfillment> =
  fulfillmentsWithTrackingNumber(order, trackingNumber).filter { it.status != FulfillmentStatus.CANCELLED }

/**
 * The order's fulfillments that carry [trackingNumber], whatever their status: whether the order has ever carried the
 * number. Both sides are trimmed; case is kept, because carriers differ on whether it is significant.
 */
fun fulfillmentsWithTrackingNumber(order: Order, trackingNumber: String): List<Fulfillment> {
  val wanted = trackingNumber.trim()
  return order.fulfillments.filter { fulfillment -> fulfillment.trackingInfo.any { it.number?.trim() == wanted } }
}

/** The tracking numbers a fulfillment carries, trimmed the way [fulfillmentsWithTrackingNumber] compares them. */
fun Fulfillment.trackingNumbers(): List<String> = trackingInfo.mapNotNull { it.number?.trim() }
