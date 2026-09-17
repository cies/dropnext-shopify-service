package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.TOPIC_MDC_KEY
import dropnext.dss.lib.slf4j.WEBHOOK_ID_MDC_KEY
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithoutFulfillmentOrders
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.MutableTimeSource
import dropnext.dss.testutil.helper.awaitUntil
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.mdcOf
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The inbound Shopify webhook through the production module: the HMAC gate, the topic routing and
 * the outbound effect. Signing happens here rather than through our own verifier, so a change to
 * `ShopifyHmacVerifierService` cannot make both sides agree on the wrong thing.
 */
class ShopifyWebhookHandlersTest {

  @Test
  fun `rejects request with missing HMAC header as 401`() = withDssApp(testDependencies()) { client ->
    val r = client.post(Paths.webhooksShopify) {
      header("X-Shopify-Topic", "orders/create")
      setBody("""{"id":1}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `bad HMAC on orders_create gates out monolith POST`() {
    val monolith = FakeMonolithService()
    withDssApp(testDependencies(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.post(Paths.webhooksShopify) {
        header("X-Shopify-Topic", "orders/create")
        header("X-Shopify-Shop-Domain", "acme.myshopify.com")
        header("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(ByteArray(32)))
        setBody("""{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""")
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /** A rotated app secret or a subscription another app left behind is invisible without this line. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a failed HMAC is logged at warn without the signature or the body`() {
    val forgedSignature = Base64.getEncoder().encodeToString(ByteArray(32))
    val lines = capturingLogs {
      withDssApp(testDependencies()) { client ->
        val r = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", "acme.myshopify.com")
          header("X-Shopify-Hmac-Sha256", forgedSignature)
          setBody("""{"id":1001,"note":"do-not-log"}""")
        }
        assert(r.status == HttpStatusCode.Unauthorized)
      }
    }
    val line = lines.single { "HMAC mismatch" in it }
    assert(line.startsWith("WARN"))
    assert("topic=orders/create" in line)
    assert("shopDomainHeader=acme.myshopify.com" in line)
    assert("hmacHeaderPresent=true" in line)
    assert(forgedSignature !in line)
    assert("do-not-log" !in line)
  }

  /** The shop has a service, so a topic routed to a workflow by mistake would show up as a Shopify read. */
  @Test
  fun `a verified delivery for a topic the service never handled is acknowledged as not mirrored`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook("shop/update", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().reason == "topic_not_mirrored")
      assert(shopify.productByIdCalls.isEmpty())
      assert(shopify.orderForDssCalls.isEmpty())
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /** Every shop has a service here, so only the missing shop can stop the body's product id from being loaded. */
  @Test
  fun `a product delivery naming no shop is skipped without Graphql or the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1}""", shopDomain = null)
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().reason == "no_shop_domain")
      assert(shopify.productByIdCalls.isEmpty())
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /** Neither `admin_graphql_api_id` nor `id`: there is no order to load, so Shopify must not be asked for one. */
  @Test
  fun `an orders_create delivery without an order id is skipped without Graphql or the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook("orders/create", """{"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().reason == "no_resource_id")
      assert(shopify.orderForDssCalls.isEmpty())
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /** The summary line is the one line a skipped delivery leaves: at error level for a shop without a token, and nothing else. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a delivery for a shop without a token leaves one error line and no other`() {
    val lines = capturingLogs {
      withDssApp(testDependencies(monolith = FakeMonolithService())) { client ->
        val r = client.signedWebhook("products/create", """{"id":1,"domain":"acme.myshopify.com"}""")
        assert(r.status == HttpStatusCode.OK)
      }
    }
    val done = lines.single { "Webhook done" in it }
    assert(done.startsWith("ERROR"))
    assert("outcome=skipped reason=no_admin_token" in done)
    assert(lines.none { "no Admin token" in it })
  }

  @Test
  fun `orders_create end-to-end POSTs the mapped order to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      val forwarded = monolith.createOrderCalls.single()
      assert(forwarded.shopifyOrderId == 1001L)
      assert(forwarded.shopifySubdomain == "acme")
      assert(forwarded.lineItems.single().productVariantId == 101L)
      assert(shopify.orderForDssCalls.single() == "gid://shopify/Order/1001")
    }
  }

  /** Shopify routes an order into fulfillment orders after creating it; a delivery that lands first must still carry the order. */
  @Test
  fun `orders_create for an order not yet routed into fulfillment orders POSTs it with its lines`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(orderWithoutFulfillmentOrders()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().outcome == "mirrored")
      assert(monolith.createOrderCalls.single().lineItems.single().productVariantId == 101L)
    }
  }

  // ---------- product webhook flows ----------

  @Test
  fun `products_create loads the product and upserts mapped variants to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/create",
        """{"id":501,"admin_graphql_api_id":"gid://shopify/Product/501","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(shopify.productByIdCalls.single() == "gid://shopify/Product/501")
      val upsert = monolith.upsertProductVariantsCalls.single()
      assert(upsert.shopifySubdomain == "acme")
      assert(upsert.productVariants.single().productVariantId == 9001L)
      assert(upsert.productVariants.single().priceCurrency == "EUR")
    }
  }

  @Test
  fun `products_update routes through the same upsert path as products_create`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "502", variantId = "9002"), "USD"))
    }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/update",
        """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.upsertProductVariantsCalls.single().productVariants.single().productVariantId == 9002L)
    }
  }

  @Test
  fun `products_create skips monolith call when Shopify returns no product`() {
    val monolith = FakeMonolithService()
    // The fake's default productByIdResult is a successful `null`.
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/create",
        """{"id":999,"admin_graphql_api_id":"gid://shopify/Product/999","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(shopify.productByIdCalls.size == 1)
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /** Shopify's `products/delete` body is the product id and nothing else; the shop comes from the header. */
  @Test
  fun `products_delete asks the monolith to delete the product's variants from the id-only body`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook("products/delete", """{"id":503}""")
      assert(r.status == HttpStatusCode.OK)
      val deleteReq = monolith.deleteProductVariantsCalls.single()
      assert(deleteReq.shopifySubdomain == "acme")
      assert(deleteReq.productId == 503L)
      // The product is gone from Shopify: nothing to fetch.
      assert(shopify.productByIdCalls.isEmpty())
    }
  }

  /** A delete needs no Shopify Admin token, so a shop without one must not lose its deletes. */
  @Test
  fun `products_delete reaches the monolith when no token is available for the shop`() {
    val monolith = FakeMonolithService()
    withDssApp(testDependencies(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/delete", """{"id":503}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.single().productId == 503L)
    }
  }

  @Test
  fun `products_delete without a product id in the body skips the monolith call`() {
    val monolith = FakeMonolithService()
    withDssApp(testDependencies(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.signedWebhook("products/delete", """{"admin_graphql_api_id":"gid://shopify/Product/503"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.isEmpty())
    }
  }

  /** A shop installed before the service stopped subscribing to `orders/updated` keeps delivering it until it is registered again. */
  @Test
  fun `a delivery for a topic the service no longer subscribes to is acknowledged without work`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/updated",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
      assert(r.body<WebhookDeliveryResponse>().reason == "topic_not_mirrored")
      assert(shopify.orderForDssCalls.isEmpty())
    }
  }

  /**
   * After an uninstall every webhook for the shop hits a `401` at Shopify. The delivery is still acknowledged, and the
   * body Shopify stores with it names the token rather than the network, because that is all whoever opens the
   * delivery log gets. The workflow's own line is pinned in `SyncShopifyOrderToMonolithTest`.
   */
  @Test
  fun `orders_create with a rejected token is acknowledged with 200 and named as a token problem`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().error == "shopify_token_rejected")
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  // ---------- what Shopify is told to do next ----------

  /** A monolith deploy window must not lose orders: a 5xx from it becomes a 502 to Shopify, which redelivers. */
  @Test
  fun `orders_create with the monolith down is answered 502 so Shopify redelivers`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 503 }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  @Test
  fun `products_update with Shopify unreachable is answered 502 so Shopify redelivers`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { productByIdResult = Failure(ShopifyError.Network("connection reset")) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/update",
        """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /**
   * A redelivery loads the same first page, so asking Shopify for one only burns the eight attempts it allows before it
   * drops the subscription. The delivery is acknowledged and the countable label is what an operator alerts on.
   */
  @Test
  fun `products_update on a product with more variants than we load is acknowledged with 200`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Failure(ShopifyError.Truncated("product.variants", 100))
    }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/update",
        """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().error == "shopify_truncated")
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /** A request the monolith refuses gets the same answer on every redelivery, so Shopify is not asked for one. */
  @Test
  fun `orders_create the monolith refuses with a 4xx is acknowledged with 200`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 400 }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  /** After a restart the token is only at the monolith; a monolith that does not answer must not cost the order. */
  @Test
  fun `orders_create whose token the monolith could not be asked for is answered 502 so Shopify redelivers`() {
    val monolith = FakeMonolithService()
    withDssApp(testDependencies(monolith = monolith, tokenSourceUnavailable = true)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /**
   * Shopify stops waiting after five seconds. A read still running when the budget ends is cancelled however long the
   * write grace is: nothing has changed yet, and the redelivery asks again.
   */
  @Test
  fun `orders_create whose Shopify read outlives the time budget is answered 502 without waiting out the write grace`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      orderForDssDelay = 2.seconds
    }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify, webhookMirrorBudget = 50.milliseconds, webhookWriteGrace = 5.seconds)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /** A delivery that finds every mirror slot taken is answered before any work, so a burst cannot queue up behind itself. */
  @Test
  fun `a delivery arriving while every mirror slot is taken is answered 502 without work`() {
    val monolith = FakeMonolithService()
    val gate = CompletableDeferred<Unit>()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      orderForDssGate = gate
    }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify, webhookMirrorSlots = Semaphore(1))) { client ->
      val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
      coroutineScope {
        val first = async { client.signedWebhook("orders/create", body) }
        // The first delivery holds the one slot once it is inside its order load, waiting at the gate.
        assert(awaitUntil { shopify.orderForDssCalls.isNotEmpty() })

        val second = client.signedWebhook("orders/create", body)
        assert(second.status == HttpStatusCode.BadGateway)
        assert(shopify.orderForDssCalls.size == 1)

        gate.complete(Unit)
        assert(first.await().status == HttpStatusCode.OK)
      }
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  /**
   * A missing scope answers the same on every redelivery; asking for them would only cost the subscription. The code
   * reaches only the summary line, while the response body carries the label alone, so this case reads the log.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `orders_create that Shopify refuses with ACCESS_DENIED is acknowledged with 200 and logged with the code`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.GraphqlError("Access denied for order field.", codes = listOf("ACCESS_DENIED")))
    }
    val lines = capturingLogs {
      withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
        val r = client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
        )
        assert(r.status == HttpStatusCode.OK)
        r.body<WebhookDeliveryResponse>()
      }
    }
    assert(lines.value.error == "shopify_graphql")
    assert(monolith.createOrderCalls.isEmpty())
    assert("transient=false error=shopify_graphql codes=ACCESS_DENIED" in lines.single { "Webhook done" in it })
  }

  // ---------- what the delivery log and the Partner Dashboard get to read ----------

  /** Shopify stores the body of every delivery, so a skipped one says why, with the trace id that finds our log line. */
  @Test
  fun `a skipped delivery answers 200 with its reason and the trace id in the body`() {
    // The default graph resolves no service for the shop, so `forShop` answers `Missing`.
    val monolith = FakeMonolithService()
    withDssApp(testDependencies(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<WebhookDeliveryResponse>()
      assert(body.outcome == "skipped")
      assert(body.reason == "no_admin_token")
      assert(body.traceId == r.headers["X-Trace-Id"])
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  @Test
  fun `a mirrored delivery answers 200 with the outcome in the body`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.body<WebhookDeliveryResponse>() == WebhookDeliveryResponse(outcome = "mirrored", traceId = r.headers["X-Trace-Id"]))
    }
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every verified delivery ends in one summary line with the webhook id and the lag`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 503 }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val lines = capturingLogs {
      withDssApp(testDependencies(monolith = monolith, shopify = shopify)) { client ->
        client.post(Paths.webhooksShopify) {
          val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", "acme.myshopify.com")
          header("X-Shopify-Webhook-Id", "delivery-42")
          header("X-Shopify-Triggered-At", "2020-01-01T00:00:00Z")
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(TEST_APP_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
          setBody(body)
        }
      }
    }
    val line = lines.single { "Webhook done" in it }
    assert(line.startsWith("WARN"))
    assert("topic=orders/create webhook_id=delivery-42" in line)
    assert(mdcOf(line)[SHOP_MDC_KEY] == "acme.myshopify.com")
    assert("outcome=failed transient=true error=monolith_503" in line)
    assert("answered=502" in line)
  }

  /**
   * `lag_ms` is how long a delivery sat between Shopify triggering it and this service reading it, which is the field
   * an operator watches when deliveries start arriving late. Measured against the real clock a test can only assert
   * that the field is present, which passes for any number it could possibly hold.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line reports the lag between Shopify's trigger and the receipt`() {
    val receivedAt = Instant.parse("2026-09-16T10:00:00Z")
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val lines = capturingLogs {
      withDssApp(testDependencies(shopify = shopify, webhookClock = Clock.fixed(receivedAt, ZoneOffset.UTC))) { client ->
        client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
          triggeredAt = "2026-09-16T09:59:59.250Z",
        )
      }
    }
    assert("lag_ms=750" in lines.single { "Webhook done" in it })
  }

  /** `took_ms` has to be what the handler measured over the delivery, not a constant that would read the same if it never did. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line reports how long the mirror took, as the handler measured it`() {
    val timeSource = MutableTimeSource()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    // Resolving the shop's service is the one point inside the measured window a test can reach, so time passes there.
    val factory = object : ShopifyGraphqlServiceFactory {
      override suspend fun forShop(shop: ShopDomain): ShopLookup<ShopifyGraphqlService> {
        timeSource += 250.milliseconds
        return ShopLookup.Found(shopify)
      }
    }
    val lines = capturingLogs {
      withDssApp(testDependencies(shopifyGraphqlServiceFactory = factory, webhookTimeSource = timeSource)) { client ->
        client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
        )
      }
    }
    assert("took_ms=250" in lines.single { "Webhook done" in it })
  }

  // ---------- what every line of a delivery carries ----------

  /**
   * The lines between two summaries are what an incident is made of, so the shop, the topic and the delivery id are
   * fields on each of them. The workflow's line comes after the Shopify read suspended, where a value put in the
   * thread-local MDC would be gone or, worse, another delivery's.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every line of a verified delivery carries the shop, the topic and the webhook id in its MDC`() {
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.TokenRejected(401))
      orderForDssDelay = 20.milliseconds
    }
    val lines = capturingLogs {
      withDssApp(testDependencies(shopify = shopify)) { client ->
        val r = client.signedWebhook("orders/create", """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""", webhookId = "wh-1")
        assert(r.status == HttpStatusCode.OK)
      }
    }
    val workflowLine = mdcOf(lines.single { "could not load order" in it })
    assert(workflowLine[SHOP_MDC_KEY] == "acme.myshopify.com")
    assert(workflowLine[TOPIC_MDC_KEY] == "orders/create")
    assert(workflowLine[WEBHOOK_ID_MDC_KEY] == "wh-1")
    val summaryLine = mdcOf(lines.single { "Webhook done" in it })
    assert(summaryLine[SHOP_MDC_KEY] == "acme.myshopify.com")
    assert(summaryLine[TOPIC_MDC_KEY] == "orders/create")
    assert(summaryLine[WEBHOOK_ID_MDC_KEY] == "wh-1")
  }

  /** The shop header is unverified until the HMAC is, so the rejection names no `shop`; the topic and the id are what Shopify's headers said. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a delivery with a bad HMAC logs its rejection with the topic and the webhook id but no shop`() {
    val lines = capturingLogs {
      withDssApp(testDependencies()) { client ->
        val r = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", "acme.myshopify.com")
          header("X-Shopify-Webhook-Id", "wh-1")
          header("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(ByteArray(32)))
          setBody("""{"id":1001}""")
        }
        assert(r.status == HttpStatusCode.Unauthorized)
      }
    }
    val rejection = mdcOf(lines.single { "HMAC mismatch" in it })
    assert(rejection[TOPIC_MDC_KEY] == "orders/create")
    assert(rejection[WEBHOOK_ID_MDC_KEY] == "wh-1")
    assert(SHOP_MDC_KEY !in rejection)
  }

  // ---------- helpers ----------

  /** A webhook signed the way Shopify signs one: base64 HMAC-SHA256 over the exact body bytes. */
  private suspend fun HttpClient.signedWebhook(
    topic: String,
    body: String,
    shopDomain: String? = "acme.myshopify.com",
    webhookId: String? = null,
    triggeredAt: String? = null,
  ): HttpResponse = post(Paths.webhooksShopify) {
    header("X-Shopify-Topic", topic)
    shopDomain?.let { header("X-Shopify-Shop-Domain", it) }
    webhookId?.let { header("X-Shopify-Webhook-Id", it) }
    triggeredAt?.let { header("X-Shopify-Triggered-At", it) }
    header("X-Shopify-Hmac-Sha256", base64HmacSha256(TEST_APP_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
    setBody(body)
  }
}
