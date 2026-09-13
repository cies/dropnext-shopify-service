package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.mapper.OrderLineItemOmission
import dropnext.dss.mapper.mapOrderForMonolith
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Loads the Shopify order snapshot via [shopify] and POSTs [CreateShopifyOrderRequest] to the monolith.
 * An order with no variant-backed lines is [WebhookMirrorOutcome.Skipped]; the caller decides from the
 * outcome whether Shopify should redeliver.
 */
suspend fun syncShopifyOrderToMonolith(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  orderGid: String,
  webhookTopic: String,
): WebhookMirrorOutcome {
  // The gid is what the webhook named and what Shopify is asked for, so it is what the monolith keys the order by.
  val orderId = legacyIdFromGid(orderGid)?.let(::ShopifyOrderId) ?: run {
    log.warn { "Webhook $webhookTopic: the order gid carries no numeric id orderGid=$orderGid" }
    return WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_RESOURCE_ID)
  }
  val order = when (val loaded = shopify.orderForDss(orderGid)) {
    is Failure -> {
      log.error { "Webhook $webhookTopic: could not load order orderGid=$orderGid error=${loaded.reason.message}" }
      return WebhookMirrorOutcome.ShopifyFailed(loaded.reason)
    }
    is Success -> loaded.value
  }
  log.info { "Webhook order loaded id=${order.id} name=${order.name}" }
  val mapping = mapOrderForMonolith(shopify.shop.subdomainOnly, orderId, order)
  val req = mapping.request
  mapping.omittedLineItems.forEach { omitted ->
    val line = "Webhook $webhookTopic: omitted line item ${omitted.lineItemId} " +
      "reason=${omitted.reason.name.lowercase()} shopifyOrderId=${req.shopifyOrderId}"
    // A tip or a custom line is the normal shape of an order; the other reasons are Shopify data we did not expect.
    if (omitted.reason == OrderLineItemOmission.NO_VARIANT) log.info { line } else log.warn { line }
  }
  if (req.lineItems.isEmpty()) {
    log.warn {
      "Webhook $webhookTopic: skip monolith order sync — mapped line_items empty " +
        "(no variant-backed lines or no fulfillment_order_id — e.g. tips/custom-only order)"
    }
    return WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES)
  }
  return when (val posted = postMappedOrderToMonolith(monolith, req, webhookTopic)) {
    is Success -> WebhookMirrorOutcome.Mirrored
    is Failure -> WebhookMirrorOutcome.MonolithFailed(posted.reason)
  }
}

/** POSTs a mapped order and logs success/failure (testable without Shopify Graphql). */
suspend fun postMappedOrderToMonolith(
  monolith: MonolithService,
  req: CreateShopifyOrderRequest,
  webhookTopic: String,
): MonolithResult<CreateOrderOutcome> {
  val result = monolith.postCreateOrder(req)
  when (result) {
    is Success -> {
      val detail = if (result.value == CreateOrderOutcome.AlreadyExisted) " (order already existed — duplicate webhook)" else ""
      log.info {
        "Monolith create order accepted$detail topic=$webhookTopic " +
          "shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size}"
      }
    }

    is Failure -> logMonolithFailure(
      operation = "postCreateOrder",
      error = result.reason,
      extra = "topic=$webhookTopic shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size} " +
        "fulfillmentStatus=${req.fulfillmentStatus}",
    )
  }
  return result
}
