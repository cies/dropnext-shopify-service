package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.dssDependencies
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError

import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.TOPIC_MDC_KEY
import dropnext.dss.lib.slf4j.WEBHOOK_ID_MDC_KEY
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
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
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock



private const val WEBHOOK_SECRET = "shpss_test_webhook_secret"


/**
 * The inbound Shopify webhook through the production module: the HMAC gate, the topic routing and
 * the outbound effect. Signing happens here rather than through our own verifier, so a change to
 * `ShopifyHmacVerifierService` cannot make both sides agree on the wrong thing.
 */
class ShopifyWebhookHandlersTest {

  @Test
  fun `rejects request with missing HMAC header as 401`() = withDssApp(deps()) { client ->
    val r = client.post(Paths.webhooksShopify) {
      header("X-Shopify-Topic", "orders/create")
      setBody("""{"id":1}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `bad HMAC on orders_create gates out monolith POST`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
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
      withDssApp(deps()) { client ->
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

  @Test
  fun `accepts valid HMAC for unknown topic and returns 200 without calling monolith`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("shop/update", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `returns 200 without Graphql when shop domain cannot be resolved`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1}""", shopDomain = null)
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `returns 200 without Graphql when no token is available for the shop`() {
    // The default graph resolves no service, so `forShop` answers `Missing`.
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /** The summary line is the one line a skipped delivery leaves: at error level for a shop without a token, and nothing else. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a delivery for a shop without a token leaves one error line and no other`() {
    val lines = capturingLogs {
      withDssApp(deps(monolith = FakeMonolithService())) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      val forwarded = monolith.createOrderCalls.single()
      assert(forwarded.shopifyOrderId == 1001L)
      assert(forwarded.shopifySubdomain == "acme")
      assert(forwarded.lineItems.single().fulfillmentOrderId == 301L)
      assert(shopify.orderForDssCalls.single() == "gid://shopify/Order/1001")
    }
  }

  // ---------- product webhook flows ----------

  @Test
  fun `products_create loads the product and upserts mapped variants to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/delete", """{"id":503}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.single().productId == 503L)
    }
  }

  @Test
  fun `products_delete without a product id in the body skips the monolith call`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.signedWebhook("products/delete", """{"admin_graphql_api_id":"gid://shopify/Product/503"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.isEmpty())
    }
  }

  @Test
  fun `orders_updated does not POST to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/updated",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
      // With sync disabled, the handler also skips the Graphql fetch.
      assert(shopify.orderForDssCalls.isEmpty())
    }
  }

  /**
   * After an uninstall every webhook for the shop hits a `401` at Shopify. The delivery is still
   * acknowledged, and the log has to say what happened, because that line is all an operator gets.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `orders_create with a rejected token is acknowledged and logged as a token problem`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    val lines = capturingLogs {
      withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
        val r = client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
        )
        assert(r.status == HttpStatusCode.OK)
      }
    }
    assert(monolith.createOrderCalls.isEmpty())
    val line = lines.single { "could not load order" in it }
    assert("Shopify rejected the Admin token (HTTP 401)" in line)
    assert("network" !in line.lowercase())
  }

  // ---------- what Shopify is told to do next ----------

  /** A monolith deploy window must not lose orders: a 5xx from it becomes a 502 to Shopify, which redelivers. */
  @Test
  fun `orders_create with the monolith down is answered 502 so Shopify redelivers`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 503 }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/update",
        """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  /** A request the monolith refuses gets the same answer on every redelivery, so Shopify is not asked for one. */
  @Test
  fun `orders_create the monolith refuses with a 4xx is acknowledged with 200`() {
    val monolith = FakeMonolithService().apply { createOrderStatus = 400 }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
    withDssApp(deps(monolith = monolith, tokenSourceUnavailable = true)) { client ->
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
    withDssApp(deps(monolith = monolith, shopify = shopify, mirrorBudget = 50.milliseconds, writeGrace = 5.seconds)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  /** A write already on the wire when the budget ends may still land within Shopify's five seconds; cancelling it would discard a commit. */
  @Test
  fun `orders_create whose monolith write outlives the time budget but not its grace is answered 200`() {
    val monolith = FakeMonolithService().apply { writeDelay = 1_500.milliseconds }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify, mirrorBudget = 1.seconds, writeGrace = 3.seconds)) { client ->
      val r = client.signedWebhook("orders/create", """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<WebhookDeliveryResponse>().outcome == "mirrored")
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  /** The grace bounds the wait: a write that outlives it too is cancelled, and the redelivery finds the order or creates it. */
  @Test
  fun `orders_create whose monolith write outlives its grace too is answered 502`() {
    val monolith = FakeMonolithService().apply { writeDelay = 10.seconds }
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify, mirrorBudget = 300.milliseconds, writeGrace = 300.milliseconds)) { client ->
      val r = client.signedWebhook("orders/create", """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.BadGateway)
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  /** A delete has no read before its write, so the grace covers it from the first call. */
  @Test
  fun `products_delete whose monolith write outlives the time budget but not its grace is answered 200`() {
    val monolith = FakeMonolithService().apply { writeDelay = 1_500.milliseconds }
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService(), mirrorBudget = 1.seconds, writeGrace = 3.seconds)) { client ->
      val r = client.signedWebhook("products/delete", """{"id":503}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.size == 1)
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
    withDssApp(deps(monolith = monolith, shopify = shopify, mirrorSlots = Semaphore(1))) { client ->
      val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
      coroutineScope {
        val first = async { client.signedWebhook("orders/create", body) }
        // The first delivery holds the one slot once it is inside its order load, waiting at the gate.
        while (shopify.orderForDssCalls.isEmpty()) delay(10.milliseconds)

        val second = client.signedWebhook("orders/create", body)
        assert(second.status == HttpStatusCode.BadGateway)
        assert(shopify.orderForDssCalls.size == 1)

        gate.complete(Unit)
        assert(first.await().status == HttpStatusCode.OK)
      }
      assert(monolith.createOrderCalls.size == 1)
    }
  }

