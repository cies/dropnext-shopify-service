package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.contract.ApiError
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.dssDependencies
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private val monolithToDssApiKey = "d".repeat(32)
private val idOnlyFields = listOf("id", "admin_graphql_api_id")


/**
 * The per-shop webhook subscription endpoints, both behind the bearer auth: the check reads what Shopify has for a shop
 * and changes nothing, the registration brings the shop to what this version handles.
 */
class WebhookSubscriptionHandlersTest {

  @Test
  fun `api check without the bearer token is a 401 before anything is looked up`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.getStoreCalls.isEmpty())
      assert(shopify.accessScopeHandlesCalls.isEmpty())
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
      assert("\"has_token_mapped_for_shop\":true" in body)
      assert("hasTokenMappedForShop" !in body)
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
          webhookSubscriptionStatus(1, "PRODUCTS_CREATE", includeFields = idOnlyFields),
          webhookSubscriptionStatus(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify"),
        ),
      )
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val webhooks = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["webhooks"]!!.jsonArray.map { it.jsonObject }
      assert(webhooks.size == 4)
      val productsCreate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "PRODUCTS_CREATE" }
      assert(productsCreate["status"]!!.jsonPrimitive.content == "active")
      assert(productsCreate["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
      val ordersCreate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "ORDERS_CREATE" }
      assert(ordersCreate["status"]!!.jsonPrimitive.content == "missing")
      assert(ordersCreate["stale"]!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content == "https://old.example/webhooks/shopify")
      assert(shopify.registerWebhookCalls.isEmpty())
    }
  }

  /** Subscribed at our URL is not enough to be `active`: a subscription sending other payload fields is named with both lists. */
  @Test
  fun `api check reports a subscription at our url with other payload fields as mismatched`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(
        listOf(webhookSubscriptionStatus(3, "ORDERS_CREATE")),
      )
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val webhooks = r.body<JsonObject>()["webhooks"]!!.jsonArray.map { it.jsonObject }
      val ordersCreate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "ORDERS_CREATE" }
      assert(ordersCreate["status"]!!.jsonPrimitive.content == "mismatched")
      assert(ordersCreate["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/3")
      assert(ordersCreate["include_fields"]!!.jsonArray.isEmpty())
      assert(ordersCreate["expected_include_fields"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("id", "admin_graphql_api_id"))
      assert(shopify.registerWebhookCalls.isEmpty())
      assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    }
  }

  @Test
  fun `api check names the filter and the format of a mismatched subscription`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(4, "PRODUCTS_UPDATE", filter = "vendor:Acme", format = "XML")))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val webhooks = r.body<JsonObject>()["webhooks"]!!.jsonArray.map { it.jsonObject }
      val productsUpdate = webhooks.single { it["topic"]!!.jsonPrimitive.content == "PRODUCTS_UPDATE" }
      assert(productsUpdate["status"]!!.jsonPrimitive.content == "mismatched")
      assert(productsUpdate["filter"]!!.jsonPrimitive.content == "vendor:Acme")
      assert(productsUpdate["format"]!!.jsonPrimitive.content == "XML")
    }
  }

  /** The check deletes nothing: a subscription an earlier version left at our URL is named, for a registration to remove. */
  @Test
  fun `api check lists a subscription at our url for a topic the service does not handle as obsolete`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(7, "ORDERS_UPDATED", includeFields = idOnlyFields)))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<JsonObject>()
      val obsolete = body["obsolete_webhooks"]!!.jsonArray.single().jsonObject
      assert(obsolete["topic"]!!.jsonPrimitive.content == "ORDERS_UPDATED")
      assert(obsolete["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/7")
      assert(obsolete["status"]!!.jsonPrimitive.content == "obsolete")
      assert(body["webhooks"]!!.jsonArray.none { it.jsonObject["topic"]!!.jsonPrimitive.content == "ORDERS_UPDATED" })
      assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
    }
  }

  /** A shop installed before the scope list changed keeps its old grant, and nothing but the check can say so. */
  @Test
  fun `api check names the access scopes the shop has not granted`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val granted = listOf("read_products", "write_orders", "write_merchant_managed_fulfillment_orders")
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Success(granted) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val accessScopes = r.body<JsonObject>()["access_scopes"]!!.jsonObject
      assert(accessScopes["status"]!!.jsonPrimitive.content == "missing")
      assert(accessScopes["missing"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("write_third_party_fulfillment_orders", "write_fulfillments"))
      assert(accessScopes["granted"]!!.jsonArray.map { it.jsonPrimitive.content } == granted)
      assert(shopify.accessScopeHandlesCalls.size == 1)
    }
  }

  @Test
  fun `api check reports a complete grant`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    withDssApp(deps(tokens = tokens), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val accessScopes = r.body<JsonObject>()["access_scopes"]!!.jsonObject
      assert(accessScopes["status"]!!.jsonPrimitive.content == "complete")
      assert(accessScopes["missing"]!!.jsonArray.isEmpty())
      assert(accessScopes["granted"]!!.jsonArray.map { it.jsonPrimitive.content } == ShopifyAccessScope.entries.map { it.handle })
    }
  }

  /** Not knowing the grant says nothing about the subscriptions, which the check still reports. */
  @Test
  fun `api check still answers the webhook rows when Shopify cannot list the access scopes`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Failure(ShopifyError.Network("connection reset")) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<JsonObject>()
      val accessScopes = body["access_scopes"]!!.jsonObject
      assert(accessScopes["status"]!!.jsonPrimitive.content == "unknown")
      assert(accessScopes["granted"]!!.jsonArray.isEmpty())
      assert(accessScopes["missing"]!!.jsonArray.isEmpty())
      assert(body["webhooks"]!!.jsonArray.size == ShopifyWebhookTopic.known.size)
    }
  }

  /** The answer says `unknown` and nothing more, so what went wrong has to be in the log. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `api check logs why it could not list the access scopes`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Failure(ShopifyError.Network("connection reset")) }
    val lines = capturingLogs {
      withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.OK)
      }
    }

    val scopeLines = lines.filter { "api check: access scopes query failed" in it }
    assert(scopeLines.size == 1)
    assert(scopeLines.single().startsWith("WARN "))
    assert("error=connection reset" in scopeLines.single())
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

  // ---------- registering one shop's webhook subscriptions ----------

  @Test
  fun `webhook registration without the bearer token is a 401 before anything is looked up`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.getStoreCalls.isEmpty())
      assert(shopify.webhookSubscriptionsCalls.isEmpty())
    }
  }

  @Test
  fun `webhook registration without a shop parameter is a 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.apiWebhooksRegister)
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.body<ApiError>().error == "Missing shop")
  }

  @Test
  fun `webhook registration answers 401 for a shop with no resolvable token`() =
    withDssApp(deps(shopify = null), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.body<ApiError>().error)
    }

  /** The lookup behind the registration got no answer from the monolith: a 502 to retry, not the 401 of a shop without a token. */
  @Test
  fun `webhook registration answers 502 when the token lookup could not reach the monolith`() {
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(shopify = shopify, tokenSourceUnavailable = true), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.body<ApiError>().error == DssError.ShopifyAdminTokenUnavailable.message)
      assert(shopify.webhookSubscriptionsCalls.isEmpty())
    }
  }

  /** A shop installed before the product topics went id-only and `orders/updated` was dropped, brought along without the merchant. */
  @Test
  fun `webhook registration registers what is missing, updates what is mismatched and deletes what is obsolete`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(
        listOf(
          webhookSubscriptionStatus(1, "PRODUCTS_CREATE", includeFields = idOnlyFields),
          webhookSubscriptionStatus(2, "PRODUCTS_UPDATE"),
          webhookSubscriptionStatus(3, "ORDERS_UPDATED", includeFields = idOnlyFields),
        ),
      )
      updateWebhookSubscriptionResult = Success(webhookSubscriptionStatus(2, "PRODUCTS_UPDATE", includeFields = idOnlyFields))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<JsonObject>()
      val statuses = body["webhooks"]!!.jsonArray.associate { row ->
        row.jsonObject["topic"]!!.jsonPrimitive.content to row.jsonObject["status"]!!.jsonPrimitive.content
      }
      val expected = mapOf("PRODUCTS_UPDATE" to "updated", "PRODUCTS_CREATE" to "active", "PRODUCTS_DELETE" to "added", "ORDERS_CREATE" to "added")
      assert(statuses == expected)
      val obsolete = body["obsolete_webhooks"]!!.jsonArray.single().jsonObject
      assert(obsolete["topic"]!!.jsonPrimitive.content == "ORDERS_UPDATED")
      assert(obsolete["status"]!!.jsonPrimitive.content == "deleted")
      val summary = body["summary"]!!.jsonObject
      assert(summary["added"]!!.jsonPrimitive.int == 2)
      assert(summary["updated"]!!.jsonPrimitive.int == 1)
      assert(summary["deleted"]!!.jsonPrimitive.int == 1)
      assert(summary["obsolete"]!!.jsonPrimitive.int == 0)
      assert(shopify.registerWebhookCalls.map { it.first.name }.toSet() == setOf("PRODUCTS_DELETE", "ORDERS_CREATE"))
      assert(shopify.updateWebhookSubscriptionCalls.single().includeFields == idOnlyFields)
      assert(shopify.deleteWebhookSubscriptionCalls == listOf("gid://shopify/WebhookSubscription/3"))
    }
  }

  /** A subscription the run moved to our callback URL is named with the address it came from. */
  @Test
  fun `webhook registration names the address a repointed subscription came from`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(5, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")))
      updateWebhookSubscriptionResult = Success(webhookSubscriptionStatus(5, "ORDERS_CREATE", includeFields = idOnlyFields))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val ordersCreate = r.body<JsonObject>()["webhooks"]!!.jsonArray.map { it.jsonObject }
        .single { it["topic"]!!.jsonPrimitive.content == "ORDERS_CREATE" }
      assert(ordersCreate["status"]!!.jsonPrimitive.content == "repointed")
      assert(ordersCreate["previous_uri"]!!.jsonPrimitive.content == "https://old.example/webhooks/shopify")
      assert("previousUri" !in ordersCreate)
    }
  }

  /** What Shopify said stays in the log, under the trace id; the caller learns which topics failed. */
  @Test
  fun `webhook registration answers a topic Shopify refuses as a failed row in a 200`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply {
      registerWebhookResult = Failure(ShopifyError.UserError(listOf("topic: scope missing")))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<JsonObject>()
      assert(body["webhooks"]!!.jsonArray.all { it.jsonObject["status"]!!.jsonPrimitive.content == "failed" })
      assert(body["summary"]!!.jsonObject["failed"]!!.jsonPrimitive.int == 4)
      assert("scope missing" !in body.toString())
    }
  }

  /** A run without the scan would come back with every existing topic refused and nothing deleted; a 502 asks the caller to try again. */
  @Test
  fun `webhook registration answers 502 and changes nothing when Shopify cannot list the subscriptions`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.HttpError(503)) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.post("${Paths.apiWebhooksRegister}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.BadGateway)
      assert(shopify.registerWebhookCalls.isEmpty())
      assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
    }
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
