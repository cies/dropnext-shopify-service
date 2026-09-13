package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.RequestValidation
import kotlin.test.Test


class ValidateSyncShipmentsRequestTest {

  @Test
  fun `rejects non-positive shopify_order_id`() {
    val result = validateSyncShipmentsRequest(request(shopifyOrderId = 0L, shipments = listOf(validShipment())))
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects empty shipments list`() {
    val result = validateSyncShipmentsRequest(request(shipments = emptyList()))
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects blank tracking number on shipment`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(trackingNumber = "  "))))
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `accepts shipment without carrier`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(carrier = null))))
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `rejects shipment with empty line_items`() {
    val result = validateSyncShipmentsRequest(request(shipments = listOf(validShipment().copy(lineItems = emptyList()))))
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects zero quantity line item`() {
    val shipment = validShipment().copy(lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 0)))
    val result = validateSyncShipmentsRequest(request(shipments = listOf(shipment)))
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects duplicate tracking numbers`() {
    val result = validateSyncShipmentsRequest(
      request(shipments = listOf(validShipment().copy(trackingNumber = "1Z999"), validShipment().copy(trackingNumber = "1Z999"))),
    )
    assert(result is RequestValidation.Invalid)
    assert("duplicate tracking_number" in (result as RequestValidation.Invalid).message)
  }

  @Test
  fun `accepts distinct tracking numbers across shipments`() {
    val result = validateSyncShipmentsRequest(
      request(shipments = listOf(validShipment().copy(trackingNumber = "1Z999"), validShipment().copy(trackingNumber = "1Z888"))),
    )
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `accepts valid sync request`() {
    assert(validateSyncShipmentsRequest(request(shipments = listOf(validShipment()))) is RequestValidation.Valid)
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
    assert(result is RequestValidation.Invalid)
    val messages = (result as RequestValidation.Invalid).messages
    assert(messages.any { "shopify_order_id" in it })
    assert(messages.any { "tracking_number" in it })
    assert(messages.any { "quantity must be positive" in it })
    assert(messages.size >= 3)
  }

  private fun request(shopifyOrderId: Long = 1001L, shipments: List<Shipment>): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(shopifySubdomain = "acme", shopifyOrderId = shopifyOrderId, shipments = shipments)

  private fun validShipment(): Shipment =
    Shipment(
      trackingNumber = "1Z999AA10123456784",
      carrier = "UPS",
      trackingUrl = "https://www.ups.com/track",
      lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
    )
}
