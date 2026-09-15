package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.contract.ApiError
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.dssDependencies
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private val monolithToDssApiKey = "d".repeat(32)


/**
 * The diagnostic endpoints. All but the per-shop check are unauthenticated and reachable from
 * anywhere the service is, so what they must *not* say matters as much as what they do: `/api`
 * renders a configuration summary, and a field added there carelessly would publish a secret. The
 * per-shop check drives a monolith lookup and a Shopify query, so it sits behind the bearer auth.
 */
class DiagnosticsHandlersTest {

  @Test
  fun `the index lists every route the service serves`() = withDssApp(deps()) { client ->
    val body = client.get(Paths.index).bodyAsText()
    assert(Paths.health in body)
    assert(Paths.webhooksShopify in body)
    assert(Paths.syncShipmentsWithFulfillments in body)
    assert(Paths.trackingUpdate in body)
    assert(Paths.storesApiKey in body)
  }

  @Test
  fun `health answers ok and names the running version`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.health)
    assert(r.status == HttpStatusCode.OK)
    val body = r.bodyAsText()
    assert("\"status\":\"ok\"" in body)
    assert("\"version\":\"test-version\"" in body)
  }

  /** The same shape with another status and a `503`, so the load balancer's `200` matcher keeps a warming task out of rotation. */
  @Test
  fun `health answers 503 warming_up until the warm-up is done`() {
    val gate = CompletableDeferred<Unit>()
    withDssApp(deps(), warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      val r = client.get(Paths.health)
      assert(r.status == HttpStatusCode.ServiceUnavailable)
      val body = r.body<JsonObject>()
      assert(body["status"]!!.jsonPrimitive.content == "warming_up")
      assert(body["version"]!!.jsonPrimitive.content == "test-version")
      gate.complete(Unit)
    }
  }

  @Test
  fun `the api status summary reports the bind address and the base url`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.api)
    assert(r.status == HttpStatusCode.OK)
    val body = r.bodyAsText()
    assert("\"status\":\"ok\"" in body)
    assert("0.0.0.0:8080" in body)
    assert("https://dss.test" in body)
  }

  /**
   * The one assertion that has to be about absence: every secret the configuration holds, checked
   * against the body of the endpoint most likely to grow a field that leaks one.
   */
  @Test
  fun `the api status summary carries no secret`() {
    val config = testConfig(
      appClientSecret = "shpss_app_secret_value",
      monolithToDssApiKey = monolithToDssApiKey,
      dssToMonolithApiKey = "mono_api_key_value",
    )
    withDssApp(deps(config = config)) { client ->
      val body = client.get(Paths.api).bodyAsText()
      assert("shpss_app_secret_value" !in body)
      assert("mono_api_key_value" !in body)
      assert(monolithToDssApiKey !in body)
    }
  }

  @Test
  fun `api check without the bearer token is a 401 before anything is looked up`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.getStoreCalls.isEmpty())
    }
  }

  @Test
  fun `api check without a shop parameter is a 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.get(Paths.apiCheck)
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.body<ApiError>().error == "Missing shop")
  }

  @Test
  fun `api check with a malformed shop is a 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.get("${Paths.apiCheck}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shop" in r.body<ApiError>().error)
  }

  @Test
  fun `api check answers 401 for a shop with no resolvable token`() =
    withDssApp(deps(shopify = null), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.body<ApiError>().error)
    }

  /** The lookup behind the check got no answer from the monolith: a 502 to retry, not the 401 of a shop without a token. */
  @Test
  fun `api check answers 502 when the token lookup could not reach the monolith`() =
    withDssApp(deps(tokenSourceUnavailable = true), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.body<ApiError>().error == DssError.ShopifyAdminTokenUnavailable.message)
    }

  @Test
  fun `api check answers 200 for a shop whose token resolves`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    withDssApp(deps(tokens = tokens), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.bodyAsText()
      assert("\"shop\":\"acme.myshopify.com\"" in body)
      assert("\"hasTokenMappedForShop\":true" in body)
      // The check reports that a token exists; it never reports the token.
      assert("shpat_test" !in body)
    }
  }

  /** The scan is read-only: the check says what Shopify has per handled topic and registers nothing. */
  @Test
  fun `api check reports each handled topic as active or missing, with stale subscriptions`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(
        listOf(
          WebhookSubscriptionStatus("gid://shopify/WebhookSubscription/1", "PRODUCTS_CREATE", "https://dss.test/webhooks/shopify"),
          WebhookSubscriptionStatus("gid://shopify/WebhookSubscription/2", "ORDERS_CREATE", "https://old.example/webhooks/shopify"),
        ),
      )
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val webhooks = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["webhooks"]!!.jsonArray.map { it.jsonObject }
      assert(webhooks.size == 5)
      val productsCreate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "PRODUCTS_CREATE" }
      assert(productsCreate["status"]!!.jsonPrimitive.content == "active")
      assert(productsCreate["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
      val ordersCreate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "ORDERS_CREATE" }
      assert(ordersCreate["status"]!!.jsonPrimitive.content == "missing")
      assert(ordersCreate["stale"]!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content == "https://old.example/webhooks/shopify")
      assert(shopify.registerWebhookCalls.isEmpty())
    }
  }

  @Test
  fun `api check answers 502 when Shopify cannot list the subscriptions`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.HttpError(503)) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.BadGateway)
    }
  }

  @Test
  fun `the redirect url endpoint answers the url Shopify is configured to call back`() =
    withDssApp(deps()) { client ->
      val r = client.get(Paths.apiRedirectUrl)
      assert(r.status == HttpStatusCode.OK)
      assert(r.bodyAsText() == "https://dss.test/oauth/callback")
    }

  // ---------- helpers ----------

  /** The factory is given the token store, so a check finds a service only for a shop whose token resolves, as in production. */
  private fun deps(
    config: Config = testConfig(monolithToDssApiKey = monolithToDssApiKey),
    tokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
    monolith: FakeMonolithService = FakeMonolithService(),
    shopify: FakeShopifyGraphqlService? = FakeShopifyGraphqlService(),
    tokenSourceUnavailable: Boolean = false,
  ): DssDependencies = dssDependencies(
    config = config,
    monolithService = monolith,
    shopTokens = tokens,
    shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(
      service = shopify,
      tokens = tokens,
      tokenSourceUnavailable = tokenSourceUnavailable,
    ),
  )
}
