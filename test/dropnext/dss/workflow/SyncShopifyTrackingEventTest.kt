package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyFulfillmentEvent
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * Which fulfillment a tracking status belongs to, and what happens when there is none.
 *
 * Fake-backed rather than wire-level: the workflow composes three `ShopifyGraphqlService` primitives, and all of them —
 * the order query, the events query and the event mutation, their decoding and their user errors — are covered at the wire in
 * `HttpShopifyGraphqlServiceTest`. What is left here is the choosing, which the in-memory fake exercises for nothing
 * and which lets the test read back what the mutation was actually asked for.
 */
class SyncShopifyTrackingEventTest {

  @Test
  fun `syncShopifyTrackingEvent rejects an unsupported status without asking Shopify anything`() = runBlocking {
    val shopify = FakeShopifyGraphqlService()

    val error = syncShopifyTrackingEvent(shopify, trackingRequest(status = "yeeted")).failureReason()

    assert(error is ShopifyError.UserError)
    assert("unsupported tracking status" in (error as ShopifyError.UserError).messages.single())
    assert(shopify.orderForDssCalls.isEmpty())
  }

  /** The request validation refuses it first; a direct caller gets a refusal rather than a parse exception. */
  @Test
  fun `syncShopifyTrackingEvent rejects a time that does not read without asking Shopify anything`() = runBlocking {
    val shopify = FakeShopifyGraphqlService()

    val error = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "yesterday")).failureReason()

    assert(error == ShopifyError.UserError(listOf("unreadable happened_at: yesterday")))
    assert(shopify.orderForDssCalls.isEmpty())
  }

  @Test
  fun `syncShopifyTrackingEvent answers NotFound when Shopify has no such order`() = runBlocking {
    // The fake's default order result is exactly this: Shopify answering that it has no such order.
    val shopify = FakeShopifyGraphqlService()

    assert(syncShopifyTrackingEvent(shopify, trackingRequest()).failureReason() is ShopifyError.NotFound)
  }

  @Test
  fun `syncShopifyTrackingEvent answers NotFound when no fulfillment carries the tracking number`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTracking("OTHER-TRACK"))
    }

    val error = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999")).failureReason()

    assert(error is ShopifyError.NotFound)
    assert("no fulfillment with tracking number 1Z999" in error.message)
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  @Test
  fun `syncShopifyTrackingEvent attaches the event to the fulfillment carrying the tracking number`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTracking("1Z999"))
      createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7777L))
    }

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999", message = "Left the sorting center"))

    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
    val event = shopify.createFulfillmentEventCalls.single()
    assert(event.fulfillmentGid == "gid://shopify/Fulfillment/5000")
    assert(event.status == FulfillmentEventStatus.IN_TRANSIT)
    assert(event.happenedAt == "2026-04-02T08:30:00Z")
    assert(event.message == "Left the sorting center")
  }

  /**
   * Shopify stores the time it is sent, and whether it keeps, rounds or cuts a fraction is not documented.
   * A whole second has no fraction to lose, so a redelivery always finds the event at the second it was sent.
   */
  @Test
  fun `syncShopifyTrackingEvent sends Shopify the time cut to the second`() = runBlocking {
    val shopify = trackedShopify()

    syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T10:30:00.734+02:00"))

    assert(shopify.createFulfillmentEventCalls.single().happenedAt == "2026-04-02T08:30:00Z")
  }

  @Test
  fun `syncShopifyTrackingEvent passes a refused event creation through`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTracking("1Z999"))
      createFulfillmentEventResult = Failure(ShopifyError.UserError(listOf("happenedAt invalid")))
    }

    val error = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999")).failureReason()

    assert(error == ShopifyError.UserError(listOf("happenedAt invalid")))
  }

  /** A cancelled fulfillment keeps its tracking number, but the package it named is no longer on the order. */
  @Test
  fun `syncShopifyTrackingEvent answers NotFound when only a cancelled fulfillment carries the tracking number`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        orderWithFulfillments(fulfillment(5000L, listOf("1Z999"), status = FulfillmentStatus.CANCELLED)),
      )
    }

    val error = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999")).failureReason()

    assert(error is ShopifyError.NotFound)
  }

  /** Carriers and the monolith disagree about padding, and a space on either side must not lose the event. */
  @Test
  fun `syncShopifyTrackingEvent matches a tracking number that differs only in surrounding whitespace`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTracking("1Z999 "))
      createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7777L))
    }

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = " 1Z999"))

    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
  }

  // ---------- an event Shopify already has ----------

  /** The monolith's queue delivers a job at least once, so the same checkpoint can arrive again after it was created. */
  @Test
  fun `syncShopifyTrackingEvent answers the event already on the fulfillment instead of creating it again`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  @Test
  fun `syncShopifyTrackingEvent reads the events of the fulfillment carrying the tracking number`() = runBlocking {
    val shopify = trackedShopify()

    syncShopifyTrackingEvent(shopify, trackingRequest())

    assert(shopify.fulfillmentEventsCalls == listOf("gid://shopify/Fulfillment/5000"))
  }

  @Test
  fun `syncShopifyTrackingEvent creates the event when the fulfillment has its status only at another time`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:29:59Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
    assert(shopify.createFulfillmentEventCalls.size == 1)
  }

  @Test
  fun `syncShopifyTrackingEvent creates the event when the fulfillment has another status at that time`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.OUT_FOR_DELIVERY, "2026-04-02T08:30:00Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(status = "in_transit", happenedAt = "2026-04-02T08:30:00Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
    assert(shopify.createFulfillmentEventCalls.size == 1)
  }

  /** Shopify writes the time back in its own offset and precision, so the moment is compared as an instant, never as text. */
  @Test
  fun `syncShopifyTrackingEvent recognizes the same moment written with another offset`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T10:30:00+02:00"))

    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  @Test
  fun `syncShopifyTrackingEvent recognizes the same moment written with fractional seconds`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00.734Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  /** Shopify may keep the fraction of an event created before the time was cut, so the event side is cut too. */
  @Test
  fun `syncShopifyTrackingEvent recognizes the same moment when the event has fractional seconds`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00.734Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  /**
   * Two times a millisecond apart across a second's boundary are two checkpoints: both sides are cut, never rounded.
   * The event is created at the second the request names.
   */
  @Test
  fun `syncShopifyTrackingEvent creates the event for a time just before the second the event has`() = runBlocking {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:01Z"))

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00.999Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
    assert(shopify.createFulfillmentEventCalls.single().happenedAt == "2026-04-02T08:30:00Z")
  }

  @Test
  fun `syncShopifyTrackingEvent finds the matching event among several`() = runBlocking {
    val shopify = trackedShopify(
      existingEvent(7003L, FulfillmentEventStatus.OUT_FOR_DELIVERY, "2026-04-03T07:00:00Z"),
      existingEvent(7002L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z"),
      existingEvent(7001L, FulfillmentEventStatus.CONFIRMED, "2026-04-01T12:00:00Z"),
    )

    val result = syncShopifyTrackingEvent(shopify, trackingRequest(happenedAt = "2026-04-02T08:30:00Z"))

    assert(result == Success(ShopifyFulfillmentEventId(7002L)))
  }

  /** What keeps the duplicates countable in Logflare. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `syncShopifyTrackingEvent logs an event it found already on the fulfillment`() {
    val shopify = trackedShopify(existingEvent(7001L, FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z"))

    val lines = capturingLogs { runBlocking { syncShopifyTrackingEvent(shopify, trackingRequest()) } }

    val line = lines.single { "Tracking event already on fulfillment" in it }
    assert(line.startsWith("INFO Tracking event already on fulfillment fulfillmentId=5000 eventId=7001 status=IN_TRANSIT"))
  }

  /** Creating the event anyway would bring the duplicate back; failing lets the monolith retry the job. */
  @Test
  fun `syncShopifyTrackingEvent fails without creating anything when the events cannot be read`() = runBlocking {
    val unreachable = ShopifyError.Network("connection reset")
    val shopify = trackedShopify().apply { fulfillmentEventsResult = Failure(unreachable) }

    val error = syncShopifyTrackingEvent(shopify, trackingRequest()).failureReason()

    assert(error == unreachable)
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  @Test
  fun `syncShopifyTrackingEvent fails without creating anything when reading the events is throttled`() = runBlocking {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))
    val shopify = trackedShopify().apply { fulfillmentEventsResult = Failure(throttled) }

    val error = syncShopifyTrackingEvent(shopify, trackingRequest()).failureReason()

    assert(error == throttled)
    assert(shopify.createFulfillmentEventCalls.isEmpty())
  }

  // ---------- helpers ----------

  /** An order whose fulfillment 5000 carries `1Z999` and already has [events]; an event created on it is 7777. */
  private fun trackedShopify(vararg events: ShopifyFulfillmentEvent) =
    FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTracking("1Z999"))
      fulfillmentEventsResult = Success(events.toList())
      createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7777L))
    }

  private fun existingEvent(id: Long, status: FulfillmentEventStatus, happenedAt: String) =
    ShopifyFulfillmentEvent(ShopifyFulfillmentEventId(id), status, Instant.parse(happenedAt))

  private fun orderWithTracking(trackingNumber: String) =
    orderWithFulfillment(id = 5000L, trackingNumbers = listOf(trackingNumber))

  private fun trackingRequest(
    trackingNumber: String = "1Z999",
    status: String = "in_transit",
    happenedAt: String = "2026-04-02T08:30:00Z",
    message: String? = null,
  ): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = trackingNumber,
      status = status,
      happenedAt = happenedAt,
      message = message,
    )
}