  /** A missing scope answers the same on every redelivery; asking for them would only cost the subscription. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `orders_create that Shopify refuses with ACCESS_DENIED is acknowledged with 200 and logged with the code`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.GraphqlError("Access denied for order field.", codes = listOf("ACCESS_DENIED")))
    }
    val lines = capturingLogs {
      withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
        val r = client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
        )
        assert(r.status == HttpStatusCode.OK)
      }
    }
    assert(monolith.createOrderCalls.isEmpty())
    assert("transient=false error=shopify_graphql codes=ACCESS_DENIED" in lines.single { "Webhook done" in it })
    assert("Access denied for order field." in lines.single { "could not load order" in it })
  }

  // ---------- what the delivery log and the Partner Dashboard get to read ----------

  /** Shopify stores the body of every delivery, so a skipped one says why, with the trace id that finds our log line. */
  @Test
  fun `a skipped delivery answers 200 with its reason and the trace id in the body`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<WebhookDeliveryResponse>()
      assert(body.outcome == "skipped")
      assert(body.reason == "no_admin_token")
      assert(body.traceId == r.headers["X-Trace-Id"])
    }
  }

  @Test
  fun `a mirrored delivery answers 200 with the outcome in the body`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
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
      withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
        client.post(Paths.webhooksShopify) {
          val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", "acme.myshopify.com")
          header("X-Shopify-Webhook-Id", "delivery-42")
          header("X-Shopify-Triggered-At", "2020-01-01T00:00:00Z")
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(WEBHOOK_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
          setBody(body)
        }
      }
    }
    val line = lines.single { "Webhook done" in it }
    assert(line.startsWith("WARN"))
    assert("topic=orders/create webhook_id=delivery-42" in line)
    assert(mdcOf(line)[SHOP_MDC_KEY] == "acme.myshopify.com")
    assert("outcome=failed transient=true error=monolith_503" in line)
    assert("lag_ms=" in line)
    assert("answered=502" in line)
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
      withDssApp(deps(shopify = shopify)) { client ->
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
      withDssApp(deps()) { client ->
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
  ): HttpResponse = post(Paths.webhooksShopify) {
    header("X-Shopify-Topic", topic)
    shopDomain?.let { header("X-Shopify-Shop-Domain", it) }
    webhookId?.let { header("X-Shopify-Webhook-Id", it) }
    header("X-Shopify-Hmac-Sha256", base64HmacSha256(WEBHOOK_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
    setBody(body)
  }

  /**
   * Default graph: no Admin token resolvable for any shop, so `forShop` answers `Missing` and the handler
   * logs the "no Admin token" error. Tests that need a working service pass [shopify].
   */
  private fun deps(
    monolith: MonolithService = FakeMonolithService(),
    shopify: FakeShopifyGraphqlService? = null,
    tokenSourceUnavailable: Boolean = false,
    mirrorBudget: Duration = WEBHOOK_MIRROR_BUDGET,
    writeGrace: Duration = WEBHOOK_WRITE_GRACE,
    mirrorSlots: Semaphore = Semaphore(MAX_CONCURRENT_MIRRORS),
  ): DssDependencies = dssDependencies(
    config = testConfig(appClientSecret = WEBHOOK_SECRET),
    monolithService = monolith,
    shopTokens = InMemoryShopTokenStore(),
    shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = shopify, tokenSourceUnavailable = tokenSourceUnavailable),
    webhookMirrorBudget = mirrorBudget,
    webhookWriteGrace = writeGrace,
    webhookMirrorSlots = mirrorSlots,
  )
}
