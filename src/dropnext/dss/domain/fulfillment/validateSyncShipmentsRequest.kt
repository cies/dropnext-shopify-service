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
  if (request.shipments.isEmpty()) {
    errors += "at least one shipment is required"
  } else {
    request.shipments.forEachIndexed { index, shipment ->
      errors += validateShipment(shipment, index)
    }
    errors += validateDuplicateTrackingNumbers(request.shipments)
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
