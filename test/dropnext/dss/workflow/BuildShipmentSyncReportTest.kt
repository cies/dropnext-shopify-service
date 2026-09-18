package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.FulfillmentCancellation
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSync
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.shipment
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


/**
 * The report is assembled from the plan and the effects alone, so every outcome can be set up here without Shopify.
 * That the plan and the effects are what a real run produces is `SyncShopifyShipmentsToFulfillmentsTest`'s.
 */
class BuildShipmentSyncReportTest {

  private val created = shipment(tracking = "TRK-CREATED")
  private val alreadyFulfilled = shipment(tracking = "TRK-DONE")
  private val noRoom = shipment(tracking = "TRK-NO-ROOM")
  private val notOnOrder = shipment(tracking = "TRK-UNKNOWN")
  private val refused = shipment(tracking = "TRK-REFUSED")

  @Test
  fun `every shipment gets one row in request order with the outcome of its decision`() {
    val shipments = listOf(noRoom, created, alreadyFulfilled, refused, notOnOrder)
    val liveFulfillment = fulfillment(3990L, listOf("TRK-DONE"))
    val plan = plan(
      creates = listOf(create(created), create(refused)),
      alreadyFulfilled = listOf(AlreadyFulfilledShipment(alreadyFulfilled, listOf(liveFulfillment))),
      skipped = listOf(skipped(noRoom, SkipReason.NO_ROOM), skipped(notOnOrder, SkipReason.NOT_ON_ORDER)),
    )
    val effected = MutationEffectReport(
      effects = listOf(
        MutationEffect.Created(create(created), ShopifyFulfillmentId(4001L)),
        MutationEffect.Refused(create(refused), listOf("tracking number invalid")),
      ),
    )
    val report = buildShipmentSyncReport(shipments, plan, emptyList(), effected)
    val expected = listOf(
      ShipmentSync("TRK-NO-ROOM", ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM)),
      ShipmentSync("TRK-CREATED", ShipmentSyncStatus.Created(ShopifyFulfillmentId(4001L))),
      ShipmentSync("TRK-DONE", ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(3990L))),
      ShipmentSync("TRK-REFUSED", ShipmentSyncStatus.Refused(listOf("tracking number invalid"))),
      ShipmentSync("TRK-UNKNOWN", ShipmentSyncStatus.Skipped(SkipReason.NOT_ON_ORDER)),
    )
    assert(report.shipments == expected)
    assert(report.cancellations.isEmpty())
  }

  @Test
  fun `the create the run stopped on is failed and the ones after it are not attempted`() {
    val first = shipment(tracking = "TRK-1")
    val second = shipment(tracking = "TRK-2")
    val third = shipment(tracking = "TRK-3")
    val plan = plan(creates = listOf(create(first), create(second), create(third)))
    val effected = MutationEffectReport(
      effects = listOf(MutationEffect.Created(create(first), ShopifyFulfillmentId(5001L))),
      stop = MutationStop(create(second), ShopifyError.HttpError(503)),
    )
    val report = buildShipmentSyncReport(listOf(first, second, third), plan, emptyList(), effected)
    val expected = listOf(
      ShipmentSync("TRK-1", ShipmentSyncStatus.Created(ShopifyFulfillmentId(5001L))),
      ShipmentSync("TRK-2", ShipmentSyncStatus.Failed),
      ShipmentSync("TRK-3", ShipmentSyncStatus.NotAttempted),
    )
    assert(report.shipments == expected)
  }

  /** The monolith finds its shipment by the string it sent, so nothing may trim it on the way back. */
  @Test
  fun `a tracking number is echoed exactly as the request spelled it`() {
    val spaced = shipment(tracking = " TRK-1 ")
    val plan = plan(creates = listOf(create(spaced)))
    val effected = MutationEffectReport(
      effects = listOf(MutationEffect.Created(create(spaced), ShopifyFulfillmentId(5001L))),
    )
    val report = buildShipmentSyncReport(listOf(spaced), plan, emptyList(), effected)
    assert(report.shipments.single().trackingNumber == " TRK-1 ")
  }

  @Test
  fun `a cancellation row per planned cancel in the order sent with what Shopify answered`() {
    val canceled = cancel(8001L, "TRK-OLD-1")
    val refusedCancel = cancel(8002L, "TRK-OLD-2")
    val failed = cancel(8003L, "TRK-OLD-3")
    val notAttempted = cancel(8004L, "TRK-OLD-4")
    val cancels = listOf(canceled, refusedCancel, failed, notAttempted)
    val effected = MutationEffectReport(
      effects = listOf(
        MutationEffect.Canceled(canceled),
        MutationEffect.Refused(refusedCancel, listOf("cannot cancel")),
      ),
      stop = MutationStop(failed, ShopifyError.Network("connection reset")),
    )
    val report = buildShipmentSyncReport(emptyList(), plan(), cancels, effected)
    val refusedStatus = FulfillmentCancellationStatus.Refused(listOf("cannot cancel"))
    val expected = listOf(
      FulfillmentCancellation("TRK-OLD-1", ShopifyFulfillmentId(8001L), FulfillmentCancellationStatus.Canceled),
      FulfillmentCancellation("TRK-OLD-2", ShopifyFulfillmentId(8002L), refusedStatus),
      FulfillmentCancellation("TRK-OLD-3", ShopifyFulfillmentId(8003L), FulfillmentCancellationStatus.Failed),
      FulfillmentCancellation("TRK-OLD-4", ShopifyFulfillmentId(8004L), FulfillmentCancellationStatus.NotAttempted),
    )
    assert(report.cancellations == expected)
    assert(report.canceledCount == 1)
    assert(report.cancelRefusedCount == 1)
  }

  @Test
  fun `only the creates that landed and emailed the customer are counted as notified`() {
    val notifying = shipment(tracking = "TRK-NEW")
    val silent = shipment(tracking = "TRK-KNOWN")
    val refusedNotifying = shipment(tracking = "TRK-REFUSED")
    val plan = plan(
      creates = listOf(create(notifying, notify = true), create(silent), create(refusedNotifying, notify = true)),
    )
    val effected = MutationEffectReport(
      effects = listOf(
        MutationEffect.Created(create(notifying, notify = true), ShopifyFulfillmentId(5001L)),
        MutationEffect.Created(create(silent), ShopifyFulfillmentId(5002L)),
        MutationEffect.Refused(create(refusedNotifying, notify = true), listOf("tracking number invalid")),
      ),
    )
    val report = buildShipmentSyncReport(listOf(notifying, silent, refusedNotifying), plan, emptyList(), effected)
    assert(report.notifiedCount == 1)
    assert(report.createdCount == 2)
    assert(report.refusedCount == 1)
    assert(report.createdFulfillmentIds == listOf(ShopifyFulfillmentId(5001L), ShopifyFulfillmentId(5002L)))
  }

  @Test
  fun `a run that stopped before its plan reports every shipment as not attempted`() {
    val report = notAttemptedShipmentSyncReport(listOf(created, noRoom))
    val expected = listOf(
      ShipmentSync("TRK-CREATED", ShipmentSyncStatus.NotAttempted),
      ShipmentSync("TRK-NO-ROOM", ShipmentSyncStatus.NotAttempted),
    )
    assert(report.shipments == expected)
    assert(report.cancellations.isEmpty())
    assert(report.notifiedCount == 0)
  }

  /** The report names the stale fulfillment in the way, so an operator can find it in the Shopify admin. */
  @Test
  fun `a blocked shipment is reported with the fulfillment that blocks it`() {
    val blocked = shipment(tracking = "TRK-BLOCKED")
    val stale = fulfillment(3985L, listOf("TRK-BLOCKED"))
    val plan = plan(blocked = listOf(BlockedShipment(blocked, stale)))
    val report = buildShipmentSyncReport(listOf(blocked), plan, emptyList(), MutationEffectReport(effects = emptyList()))
    assert(report.shipments == listOf(ShipmentSync("TRK-BLOCKED", ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(3985L)))))
    assert(report.blockedCount == 1)
  }

  /** The reload after a cancel failed: the cancel landed and must be reported, the shipments were never classified. */
  @Test
  fun `a run that stopped after its cancels reports them with every shipment not attempted`() {
    val canceled = cancel(8001L, "TRK-OLD")
    val stopped = cancel(8002L, "TRK-OLDER")
    val effected = MutationEffectReport(
      effects = listOf(MutationEffect.Canceled(canceled)),
      stop = MutationStop(stopped, ShopifyError.Network("connection reset")),
    )
    val report = notAttemptedShipmentSyncReport(listOf(created), listOf(canceled, stopped), effected)
    assert(report.shipments == listOf(ShipmentSync("TRK-CREATED", ShipmentSyncStatus.NotAttempted)))
    assert(report.cancellations.map { it.status } == listOf(FulfillmentCancellationStatus.Canceled, FulfillmentCancellationStatus.Failed))
  }

  /** The budget stopped the run before the next mutation: nothing was sent, so nothing failed. */
  @Test
  fun `a run the budget stopped reports the mutations it did not send as not attempted`() {
    val first = shipment(tracking = "TRK-1")
    val second = shipment(tracking = "TRK-2")
    val plan = plan(creates = listOf(create(first), create(second)))
    val effected = MutationEffectReport(
      effects = listOf(MutationEffect.Created(create(first), ShopifyFulfillmentId(5001L))),
      stop = MutationStop(mutation = null, ShopifyError.TimedOut("syncing the shipments", 25.seconds)),
    )
    val report = buildShipmentSyncReport(listOf(first, second), plan, emptyList(), effected)
    assert(report.shipments.map { it.status } == listOf(ShipmentSyncStatus.Created(ShopifyFulfillmentId(5001L)), ShipmentSyncStatus.NotAttempted))
  }

  @Test
  fun `an empty request is an empty report`() {
    val report = buildShipmentSyncReport(emptyList(), plan(), emptyList(), MutationEffectReport(effects = emptyList()))
    assert(report.shipments.isEmpty())
    assert(report.cancellations.isEmpty())
  }

  private fun plan(
    creates: List<ShopifyMutation.FulfillmentCreate> = emptyList(),
    alreadyFulfilled: List<AlreadyFulfilledShipment> = emptyList(),
    skipped: List<SkippedShipment> = emptyList(),
    blocked: List<BlockedShipment> = emptyList(),
  ) = ShipmentPlan(
    mutations = creates,
    alreadyFulfilledShipments = alreadyFulfilled,
    skippedShipments = skipped,
    blockedShipments = blocked,
  )

  private fun create(shipment: Shipment, notify: Boolean = false) = ShopifyMutation.FulfillmentCreate(
    lineItems = emptyList(),
    trackingNumber = shipment.trackingNumber,
    carrier = shipment.carrier,
    trackingUrl = shipment.trackingUrl,
    notifyCustomer = notify,
  )

  private fun cancel(fulfillmentId: Long, tracking: String) =
    ShopifyMutation.FulfillmentCancel(ShopifyFulfillmentId(fulfillmentId), tracking)

  private fun skipped(shipment: Shipment, reason: SkipReason) = SkippedShipment(shipment, reason, emptyList())
}
