package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.RequestValidation
import dropnext.dss.testutil.fixture.shipment
import org.junit.jupiter.api.Test


class ValidateSyncShipmentsRequestTest {

  @Test
  fun `rejects non-positive shopify_order_id`() {
    val result = validateSyncShipmentsRequest(request(shopifyOrderId = 0L, shipments = listOf(validShipment())))
    assert(result == RequestValidation.Invalid(listOf("invalid shopify_order_id: must be positive")))
  }

  @Test
  fun `rejects empty shipments list`() {
    val result = validateSyncShipmentsRequest(request(shipments = emptyList()))
    assert(result == RequestValidation.Invalid(listOf("at least one shipment is required")))
  }

  /** The index is what tells the monolith which shipment of its payload to fix. */
  @Test
  fun `rejects blank tracking number on shipment`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(trackingNumber = "  "))))
    assert(result == RequestValidation.Invalid(listOf("shipments[0] tracking_number is required")))
  }

  @Test
  fun `accepts shipment without carrier`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(carrier = null))))
    assert(result == RequestValidation.Valid)
  }

  @Test
  fun `rejects shipment with empty line_items`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(lineItems = emptyList()))))
    assert(result == RequestValidation.Invalid(listOf("shipments[0] line_items must not be empty")))
  }

  @Test
  fun `rejects zero quantity line item`() {
    val shipment = validShipment().copy(lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 0)))
    val result = validateSyncShipmentsRequest(request(shipments = listOf(shipment)))
    assert(result == RequestValidation.Invalid(listOf("line item quantity must be positive (variant_id=101)")))
  }

  @Test
  fun `rejects duplicate tracking numbers`() {
    val result = validateSyncShipmentsRequest(
      request(shipments = listOf(validShipment().copy(trackingNumber = "1Z999"), validShipment().copy(trackingNumber = "1Z999"))),
    )
    assert(result == RequestValidation.Invalid(listOf("duplicate tracking_number in payload: 1Z999")))
  }

  /** The already-fulfilled skip matches tracking numbers trimmed, so these two would name one fulfillment. */
  @Test
  fun `rejects tracking numbers that differ only in surrounding whitespace as duplicates`() {
    val result = validateSyncShipmentsRequest(
      request(shipments = listOf(validShipment().copy(trackingNumber = "1Z999"), validShipment().copy(trackingNumber = " 1Z999 "))),
    )
    assert(result == RequestValidation.Invalid(listOf("duplicate tracking_number in payload: 1Z999")))
  }

  @Test
  fun `accepts distinct tracking numbers across shipments`() {
    val result = validateSyncShipmentsRequest(
      request(shipments = listOf(validShipment().copy(trackingNumber = "1Z999"), validShipment().copy(trackingNumber = "1Z888"))),
    )
    assert(result == RequestValidation.Valid)
  }

  @Test
  fun `accepts valid sync request`() {
    assert(validateSyncShipmentsRequest(request(shipments = listOf(validShipment()))) == RequestValidation.Valid)
  }

  @Test
  fun `accumulates multiple errors instead of short-circuiting on the first`() {
    val result = validateSyncShipmentsRequest(
      request(
        shopifyOrderId = 0L,
        shipments = listOf(
          validShipment().copy(trackingNumber = "  "),
          validShipment().copy(lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 0))),
        ),
      ),
    )
    val expected = listOf(
      "invalid shopify_order_id: must be positive",
      "shipments[0] tracking_number is required",
      "line item quantity must be positive (variant_id=101)",
    )
    assert(result == RequestValidation.Invalid(expected))
  }

  private fun request(shopifyOrderId: Long = 1001L, shipments: List<Shipment>): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(shopifySubdomain = "acme", shopifyOrderId = shopifyOrderId, shipments = shipments)

  private fun validShipment(): Shipment =
    shipment(tracking = "1Z999AA10123456784", trackingUrl = "https://www.ups.com/track")
}
