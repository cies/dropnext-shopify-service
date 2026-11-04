package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.contract.ApiError
import dropnext.dss.contract.ObsoleteShopifyWebhookRow
import dropnext.dss.contract.RegisterShopifyWebhooksRequest
import dropnext.dss.contract.RegisterShopifyWebhooksResponse
import dropnext.dss.contract.ShopifyAccessProblemKind
import dropnext.dss.contract.ShopifyAccessScopesStatus
import dropnext.dss.contract.ShopifyConnectionError
import dropnext.dss.contract.ShopifyStoreStatusResponse
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.SHOPIFY_ACCESS_SCOPE_MISSING_CODE
import dropnext.dss.lib.ktor.SHOPIFY_TOKEN_MISSING_CODE
import dropnext.dss.lib.ktor.SHOPIFY_TOKEN_REJECTED_CODE
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_ADMIN_TOKEN
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val idOnlyFields = listOf("id", "admin_graphql_api_id")


/**
 * The per-shop Shopify store endpoints of the contract, both behind the bearer auth: the status reads what Shopify has
 * for a shop and changes nothing, the registration brings the shop to what this version handles.
 */
class ShopifyStoreHandlersTest {

  @Test
  fun `store status without the bearer token is a 401 before anything is looked up`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.getStoreCalls.isEmpty())
      assert(shopify.accessScopeHandlesCalls.isEmpty())
    }
  }

  @Test
  fun `store status without a shopify_subdomain parameter is a 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.get(Paths.storesShopifyStatus)
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.body<ApiError>().error == "Missing shopify_subdomain")
  }

  /** A path or a fragment smuggled into the value cuts it short of `.myshopify.com`, so nothing is looked up. */
  @Test
  fun `store status with a malformed shopify_subdomain is a 400 before anything is looked up`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith), authenticateAsMonolith = true) { client ->
      malformedSubdomains.forEach { malformed ->
        val r = client.getStoreStatus(malformed)
        assert(r.status == HttpStatusCode.BadRequest)
        assert(r.body<ApiError>().error == "Invalid shopify_subdomain: not a valid Shopify domain")
      }
    }
    assert(monolith.getStoreCalls.isEmpty())
  }

  @Test
  fun `store status answers 401 for a shop with no resolvable token`() =
    withDssApp(deps(shopify = null), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Unauthorized)
      val body = r.body<ShopifyConnectionError>()
      assert(body.error == DssError.MissingShopifyAdminToken.message)
      assert(body.code == SHOPIFY_TOKEN_MISSING_CODE)
    }

  /** The lookup behind the check got no answer from the monolith: a 502 to retry, not the 401 of a shop without a token. */
  @Test
  fun `store status answers 502 when the token lookup could not reach the monolith`() =
    withDssApp(deps(tokenSourceUnavailable = true), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.body<ApiError>().error == DssError.ShopifyAdminTokenUnavailable.message)
    }

  @Test
  fun `store status answers 200 for a shop whose token resolves`() {
    val tokens = tokensWithAcme()
    withDssApp(deps(tokens = tokens), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val json = r.body<JsonObject>()
      val body = AppJson.decodeFromJsonElement(ShopifyStoreStatusResponse.serializer(), json)
      assert(body.shopifySubdomain == "acme")
      assert(body.shopDomain == "acme.myshopify.com")
      assert(body.hasTokenMappedForShop)
      assert("has_token_mapped_for_shop" in json)
      // The status reports that a token exists; it never reports the token.
      assert(TEST_ADMIN_TOKEN.value !in json.toString())
    }
  }

  /** The scan is read-only: the check says what Shopify has per handled topic and registers nothing. */
  @Test
  fun `store status reports each handled topic as active or missing, with stale subscriptions`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(
        listOf(
          webhookSubscriptionStatus(1, "PRODUCTS_CREATE", includeFields = idOnlyFields),
          webhookSubscriptionStatus(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify"),
        ),
      )
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val webhooks = r.storeStatus().webhooks
      assert(webhooks.size == 4)
      val productsCreate = webhooks.single { it.topic == "PRODUCTS_CREATE" }
      assert(productsCreate.status == "active")
      assert(productsCreate.id == "gid://shopify/WebhookSubscription/1")
      val ordersCreate = webhooks.single { it.topic == "ORDERS_CREATE" }
      assert(ordersCreate.status == "missing")
      assert(ordersCreate.stale?.single()?.uri == "https://old.example/webhooks/shopify")
      assert(shopify.registerWebhookCalls.isEmpty())
    }
  }

  /** Subscribed at our URL is not enough to be `active`: a subscription sending other payload fields is named with both lists. */
  @Test
  fun `store status reports a subscription at our url with other payload fields as mismatched`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(
        listOf(webhookSubscriptionStatus(3, "ORDERS_CREATE")),
      )
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val ordersCreate = r.storeStatus().webhooks.single { it.topic == "ORDERS_CREATE" }
      assert(ordersCreate.status == "mismatched")
      assert(ordersCreate.id == "gid://shopify/WebhookSubscription/3")
      assert(ordersCreate.includeFields == emptyList<String>())
      assert(ordersCreate.expectedIncludeFields == listOf("id", "admin_graphql_api_id"))
      assert(shopify.registerWebhookCalls.isEmpty())
      assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    }
  }

  @Test
  fun `store status names the filter and the format of a mismatched subscription`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(4, "PRODUCTS_UPDATE", filter = "vendor:Acme", format = "XML")))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val productsUpdate = r.storeStatus().webhooks.single { it.topic == "PRODUCTS_UPDATE" }
      assert(productsUpdate.status == "mismatched")
      assert(productsUpdate.filter == "vendor:Acme")
      assert(productsUpdate.format == "XML")
    }
  }

  /** The check deletes nothing: a subscription an earlier version left at our URL is named, for a registration to remove. */
  @Test
  fun `store status lists a subscription at our url for a topic the service does not handle as obsolete`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(7, "ORDERS_UPDATED", includeFields = idOnlyFields)))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val body = r.storeStatus()
      val obsolete = body.obsoleteWebhooks.single()
      assert(obsolete == ObsoleteShopifyWebhookRow(topic = "ORDERS_UPDATED", id = "gid://shopify/WebhookSubscription/7", status = "obsolete"))
      assert(body.webhooks.none { it.topic == "ORDERS_UPDATED" })
      assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
    }
  }

  /** A shop installed before the scope list changed keeps its old grant, and nothing but the check can say so. */
  @Test
  fun `store status names the access scopes the shop has not granted`() {
    val tokens = tokensWithAcme()
    val granted = listOf("read_products", "write_orders", "write_merchant_managed_fulfillment_orders")
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Success(granted) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val accessScopes = r.storeStatus().accessScopes
      assert(accessScopes.status == "missing")
      assert(accessScopes.missing == listOf("write_third_party_fulfillment_orders", "write_fulfillments"))
      assert(accessScopes.granted == granted)
      assert(shopify.accessScopeHandlesCalls.size == 1)
    }
  }

  @Test
  fun `store status reports a complete grant`() {
    val tokens = tokensWithAcme()
    withDssApp(deps(tokens = tokens), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val accessScopes = r.storeStatus().accessScopes
      assert(accessScopes.status == "complete")
      assert(accessScopes.missing.isEmpty())
      assert(accessScopes.granted == ShopifyAccessScope.entries.map { it.handle })
    }
  }

  /** Not knowing the grant says nothing about the subscriptions, which the check still reports. */
  @Test
  fun `store status still answers the webhook rows when Shopify cannot list the access scopes`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Failure(ShopifyError.Network("connection reset")) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      val body = r.storeStatus()
      assert(body.accessScopes == ShopifyAccessScopesStatus(status = "unknown", granted = emptyList(), missing = emptyList()))
      assert(body.webhooks.size == ShopifyWebhookTopic.known.size)
    }
  }

  /** The answer says `unknown` and nothing more, so what went wrong has to be in the log. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `store status logs why it could not list the access scopes`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Failure(ShopifyError.Network("connection reset")) }
    val lines = capturingLogs {
      withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
        assert(client.getStoreStatus().status == HttpStatusCode.OK)
      }
    }

    val scopeLines = lines.filter { "shopify-store-status: access scopes query failed" in it }
    assert(scopeLines.size == 1)
    assert(scopeLines.single().startsWith("WARN "))
    assert("error=connection reset" in scopeLines.single())
  }

  @Test
  fun `store status answers 502 when Shopify cannot list the subscriptions`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.HttpError(503)) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.BadGateway)
      // The token lookup's own 502 names the monolith; this one has to name the scan.
      assert(r.body<ApiError>().error == "could not list the shop's webhook subscriptions")
    }
  }

  /** The grant, not Shopify, is what failed: the caller asks the merchant to re-authorize rather than trying again. */
  @Test
  fun `store status answers 403 naming the scope when the grant does not reach the subscriptions`() {
    val tokens = tokensWithAcme()
    val denied = ShopifyError.AccessDenied("Access denied for webhookSubscriptions field.", listOf("read_orders"))
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(denied) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Forbidden)
      val body = r.body<ShopifyConnectionError>()
      assert(body.error == DssError.ShopifyAccessScopeMissing(listOf("read_orders")).message)
      assert(body.code == SHOPIFY_ACCESS_SCOPE_MISSING_CODE)
      // As a field too, so the monolith does not have to read the scopes off the message.
      assert(body.requiredAccessScopes == listOf("read_orders"))
    }
  }

  /** `currentAppInstallation` needs no scope, so this is not expected; if it happens, the grant is simply not known. */
  @Test
  fun `store status reports the access scopes as unknown when listing them is denied`() {
    val tokens = tokensWithAcme()
    val denied = ShopifyError.AccessDenied("Access denied for currentAppInstallation field.", emptyList())
    val shopify = FakeShopifyGraphqlService().apply { accessScopeHandlesResult = Failure(denied) }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.OK)
      assert(r.storeStatus().accessScopes.status == "unknown")
      // Only a refused token is reported: a denied scope listing is not news the monolith acts on.
      assert(monolith.postShopifyAccessProblemCalls.isEmpty())
    }
  }

  /**
   * A token Shopify refuses is reported to the monolith as soon as the status sees it, and answered as the reinstall it
   * needs at once: a scan with the same token could only be refused the same way, and the portal would wait for it.
   */
  @Test
  fun `store status reports a rejected token to the monolith and answers 401`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      accessScopeHandlesResult = Failure(ShopifyError.TokenRejected(401, tokenFingerprint = "0123456789abcdef"))
      webhookSubscriptionsResult = Failure(ShopifyError.TokenRejected(401))
    }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Unauthorized)
      val body = r.body<ShopifyConnectionError>()
      assert(body.error == DssError.ShopifyAdminTokenRejected(401).message)
      assert(body.code == SHOPIFY_TOKEN_REJECTED_CODE)
      val reported = monolith.postShopifyAccessProblemCalls.single()
      assert(reported.shopifySubdomain == "acme")
      assert(reported.kind == ShopifyAccessProblemKind.TOKEN_REJECTED)
      assert(reported.tokenFingerprint == "0123456789abcdef")
      assert(reported.observedDuring == "shopify-store-status")
      assert(shopify.webhookSubscriptionsCalls.isEmpty())
    }
  }

  /**
   * The scopes were listed, so nothing was reported yet; the scan's refusal is the news.
   * `observed_at` is what the monolith's race rule compares, so it is the injected clock's time.
   */
  @Test
  fun `store status reports a scan Shopify refuses for a rejected token after the scopes were listed`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.TokenRejected(401)) }
    val monolith = FakeMonolithService()
    val clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC)
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith, clock = clock), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Unauthorized)
    }
    val reported = monolith.postShopifyAccessProblemCalls.single()
    assert(reported.kind == ShopifyAccessProblemKind.TOKEN_REJECTED)
    assert(reported.observedDuring == "shopify-store-status")
    assert(reported.observedAt == "2026-11-04T12:00:00Z")
  }

  @Test
  fun `store status reports a scan Shopify refuses for a missing scope after the scopes were listed`() {
    val tokens = tokensWithAcme()
    val denied = ShopifyError.AccessDenied("Access denied for webhookSubscriptions field.", listOf("read_orders"))
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(denied) }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Forbidden)
    }
    val reported = monolith.postShopifyAccessProblemCalls.single()
    assert(reported.kind == ShopifyAccessProblemKind.ACCESS_SCOPE_MISSING)
    assert(reported.missingAccessScopes == listOf("read_orders"))
  }

  @Test
  fun `store status answers the same when the monolith refuses the report`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      accessScopeHandlesResult = Failure(ShopifyError.TokenRejected(401))
      webhookSubscriptionsResult = Failure(ShopifyError.TokenRejected(401))
    }
    val monolith = FakeMonolithService().apply { postShopifyAccessProblemStatus = 500 }
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.getStoreStatus()
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.postShopifyAccessProblemCalls.size == 1)
    }
  }

  /** The paths before the contract named them are gone, not kept beside the new ones. */
  @Test
  fun `the paths outside the contract are not served`() = withDssApp(deps(tokens = tokensWithAcme()), authenticateAsMonolith = true) { client ->
    assert(client.get("/api/check?shop=acme.myshopify.com").status == HttpStatusCode.NotFound)
    assert(client.post("/api/webhooks/register?shop=acme.myshopify.com").status == HttpStatusCode.NotFound)
  }

  // ---------- registering one shop's webhook subscriptions ----------

  @Test
  fun `webhook registration without the bearer token is a 401 before anything is looked up`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.getStoreCalls.isEmpty())
      assert(shopify.webhookSubscriptionsCalls.isEmpty())
    }
  }

  @Test
  fun `webhook registration with a body that is not JSON is a 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.storesShopifyWebhooksRegister) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.body<ApiError>().error.startsWith("invalid request body: "))
  }

  /** The one case that proves the validator is registered for this body. */
  @Test
  fun `webhook registration with a blank shopify_subdomain is a 400 before anything is looked up`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks("  ")
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.body<ApiError>().error == "shopify_subdomain is required")
      assert(monolith.getStoreCalls.isEmpty())
    }
  }

  @Test
  fun `webhook registration with a malformed shopify_subdomain is a 400 before anything is looked up`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith), authenticateAsMonolith = true) { client ->
      malformedSubdomains.forEach { malformed ->
        val r = client.postRegisterWebhooks(malformed)
        assert(r.status == HttpStatusCode.BadRequest)
        assert(r.body<ApiError>().error == "Invalid shopify_subdomain: not a valid Shopify domain")
      }
    }
    assert(monolith.getStoreCalls.isEmpty())
  }

  @Test
  fun `webhook registration answers 401 for a shop with no resolvable token`() =
    withDssApp(deps(shopify = null), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.Unauthorized)
      val body = r.body<ShopifyConnectionError>()
      assert(body.error == DssError.MissingShopifyAdminToken.message)
      assert(body.code == SHOPIFY_TOKEN_MISSING_CODE)
    }

  /** The lookup behind the registration got no answer from the monolith: a 502 to retry, not the 401 of a shop without a token. */
  @Test
  fun `webhook registration answers 502 when the token lookup could not reach the monolith`() {
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(shopify = shopify, tokenSourceUnavailable = true), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.body<ApiError>().error == DssError.ShopifyAdminTokenUnavailable.message)
      assert(shopify.webhookSubscriptionsCalls.isEmpty())
    }
  }

  /** A shop installed before the product topics went id-only and `orders/updated` was dropped, brought along without the merchant. */
  @Test
  fun `webhook registration registers what is missing, updates what is mismatched and deletes what is obsolete`() {
    val tokens = tokensWithAcme()
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
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.OK)
      val body = r.registration()
      assert(body.shopifySubdomain == "acme")
      assert(body.shopDomain == "acme.myshopify.com")
      val statuses = body.webhooks.associate { row -> row.topic to row.status }
      val expected = mapOf("PRODUCTS_UPDATE" to "updated", "PRODUCTS_CREATE" to "active", "PRODUCTS_DELETE" to "added", "ORDERS_CREATE" to "added")
      assert(statuses == expected)
      val obsolete = body.obsoleteWebhooks.single()
      assert(obsolete.topic == "ORDERS_UPDATED")
      assert(obsolete.status == "deleted")
      val summary = body.summary
      assert(summary.added == 2)
      assert(summary.updated == 1)
      assert(summary.deleted == 1)
      assert(summary.obsolete == 0)
      assert(shopify.registerWebhookCalls.map { it.first.name }.toSet() == setOf("PRODUCTS_DELETE", "ORDERS_CREATE"))
      assert(shopify.updateWebhookSubscriptionCalls.single().includeFields == idOnlyFields)
      assert(shopify.deleteWebhookSubscriptionCalls == listOf("gid://shopify/WebhookSubscription/3"))
    }
  }

  /** A subscription the run moved to our callback URL is named with the address it came from. */
  @Test
  fun `webhook registration names the address a repointed subscription came from`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      webhookSubscriptionsResult = Success(listOf(webhookSubscriptionStatus(5, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")))
      updateWebhookSubscriptionResult = Success(webhookSubscriptionStatus(5, "ORDERS_CREATE", includeFields = idOnlyFields))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.OK)
      val json = r.body<JsonObject>()
      val ordersCreate = AppJson.decodeFromJsonElement(RegisterShopifyWebhooksResponse.serializer(), json).webhooks
        .single { it.topic == "ORDERS_CREATE" }
      assert(ordersCreate.status == "repointed")
      assert(ordersCreate.previousUri == "https://old.example/webhooks/shopify")
      assert("previous_uri" in json.getValue("webhooks").toString())
    }
  }

  /** What Shopify said stays in the log, under the trace id; the caller learns which topics failed. */
  @Test
  fun `webhook registration answers a topic Shopify refuses as a failed row in a 200`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply {
      registerWebhookResult = Failure(ShopifyError.UserError(listOf("topic: scope missing")))
    }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.OK)
      val json = r.body<JsonObject>()
      val body = AppJson.decodeFromJsonElement(RegisterShopifyWebhooksResponse.serializer(), json)
      assert(body.webhooks.all { it.status == "failed" })
      assert(body.summary.failed == 4)
      assert("scope missing" !in json.toString())
    }
  }

  /** A run without the scan would come back with every existing topic refused and nothing deleted; a 502 asks the caller to try again. */
  @Test
  fun `webhook registration answers 502 and changes nothing when Shopify cannot list the subscriptions`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.HttpError(503)) }
    withDssApp(deps(tokens = tokens, shopify = shopify), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.body<ApiError>().error == "could not list the shop's webhook subscriptions")
      assert(shopify.registerWebhookCalls.isEmpty())
      assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
    }
  }

  /** The monolith learns of the missing scope from the report as well as from the `403`; its reason texts make that one notification. */
  @Test
  fun `webhook registration answers 403, changes nothing and reports it when the grant does not reach the subscriptions`() {
    val tokens = tokensWithAcme()
    val denied = ShopifyError.AccessDenied("Access denied for webhookSubscriptions field.", listOf("read_orders"))
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(denied) }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.Forbidden)
      assert(r.body<ApiError>().error == DssError.ShopifyAccessScopeMissing(listOf("read_orders")).message)
      assert(shopify.registerWebhookCalls.isEmpty())
    }
    val reported = monolith.postShopifyAccessProblemCalls.single()
    assert(reported.kind == ShopifyAccessProblemKind.ACCESS_SCOPE_MISSING)
    assert(reported.observedDuring == "register-shopify-webhooks")
  }

  /** A token Shopify refuses is a reinstall, not a scan to retry. */
  @Test
  fun `webhook registration answers 401, changes nothing and reports it when Shopify rejects the token`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.TokenRejected(401)) }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      val r = client.postRegisterWebhooks()
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.body<ApiError>().error == DssError.ShopifyAdminTokenRejected(401).message)
      assert(shopify.registerWebhookCalls.isEmpty())
    }
    val reported = monolith.postShopifyAccessProblemCalls.single()
    assert(reported.kind == ShopifyAccessProblemKind.TOKEN_REJECTED)
    assert(reported.observedDuring == "register-shopify-webhooks")
  }

  @Test
  fun `webhook registration that Shopify cannot scan for another reason reports nothing`() {
    val tokens = tokensWithAcme()
    val shopify = FakeShopifyGraphqlService().apply { webhookSubscriptionsResult = Failure(ShopifyError.HttpError(503)) }
    val monolith = FakeMonolithService()
    withDssApp(deps(tokens = tokens, shopify = shopify, monolith = monolith), authenticateAsMonolith = true) { client ->
      assert(client.postRegisterWebhooks().status == HttpStatusCode.BadGateway)
    }
    assert(monolith.postShopifyAccessProblemCalls.isEmpty())
  }

  // ---------- helpers ----------

  /** Values that are not a shop: no Shopify subdomain at all, and an outside host with a path or a fragment after it. */
  private val malformedSubdomains = listOf("!!invalid!!", "evil.com/", "evil.com#.myshopify.com")

  private suspend fun HttpClient.getStoreStatus(shopifySubdomain: String = "acme"): HttpResponse =
    get("${Paths.storesShopifyStatus}?shopify_subdomain=${shopifySubdomain.encodeURLParameter()}")

  private suspend fun HttpClient.postRegisterWebhooks(shopifySubdomain: String = "acme"): HttpResponse =
    post(Paths.storesShopifyWebhooksRegister) {
      contentType(ContentType.Application.Json)
      setBody(RegisterShopifyWebhooksRequest(shopifySubdomain))
    }

  private suspend fun HttpResponse.storeStatus(): ShopifyStoreStatusResponse = body()

  private suspend fun HttpResponse.registration(): RegisterShopifyWebhooksResponse = body()

  /** A store that already holds the Admin token for [ACME_SHOP], so the check reaches Shopify without the monolith. */
  private fun tokensWithAcme(): InMemoryShopTokenStore = InMemoryShopTokenStore(mapOf(ACME_SHOP to TEST_ADMIN_TOKEN))

  /** The factory is given the token store, so a check finds a service only for a shop whose token resolves, as in production. */
  private fun deps(
    config: Config = testConfig(),
    tokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
    monolith: FakeMonolithService = FakeMonolithService(),
    shopify: FakeShopifyGraphqlService? = FakeShopifyGraphqlService(),
    tokenSourceUnavailable: Boolean = false,
    clock: Clock = Clock.systemUTC(),
  ): DssDependencies = testDependencies(
    config = config,
    monolith = monolith,
    shopify = shopify,
    shopTokens = tokens,
    honorTokenStore = true,
    tokenSourceUnavailable = tokenSourceUnavailable,
    clock = clock,
  )
}
