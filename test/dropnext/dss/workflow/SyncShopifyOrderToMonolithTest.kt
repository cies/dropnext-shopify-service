package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.OrderLineItem
import dropnext.dss.contract.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.ResourceLock



class SyncShopifyOrderToMonolithTest {

  @Test
  fun `posts mapped order via FakeMonolithService`() {
    val fake = FakeMonolithService()
    val req = sampleCreateOrderRequest()
    val result = runBlocking {
      postMappedOrderToMonolith(fake, req, "orders/create")
    }
    assert(result == Success(CreateOrderOutcome.Created))
    assert(fake.createOrderCalls.single().shopifySubdomain == "dropnext-staging")
    assert(fake.createOrderCalls.single().shopifyOrderId == 1001L)
  }

  @Test
  fun `a 409 from the monolith is the order already existing`() {
    val fake = FakeMonolithService().apply { createOrderStatus = 409 }
    val result = runBlocking { postMappedOrderToMonolith(fake, sampleCreateOrderRequest(), "orders/create") }
    assert(result == Success(CreateOrderOutcome.AlreadyExisted))
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

  @Test
  fun `syncShopifyOrderToMonolith skips when no mapped line items remain`() {
    // Order with no fulfillment orders → mapped lineItems is empty → skip sync.
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList())))
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result is WebhookMirrorOutcome.Skipped)
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

  @Test
  fun `surfaces monolith 500 as a rejection carrying the monolith's trace id`() {
    val fake = FakeMonolithService().apply {
      createOrderStatus = 500
      createOrderErrorBody =
        """{"error":"fail","code":"InternalError","trace_id":"fake123"}"""
    }
    val result = runBlocking {
      postMappedOrderToMonolith(fake, sampleCreateOrderRequest(), "orders/create")
    }
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is MonolithError.Rejected)
    assert((error as MonolithError.Rejected).body.monolithTraceId == "fake123")
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

  /** A tip or a custom line is expected and goes at `info`; a variant on no fulfillment order is not, and goes at `warn`. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every omitted line item is logged with its reason`() {
    val mapped = minimalOrder().lineItems.edges.single().node
    val tip = mapped.copy(id = "gid://shopify/LineItem/202", variant = null)
    val unknownVariant = mapped.copy(
      id = "gid://shopify/LineItem/203",
      variant = mapped.variant!!.copy(id = "gid://shopify/ProductVariant/999", legacyResourceId = "999"),
    )
    val order = minimalOrder().copy(
      lineItems = LineItemConnection(edges = listOf(mapped, tip, unknownVariant).map { LineItemEdge(node = it) }),
    )
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(order) }
    val monolith = FakeMonolithService()

    val lines = capturingLogs {
      runBlocking { syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create") }
    }

    val omitted = lines.filter { "omitted line item" in it }
    assert(omitted.size == 2)
    assert(omitted.single { "LineItem/202" in it }.startsWith("INFO"))
    assert("reason=no_variant" in omitted.single { "LineItem/202" in it })
    assert(omitted.single { "LineItem/203" in it }.startsWith("WARN"))
    assert("reason=no_fulfillment_order" in omitted.single { "LineItem/203" in it })
    // The order still went out with the one line the monolith can take.
    assert(monolith.createOrderCalls.single().lineItems.single().productVariantId == 101L)
  }

  private fun sampleCreateOrderRequest(): CreateShopifyOrderRequest =

    CreateShopifyOrderRequest(
      shopifySubdomain = "dropnext-staging",
      shopifyOrderId = 1001L,
      name = "#1001",
      financialStatus = "Paid",
      fulfillmentStatus = null,
      createdAt = "2026-04-25T10:30:00Z",
      shippingAddress = ShippingAddress(
        firstName = null,
        lastName = null,
        address1 = "",
        address2 = null,
        city = "",
        province = null,
        provinceCode = null,
        countryCode = "US",
        zip = null,
        phone = null,
      ),
      lineItems = listOf(
        OrderLineItem(
          shopifyLineItemId = 201L,
          productVariantId = 101L,
          quantity = 1,
          fulfillmentOrderId = 0L,
          snapshotOfVariantTitle = "Item",
          snapshotOfProductTitle = "Product",
          snapshotOfPriceAsString = "19.99",
        ),
      ),
      totalAsString = "19.99",
      currency = "USD",
    )
}
