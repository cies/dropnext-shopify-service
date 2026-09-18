package dropnext.dss.mapper

import dropnext.dss.contract.FulfillmentCancellationEntry
import dropnext.dss.contract.FulfillmentCancellationOutcome
import dropnext.dss.contract.ShipmentSkipReason
import dropnext.dss.contract.ShipmentSyncEntry
import dropnext.dss.contract.ShipmentSyncOutcome
import dropnext.dss.domain.FulfillmentCancellation
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSync
import dropnext.dss.domain.ShipmentSyncReport
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.fulfillment.SkipReason
import org.junit.jupiter.api.Test


class ToSyncShipmentsResponseTest {

  @Test
  fun `every shipment outcome maps onto its contract spelling with the id or the reason it carries`() {
    val report = ShipmentSyncReport(
      shipments = listOf(
        ShipmentSync("A", ShipmentSyncStatus.Created(ShopifyFulfillmentId(4001L))),
        ShipmentSync("B", ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(3990L))),
        ShipmentSync("C", ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM)),
        ShipmentSync("D", ShipmentSyncStatus.Skipped(SkipReason.NOT_ON_ORDER)),
        ShipmentSync("E", ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(3985L))),
        ShipmentSync("F", ShipmentSyncStatus.Refused(listOf("tracking number invalid"))),
        ShipmentSync("G", ShipmentSyncStatus.Failed),
        ShipmentSync("H", ShipmentSyncStatus.NotAttempted),
      ),
    )
    val expected = listOf(
      ShipmentSyncEntry("A", ShipmentSyncOutcome.CREATED, fulfillmentId = 4001L),
      ShipmentSyncEntry("B", ShipmentSyncOutcome.ALREADY_FULFILLED, fulfillmentId = 3990L),
      ShipmentSyncEntry("C", ShipmentSyncOutcome.SKIPPED, skipReason = ShipmentSkipReason.NO_ROOM),
      ShipmentSyncEntry("D", ShipmentSyncOutcome.SKIPPED, skipReason = ShipmentSkipReason.NOT_ON_ORDER),
      ShipmentSyncEntry("E", ShipmentSyncOutcome.BLOCKED, fulfillmentId = 3985L),
      ShipmentSyncEntry("F", ShipmentSyncOutcome.REFUSED),
      ShipmentSyncEntry("G", ShipmentSyncOutcome.FAILED),
      ShipmentSyncEntry("H", ShipmentSyncOutcome.NOT_ATTEMPTED),
    )
    assert(report.toSyncShipmentsResponse().shipments == expected)
  }

  @Test
  fun `an id Shopify's gid did not carry is left out`() {
    val report = ShipmentSyncReport(shipments = listOf(ShipmentSync("A", ShipmentSyncStatus.AlreadyFulfilled(null))))
    val expected = listOf(ShipmentSyncEntry("A", ShipmentSyncOutcome.ALREADY_FULFILLED, fulfillmentId = null))
    assert(report.toSyncShipmentsResponse().shipments == expected)
  }

  @Test
  fun `every cancellation outcome maps onto its contract spelling`() {
    val report = ShipmentSyncReport(
      shipments = emptyList(),
      cancellations = listOf(
        FulfillmentCancellation("A", ShopifyFulfillmentId(1L), FulfillmentCancellationStatus.Canceled),
        FulfillmentCancellation("B", ShopifyFulfillmentId(2L), FulfillmentCancellationStatus.Refused(listOf("cannot cancel"))),
        FulfillmentCancellation("C", ShopifyFulfillmentId(3L), FulfillmentCancellationStatus.Failed),
        FulfillmentCancellation("D", ShopifyFulfillmentId(4L), FulfillmentCancellationStatus.NotAttempted),
      ),
    )
    val expected = listOf(
      FulfillmentCancellationEntry("A", 1L, FulfillmentCancellationOutcome.CANCELED),
      FulfillmentCancellationEntry("B", 2L, FulfillmentCancellationOutcome.REFUSED),
      FulfillmentCancellationEntry("C", 3L, FulfillmentCancellationOutcome.FAILED),
      FulfillmentCancellationEntry("D", 4L, FulfillmentCancellationOutcome.NOT_ATTEMPTED),
    )
    assert(report.toSyncShipmentsResponse().cancellations == expected)
  }

  /** The notified count is for the log; the contract has no field for it. */
  @Test
  fun `an empty report maps to empty lists rather than absent ones`() {
    val response = ShipmentSyncReport(shipments = emptyList(), notifiedCount = 3).toSyncShipmentsResponse()
    assert(response.shipments == emptyList<ShipmentSyncEntry>())
    assert(response.cancellations == emptyList<FulfillmentCancellationEntry>())
  }
}
