package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.liveFulfillmentsWithTrackingNumber
import dropnext.dss.lib.shopify.graphql.ParsedFulfillmentStatus
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyFulfillmentEvent
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit


private val log = KotlinLogging.logger {}

/**
 * Looks up the live Shopify fulfillment by order and tracking number, then creates a `FulfillmentEvent` in Shopify.
 * It does not when the fulfillment already has one with the same status at the same second.
 *
 * The monolith delivers each tracking update at least once,
 * so the same checkpoint can arrive after its event was created.
 * That event's id is answered instead, exactly as if it had been created now.
 * The message is not part of the match: carriers reword it for the same checkpoint.
 * Composes [ShopifyGraphqlService.orderForDss], [ShopifyGraphqlService.fulfillmentEvents]
 * and [ShopifyGraphqlService.createFulfillmentEvent].
 */
suspend fun syncShopifyTrackingEvent(
  shopify: ShopifyGraphqlService,
  payload: TrackingUpdateRequest,
): ShopifyResult<ShopifyFulfillmentEventId> {
  val status = when (val parsed = ParsedFulfillmentStatus.parseFulfillmentEventStatus(payload.status)) {
    is ParsedFulfillmentStatus.Known -> parsed.value
    is ParsedFulfillmentStatus.Unknown ->
      return Failure(ShopifyError.UserError(listOf("unsupported tracking status: ${parsed.raw}")))
  }
  // The request validation refuses such a time already; this keeps a direct caller from a parse exception.
  val happenedAt = runCatching { OffsetDateTime.parse(payload.happenedAt).toInstant() }.getOrNull()
    ?: return Failure(ShopifyError.UserError(listOf("unreadable happened_at: ${payload.happenedAt}")))
  val shopifyOrderId = ShopifyOrderId(payload.shopifyOrderId)
  val order = when (val loaded = shopify.orderForDss(orderGid(shopifyOrderId))) {
    is Failure -> return loaded
    is Success -> loaded.value
  }
  val fulfillmentGid = liveFulfillmentsWithTrackingNumber(order, payload.trackingNumber).firstOrNull()?.id
    ?: return Failure(
      ShopifyError.NotFound("no fulfillment with tracking number ${payload.trackingNumber} on order $shopifyOrderId"),
    )
  // A failed read fails the call, so the monolith retries it: creating the event blind would bring the duplicate back.
  val existing = when (val events = shopify.fulfillmentEvents(fulfillmentGid)) {
    is Failure -> return events
    is Success -> events.value.firstOrNull { it.isSameCheckpoint(status, happenedAt) }
  }
  if (existing != null) {
    val fulfillmentId = legacyIdFromGid(fulfillmentGid) ?: fulfillmentGid
    log.info { "Tracking event already on fulfillment fulfillmentId=$fulfillmentId eventId=${existing.id} status=$status" }
    return Success(existing.id)
  }
  // Whether Shopify keeps, rounds or cuts a fraction is not documented, and a rounded one would never match again.
  // A whole second has no fraction to lose, at the cost of under a second on the customer's timeline.
  return shopify.createFulfillmentEvent(
    fulfillmentGid = fulfillmentGid,
    status = status,
    happenedAt = happenedAt.wholeSeconds().toString(),
    message = payload.message,
  )
}

/**
 * Compared at whole seconds, both sides cut and never rounded: the request may carry a fraction,
 * and an event created before the DSS sent whole seconds may keep one.
 */
private fun ShopifyFulfillmentEvent.isSameCheckpoint(status: FulfillmentEventStatus, happenedAt: Instant): Boolean =
  this.status == status && this.happenedAt.wholeSeconds() == happenedAt.wholeSeconds()

private fun Instant.wholeSeconds(): Instant = truncatedTo(ChronoUnit.SECONDS)
