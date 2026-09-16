package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.helper.failureReason
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


/**
 * Which fulfillment a tracking status belongs to, and what happens when there is none.
 *
 * Fake-backed rather than wire-level: the workflow composes two `ShopifyGraphqlService` primitives, and both of them —
 * the order query and the event mutation, their decoding and their user errors — are covered at the wire in
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

  // ---------- helpers ----------

  private fun orderWithTracking(trackingNumber: String) =
    orderWithFulfillment(id = 5000L, trackingNumbers = listOf(trackingNumber))

  private fun trackingRequest(
    trackingNumber: String = "1Z999",
    status: String = "in_transit",
    message: String? = null,
  ): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = trackingNumber,
      status = status,
      happenedAt = "2026-04-02T08:30:00Z",
      message = message,
    )
}
