package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.RequestValidation
import dropnext.dss.domain.toRequestValidation
import dropnext.dss.domain.validateShopifyOrderId


fun validateSyncShipmentsRequest(request: SyncShipmentsWithFulfillmentsRequest): RequestValidation {
  val errors = mutableListOf<String>()
  errors += validateShopifyOrderId(request.shopifyOrderId)
  // An empty list is valid: an order whose shipments are all gone is still synced, and answered with an empty report.
  request.shipments.forEachIndexed { index, shipment ->
    errors += validateShipment(shipment, index)
  }
  errors += validateDuplicateTrackingNumbers(request.shipments)
  // A retired number is used trimmed, and two that are equal then are one: a request whose meaning is clear is not refused.
  request.retiredTrackingNumbers.orEmpty().forEachIndexed { index, trackingNumber ->
    if (trackingNumber.isBlank()) errors += "retired_tracking_numbers[$index] must not be blank"
  }
  return errors.toRequestValidation()
}

private fun validateDuplicateTrackingNumbers(shipments: List<Shipment>): List<String> {
  val seen = mutableSetOf<String>()
  val duplicates = linkedSetOf<String>()
  shipments.forEach { shipment ->
    val tracking = shipment.trackingNumber.trim()
    if (!seen.add(tracking)) {
      duplicates.add(tracking)
    }
  }
  return if (duplicates.isEmpty()) {
    emptyList()
  } else {
    listOf("duplicate tracking_number in payload: ${duplicates.joinToString(", ")}")
  }
}

private fun validateShipment(shipment: Shipment, index: Int): List<String> {
  val errors = mutableListOf<String>()
  val prefix = "shipments[$index]"
  if (shipment.trackingNumber.isBlank()) errors += "$prefix tracking_number is required"
  if (shipment.lineItems.isEmpty()) {
    errors += "$prefix line_items must not be empty"
  } else {
    shipment.lineItems.forEach { errors += validateShipmentLineItem(it) }
  }
  return errors
}

private fun validateShipmentLineItem(line: ShipmentLineItem): List<String> =
  if (line.quantity <= 0) {
    listOf("line item quantity must be positive (variant_id=${line.productVariantId})")
  } else {
    emptyList()
  }
