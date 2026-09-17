package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithoutFulfillmentOrders
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.dss.testutil.fixture.COMPLETE_PAGE
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


class SyncShopifyOrderToMonolithTest {

  /** A redelivered `orders/create` is the normal way a 409 happens, so it reads as a success with the reason named. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a 409 from the monolith is logged as an accepted duplicate webhook`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 409 }
    val lines = capturingLogs {
      runBlocking { postMappedOrderToMonolith(monolith, orderToCreateShopifyOrderRequest("acme", minimalOrder()), "orders/create") }
    }
    val line = lines.single { "Monolith create order accepted" in it }
    assert(
      line.startsWith(
        "INFO Monolith create order accepted (order already existed — duplicate webhook) " +
          "topic=orders/create shopifyOrderId=1001 lines=1 {",
      ),
    )
  }

  @Test
  fun `syncShopifyOrderToMonolith loads via Graphql and forwards to monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(
        shopify = shopify,
        monolith = monolith,
        orderGid = "gid://shopify/Order/1001",
        webhookTopic = "orders/create",
      )
    }
    assert(result == WebhookMirrorOutcome.Mirrored)
    assert(monolith.createOrderCalls.single().shopifyOrderId == 1001L)
    assert(monolith.createOrderCalls.single().shopifySubdomain == "acme")
    assert(shopify.orderForDssCalls.single() == "gid://shopify/Order/1001")
  }

  /** The gid is what the monolith keys the order by; without a number in it there is nothing to load or to send. */
  @Test
  fun `a gid without a numeric id is skipped before Shopify is asked`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify = shopify, monolith = monolith, orderGid = "gid://shopify/Order/", webhookTopic = "orders/create")
    }
    assert(result == WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_RESOURCE_ID))
    assert(shopify.orderForDssCalls.isEmpty())
    assert(monolith.createOrderCalls.isEmpty())
  }

  @Test
  fun `syncShopifyOrderToMonolith reports a Shopify failure when the order is not found`() {
    // Default orderForDssResult is NotFound → workflow short-circuits and logs.
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result is WebhookMirrorOutcome.ShopifyFailed)
    assert(!result.isTransient)
    assert(monolith.createOrderCalls.isEmpty())
  }

  @Test
  fun `syncShopifyOrderToMonolith reports a transient Shopify failure on top-level Graphql errors`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.GraphqlError("throttled"))
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result == WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("throttled")))
    assert(result.isTransient)
    assert(monolith.createOrderCalls.isEmpty())
  }

  /** The race this guards: Shopify routes an order into fulfillment orders after creating it, and the delivery can land first. */
  @Test
  fun `an order Shopify has not routed into fulfillment orders yet is mirrored with its lines`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(orderWithoutFulfillmentOrders()) }
    val result = runBlocking { syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create") }
    assert(result == WebhookMirrorOutcome.Mirrored)
    assert(monolith.createOrderCalls.single().lineItems.single().productVariantId == 101L)
  }

  @Test
  fun `an order without a variant-backed line is skipped without asking the monolith`() {
    val order = minimalOrder()
    val tipOnly = order.copy(
      lineItems = LineItemConnection(pageInfo = COMPLETE_PAGE, edges = listOf(LineItemEdge(node = order.lineItems.edges.single().node.copy(variant = null)))),
    )
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(tipOnly) }
    val result = runBlocking { syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create") }
    assert(result == WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES))
    assert(monolith.createOrderCalls.isEmpty())
  }

  @Test
  fun `a monolith 5xx is a transient failure and a 4xx is not`() {
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val down = FakeMonolithService().apply { createOrderStatus = 503 }
    val refusing = FakeMonolithService().apply { createOrderStatus = 400 }

    val whenDown = runBlocking { syncShopifyOrderToMonolith(shopify, down, "gid://shopify/Order/1001", "orders/create") }
    val whenRefused = runBlocking { syncShopifyOrderToMonolith(shopify, refusing, "gid://shopify/Order/1001", "orders/create") }

    assert(whenDown is WebhookMirrorOutcome.MonolithFailed)
    assert(whenDown.isTransient)
    assert(whenRefused is WebhookMirrorOutcome.MonolithFailed)
    assert(!whenRefused.isTransient)
  }

  /** The monolith's own trace id on our failure line is what lets the two services' logs be read side by side. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith 500 is logged at error with the monolith's trace id and the order it was for`() {
    val monolith = FakeMonolithService().apply {
      createOrderStatus = 500
      createOrderErrorBody = """{"error":"order refused","code":"InternalError","trace_id":"monolith-trace-7"}"""
    }
    val lines = capturingLogs {
      runBlocking { postMappedOrderToMonolith(monolith, orderToCreateShopifyOrderRequest("acme", minimalOrder()), "orders/create") }
    }
    val line = lines.single { "Monolith postCreateOrder failed" in it }
    assert(
      line.startsWith(
        "ERROR Monolith postCreateOrder failed: status=500 monolith_trace_id=monolith-trace-7 code=InternalError " +
          "message=order refused topic=orders/create shopifyOrderId=1001 lines=1 fulfillmentStatus=null {",
      ),
    )
  }

  /** The one failure an operator has to act on must read as such, not as a network blip that will pass. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a rejected token is logged as a token problem, not as a network failure`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    val lines = capturingLogs {
      runBlocking { syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create") }
    }
    val line = lines.single { "could not load order" in it }
    assert(line.startsWith("ERROR"))
    assert("Shopify rejected the Admin token (HTTP 401)" in line)
    assert("network" !in line.lowercase())
    assert(monolith.createOrderCalls.isEmpty())
  }

  /**
   * A tip or a custom line is expected and goes at `info`; an id Shopify sent in a shape we cannot read is unexpected
   * data and goes at `warn`. A line with a variant is sent, whether or not a fulfillment order holds it.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every omitted line item is logged with its reason, at warn when Shopify sent an id we cannot read`() {
    val mapped = minimalOrder().lineItems.edges.single().node
    val variant = checkNotNull(mapped.variant)
    val tip = mapped.copy(id = "gid://shopify/LineItem/202", variant = null)
    val unknownVariant = mapped.copy(id = "gid://shopify/LineItem/203", variant = variant.copy(legacyResourceId = "999"))
    val unreadableVariantId =
      mapped.copy(id = "gid://shopify/LineItem/204", variant = variant.copy(legacyResourceId = "not-a-number"))
    val unreadableLineId = mapped.copy(id = "gid://shopify/LineItem/")
    val order = minimalOrder().copy(
      lineItems = LineItemConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(mapped, tip, unknownVariant, unreadableVariantId, unreadableLineId).map { LineItemEdge(node = it) },
      ),
    )
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(order) }
    val monolith = FakeMonolithService()

    val lines = capturingLogs {
      runBlocking { syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create") }
    }

    assert(
      lines.filter { "omitted line item" in it }.map { it.substringBefore(" {") } == listOf(
        "INFO Webhook orders/create: omitted line item gid://shopify/LineItem/202 reason=no_variant shopifyOrderId=1001",
        "WARN Webhook orders/create: omitted line item gid://shopify/LineItem/204 reason=unparseable_variant_id shopifyOrderId=1001",
        "WARN Webhook orders/create: omitted line item gid://shopify/LineItem/ reason=unparseable_line_item_id shopifyOrderId=1001",
      ),
    )
    // The order went out with both readable variant-backed lines, the one on no fulfillment order included.
    assert(monolith.createOrderCalls.single().lineItems.map { it.productVariantId } == listOf(101L, 999L))
  }
}
