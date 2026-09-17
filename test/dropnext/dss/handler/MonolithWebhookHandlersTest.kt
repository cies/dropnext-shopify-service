package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.boot.config.DssMode
import dropnext.dss.contract.ApiError
import dropnext.dss.contract.FetchProductsRequest
import dropnext.dss.contract.FetchProductsResponse
import dropnext.dss.contract.ShopCatalogResponse
import dropnext.dss.contract.ThrottledError
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.TrackingUpdateResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.domain.MAX_PRODUCTS_PER_FETCH
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.FulfillmentTracking
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyCatalogEntry
import dropnext.dss.lib.shopify.graphql.ShopifyCatalogPage
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.slf4j.ROUTE_MDC_KEY
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.mdcOf
import dropnext.dss.testutil.helper.withDssApp
import dropnext.graphql.generated.enums.FulfillmentStatus
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The monolith-facing routes through the production module. What the validators, the auth plugin, the error
 * mapping and the shipment matcher decide is theirs to prove in their own tests; this class proves each route reaches
 * them: one guard, one validator, one shop parse and one upstream failure per route, and the answer the handler builds.
 */
class MonolithWebhookHandlersTest {

  // ---------- internal-secret gate ----------

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() {
    withDssApp(testDependencies()) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
    }
  }

  // ---------- validation ----------

  /** The shop is parsed by the handler, not by a validator, so each route proves its own parse. */
  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.errorMessage() == "Invalid shopify_subdomain: not a valid Shopify domain")
  }

  @Test
  fun `sync-shipments returns 400 for malformed JSON body`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.errorMessage().startsWith("invalid request body: "))
  }

  /** The one case that proves the validator is registered for this body; its rules are `ValidateSyncShipmentsRequestTest`'s. */
  @Test
  fun `sync-shipments returns 400 for duplicate tracking numbers before asking Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(
              validSyncRequest().shipments.single(),
              validSyncRequest().shipments.single(),
            ),
          ),
        )
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.errorMessage() == "duplicate tracking_number in payload: 1Z999")
      assert(fakeShopify.orderForDssCalls.isEmpty())
    }
  }

  // ---------- sync-shipments success + dry-run ----------

  /** Which shipments match which lines is the matcher's; the handler owns the order it loads and the ids it answers. */
  @Test
  fun `sync-shipments creates the fulfillment and answers its id in new_fulfillment_ids`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(minimalOrder())
    fakeShopify.createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<SyncShipmentsWithFulfillmentsResponse>().newFulfillmentIds == listOf(5001L))
      assert(fakeShopify.orderForDssCalls == listOf("gid://shopify/Order/1001"))
      val created = fakeShopify.createFulfillmentCalls.single()
      val expectedLine = FulfillmentLine(
        fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301",
        lineItemId = "gid://shopify/FulfillmentOrderLineItem/401",
        quantity = 1,
      )
      assert(created.lines == listOf(expectedLine))
      assert(created.tracking == FulfillmentTracking(company = "UPS", number = "1Z999", url = null))
    }
  }

  @Test
  fun `sync-shipments returns 400 on dry-run quantity failure before create`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(minimalOrder())
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(
              validSyncRequest().shipments.single().copy(
                lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 99)),
              ),
            ),
          ),
        )
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("exceeds remaining" in r.errorMessage())
      assert(fakeShopify.cancelFulfillmentCalls.isEmpty())
      assert(fakeShopify.createFulfillmentCalls.isEmpty())
    }
  }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when shop has no Admin token`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.errorMessage() == DssError.MissingShopifyAdminToken.message)
  }

  @Test
  fun `tracking-update returns 401 when shop has no Admin token`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.trackingUpdate) {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.errorMessage() == DssError.MissingShopifyAdminToken.message)
  }

  // ---------- tracking-update ----------

  @Test
  fun `tracking-update creates a fulfillment event on the fulfillment with that tracking number`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    fakeShopify.createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7001L))
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<TrackingUpdateResponse>().fulfillmentEventId == 7001L)
      val event = fakeShopify.createFulfillmentEventCalls.single()
      assert(event.fulfillmentGid == "gid://shopify/Fulfillment/8000")
      assert(event.happenedAt == "2026-04-02T08:30:00Z")
    }
  }

  /** A cancelled fulfillment keeps its tracking number; the event belongs to the live one carrying it too. */
  @Test
  fun `tracking-update attaches the event to the live fulfillment when a cancelled one has the same tracking number`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(
      orderWithFulfillments(
        fulfillment(8000L, listOf("1Z999"), status = FulfillmentStatus.CANCELLED),
        fulfillment(8001L, listOf("1Z999")),
      ),
    )
    fakeShopify.createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7002L))
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      assert(fakeShopify.createFulfillmentEventCalls.single().fulfillmentGid == "gid://shopify/Fulfillment/8001")
    }
  }

  @Test
  fun `tracking-update returns 400 for an unsupported status`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(status = "teleported"))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("teleported" in r.errorMessage())
      assert(fakeShopify.createFulfillmentEventCalls.isEmpty())
    }
  }

  @Test
  fun `tracking-update returns 404 when no fulfillment carries that tracking number`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("OTHER")))
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.NotFound)
      assert("1Z999" in r.errorMessage())
    }
  }

  /** The shop is parsed by the handler, not by a validator, so each route proves its own parse. */
  @Test
  fun `tracking-update returns 400 for an invalid shopify_subdomain`() =
    withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(shopifySubdomain = "!!invalid!!"))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.errorMessage() == "Invalid shopify_subdomain: not a valid Shopify domain")
    }

  /**
   * The one case that proves the validator is registered for this body; its rules are `ValidateTrackingUpdateRequestTest`'s.
   * Shopify would refuse the date with a top-level error that reads as its own failure; the request is at fault, so a 400.
   */
  @Test
  fun `tracking-update returns 400 for a happened_at without an offset before asking Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(happenedAt = "2026-04-02T08:30:00"))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.errorMessage() == "happened_at must be an ISO 8601 date and time with an offset, such as 2026-04-02T08:30:00Z")
      assert(fakeShopify.orderForDssCalls.isEmpty())
    }
  }

  // ---------- handlePutStoreApiKey ----------

  @Test
  fun `PUT stores api-key caches the token, forwards it and answers the store id the monolith assigned`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService().apply { putStoreApiKeyStoreId = 42L }
    withDssApp(testDependencies(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_new", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<UpdateStoreApiKeyResponse>().storeId == 42L)
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_new"))
      val forwarded = fake.putStoreApiKeyCalls.single()
      assert(forwarded.shopifySubdomain == "acme")
      assert(forwarded.shopifyShopId == 99L)
      assert(forwarded.apiKey == "shpat_new")
    }
  }

  /** The answer used to be a `200` with `store_id: 0`, which the caller could not tell from a persisted token. */
  @Test
  fun `PUT stores api-key answers 502 and keeps the token cached when the monolith answers a server error`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    withDssApp(testDependencies(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "the monolith answered HTTP 500; the token is cached in memory only")
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_x"))
      assert(fake.putStoreApiKeyCalls.size == 1)
    }
  }

  /** No status to report is its own answer: after a monolith deploy this is the case the caller will see first. */
  @Test
  fun `PUT stores api-key answers 502 and keeps the token cached when the monolith does not answer`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService().apply { putStoreApiKeyTransportFailure = true }
    withDssApp(testDependencies(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "the monolith did not answer; the token is cached in memory only")
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_x"))
      assert(fake.putStoreApiKeyCalls.size == 1)
    }
  }

  @Test
  fun `PUT stores api-key answers 404 when the monolith knows no store for the shop`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 404 }
    withDssApp(testDependencies(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.NotFound)
      assert(r.errorMessage() == "the monolith knows no store for this shop; the token is cached in memory only")
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_x"))
    }
  }

  /** Null is the contract's way of saying "not known"; it used to travel as `0`, which the monolith stored. */
  @Test
  fun `PUT stores api-key forwards a null shopify_shop_id as null`() {
    val fake = FakeMonolithService()
    withDssApp(testDependencies(monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = null))
      }
      assert(r.status == HttpStatusCode.OK)
      assert(fake.putStoreApiKeyCalls.single().shopifyShopId == null)
    }
  }

  /**
   * The one case that proves the validator is registered for this body; its rules are `ValidateUpdateStoreApiKeyRequestTest`'s.
   * The handler caches before it persists, so the validator is what keeps a blank token from evicting a good one.
   */
  @Test
  fun `PUT stores api-key rejects a blank api_key as 400 without touching the cache`() {
    val tokens = InMemoryShopTokenStore(mapOf(ACME_SHOP to ShopifyAdminToken("shpat_old")))
    val fake = FakeMonolithService()
    withDssApp(testDependencies(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "   ", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.errorMessage() == "api_key is required")
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_old"))
      assert(fake.putStoreApiKeyCalls.isEmpty())
    }
  }

  /** The shop is parsed by the handler, not by a validator, so each route proves its own parse. */
  @Test
  fun `PUT stores api-key rejects invalid shopify_subdomain as 400`() {
    val fake = FakeMonolithService()
    withDssApp(testDependencies(monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "!!invalid!!", apiKey = "shpat", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert(r.errorMessage() == "Invalid shopify_subdomain: not a valid Shopify domain")
      assert(fake.putStoreApiKeyCalls.isEmpty())
    }
  }

  @Test
  fun `PUT stores api-key requires internal secret when configured`() {
    val fake = FakeMonolithService()
    withDssApp(testDependencies(monolith = fake)) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
      assert(fake.putStoreApiKeyCalls.isEmpty())
    }
  }

  // ---------- the auth guard on tracking-update ----------

  @Test
  fun `tracking-update returns 401 with the bearer challenge when the internal secret is missing`() = withDssApp(testDependencies()) { client ->
    val r = client.post(Paths.trackingUpdate) {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
  }

  // ---------- a token Shopify no longer accepts ----------

  @Test
  fun `sync-shipments returns 401 naming the rejected token when Shopify refuses it`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenRejected(401).message)
      assert(fakeShopify.createFulfillmentCalls.isEmpty())
    }
  }

  @Test
  fun `tracking-update returns 401 naming the rejected token when Shopify refuses it`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenRejected(401).message)
      assert(fakeShopify.createFulfillmentEventCalls.isEmpty())
    }
  }

  // ---------- upstream failures ----------

  /** The token lookup behind the call got no answer from the monolith: a retry can help, so not the 401 of a shop without a token. */
  @Test
  fun `sync-shipments returns 502 when the token lookup could not reach the monolith`() {
    val factory = FakeShopifyGraphqlServiceFactory(tokenSourceUnavailable = true)
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = factory), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenUnavailable.message)
    }
  }

  @Test
  fun `tracking-update returns 502 when the event creation fails at Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
      createFulfillmentEventResult = Failure(ShopifyError.GraphqlError("throttled"))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "throttled")
      assert(fakeShopify.createFulfillmentEventCalls.size == 1)
    }
  }

  // ---------- products/catalog ----------

  private val budget = ShopifyRateBudget(maximumAvailable = 2000.0, currentlyAvailable = 1880.0, restoreRate = 100.0)

  /** 400 points short of half a 2,000-point bucket, refilling at 100 a second: four seconds. */
  private val lowBudget = budget.copy(currentlyAvailable = 600.0)

  private fun catalogPath(shop: String = "acme") = "${Paths.productsCatalog}?shop=$shop"

  @Test
  fun `products-catalog answers the shop's variants grouped by product, with the counts and no wait for a full bucket`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += Success(
        ShopifyCatalogPage(
          entries = listOf(
            ShopifyCatalogEntry(ShopifyProductId(8), ShopifyVariantId(44)),
            ShopifyCatalogEntry(ShopifyProductId(8), ShopifyVariantId(45)),
          ),
          nextCursor = "c1",
        )
      )
      productVariantIdsPageResultQueue += Success(
        ShopifyCatalogPage(entries = listOf(ShopifyCatalogEntry(ShopifyProductId(9), ShopifyVariantId(46))), nextCursor = null, rateBudget = budget)
      )
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.get(catalogPath())
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<ShopCatalogResponse>()
      assert(body.products.map { it.productId } == listOf(8L, 9L))
      assert(body.products.first().productVariantIds == listOf(44L, 45L))
      assert(body.productCount == 2)
      assert(body.productVariantCount == 3)
      assert(body.nextRequestAfterSeconds == 0)
      assert(fakeShopify.productVariantIdsPageCalls.size == 2)
    }
  }

  @Test
  fun `products-catalog returns 401 with the bearer challenge when the internal secret is missing`() = withDssApp(testDependencies()) { client ->
    val r = client.get(catalogPath())
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
  }

  @Test
  fun `products-catalog returns 400 without a shop`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.get(Paths.productsCatalog)
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.errorMessage() == "Missing shop")
  }

  @Test
  fun `products-catalog returns 400 for a shop that does not parse`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.get(catalogPath(shop = "not%20a%20shop"))
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shop" in r.errorMessage())
  }

  @Test
  fun `products-catalog returns 401 when the shop has no Admin token`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.get(catalogPath())
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.errorMessage() == DssError.MissingShopifyAdminToken.message)
  }

  @Test
  fun `products-catalog returns 502 when the token lookup could not reach the monolith`() {
    val factory = FakeShopifyGraphqlServiceFactory(tokenSourceUnavailable = true)
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = factory), authenticateAsMonolith = true) { client ->
      val r = client.get(catalogPath())
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenUnavailable.message)
    }
  }

  /**
   * A failed walk is an error, never a short catalog: the monolith would soft-delete everything a short one omits.
   * The failure is one no retry is spent on, because the handler walks with the real pause; the retries themselves
   * are the workflow test's to prove.
   */
  @Test
  fun `products-catalog returns 502 and no catalog when the walk fails part way`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += Success(
        ShopifyCatalogPage(entries = listOf(ShopifyCatalogEntry(ShopifyProductId(8), ShopifyVariantId(44))), nextCursor = "c1")
      )
      productVariantIdsPageResult = Failure(ShopifyError.Undecodable("Unexpected JSON token"))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.get(catalogPath())
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "Shopify's answer could not be read")
      assert(fakeShopify.productVariantIdsPageCalls.size == 2)
    }
  }

  // ---------- products/fetch ----------

  private fun fetchRequest(vararg productIds: Long) =
    FetchProductsRequest(shopifySubdomain = "acme", shopifyProductIds = productIds.toList())

  @Test
  fun `products-fetch answers the variant items, the ones Shopify no longer has, and how long to wait`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(
        ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), shopCurrencyCode = "EUR", rateBudget = lowBudget)
      )
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(501L))
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<FetchProductsResponse>()
      assert(body.productVariants.single().productVariantId == 9001L)
      assert(body.productVariants.single().productId == 501L)
      assert(body.missingProductIds.isEmpty())
      // The bucket is under half: the monolith is told how long its refill takes, not what the bucket holds.
      assert(body.nextRequestAfterSeconds == 4)
      assert(fakeShopify.productByIdCalls == listOf("gid://shopify/Product/501"))
    }
  }

  @Test
  fun `products-fetch reports a product Shopify no longer has without failing`() {
    // The fake's default productByIdResult is a successful `null`.
    val fakeShopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(777L))
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<FetchProductsResponse>()
      assert(body.missingProductIds == listOf(777L))
      assert(body.productVariants.isEmpty())
      // Shopify reported no budget, so there is nothing to wait for.
      assert(body.nextRequestAfterSeconds == 0)
    }
  }

  @Test
  fun `products-fetch returns 400 for a batch past the cap before asking Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(*(1L..(MAX_PRODUCTS_PER_FETCH + 1).toLong()).toList().toLongArray()))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("more than $MAX_PRODUCTS_PER_FETCH" in r.errorMessage())
      assert(fakeShopify.productByIdCalls.isEmpty())
    }
  }

  @Test
  fun `products-fetch returns 400 for an invalid shopify_subdomain`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.productsFetch) {
      contentType(ContentType.Application.Json)
      setBody(FetchProductsRequest(shopifySubdomain = "not a shop", shopifyProductIds = listOf(501L)))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.errorMessage())
  }

  @Test
  fun `products-fetch returns 401 with the bearer challenge when the internal secret is missing`() = withDssApp(testDependencies()) { client ->
    val r = client.post(Paths.productsFetch) {
      contentType(ContentType.Application.Json)
      setBody(fetchRequest(501L))
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
  }

  @Test
  fun `products-fetch returns 401 when the shop has no Admin token`() = withDssApp(testDependencies(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.productsFetch) {
      contentType(ContentType.Application.Json)
      setBody(fetchRequest(501L))
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.errorMessage() == DssError.MissingShopifyAdminToken.message)
  }

  @Test
  fun `products-fetch returns 502 when the token lookup could not reach the monolith`() {
    val factory = FakeShopifyGraphqlServiceFactory(tokenSourceUnavailable = true)
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = factory), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(501L))
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenUnavailable.message)
    }
  }

  /** The monolith retries a `5xx`: the same batch, a little later. */
  @Test
  fun `products-fetch returns 502 when Shopify fails, so the monolith retries the batch`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Failure(ShopifyError.Network("connection reset"))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(501L))
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "connection reset")
    }
  }

  /**
   * A throttled shop says how long to wait, in the body the contract declares and in `Retry-After`: the shop's own
   * refill time, here 500 points short of half a bucket refilling at 50 a second.
   */
  @Test
  fun `products-fetch returns 429 with the shop's refill time when Shopify throttled it`() {
    val empty = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 0.0, restoreRate = 50.0)
    val fakeShopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Failure(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"), rateBudget = empty))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.productsFetch) {
        contentType(ContentType.Application.Json)
        setBody(fetchRequest(501L))
      }
      assert(r.status == HttpStatusCode.TooManyRequests)
      assert(r.headers[HttpHeaders.RetryAfter] == "10")
      val body = r.body<ThrottledError>()
      assert(body.retryAfterSeconds == 10)
      assert(body.error == "Shopify throttled this shop")
      assert(body.traceId != null)
    }
  }

  // ---------- an order this service cannot load whole ----------

  /**
   * A `500`, not a `502`: Shopify answered fine. The monolith treats both the same (`integrationErrorFor` retries
   * anything that is not a `4xx`), but the status is the first thing an operator reads, and it should not say the
   * fault was upstream.
   */
  @Test
  fun `sync-shipments returns 500 without creating anything when the order was truncated`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.Truncated("order.fulfillmentOrders", 50))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.InternalServerError)
      assert(fakeShopify.createFulfillmentCalls.isEmpty())
    }
  }

  /** The connection and its ceiling are for the log; a caller is told nothing about the shape of our queries. */
  @Test
  fun `the answer to a truncated order says nothing about which connection ran over`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.Truncated("order.lineItems", 100))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.InternalServerError)
      assert(r.errorMessage() == "internal error")
      assert(fakeShopify.createFulfillmentEventCalls.isEmpty())
    }
  }

  // ---------- the inbound JSON as the monolith may actually send it ----------

  /** The DTO-encoded bodies above go through `AppJson` on both sides; a hand-written body is what pins its leniency. */
  @Test
  fun `sync-shipments accepts a body without the nullable carrier and tracking_url keys`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5003L))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          """{"shopify_subdomain":"acme","shopify_order_id":1001,""" +
            """"shipments":[{"tracking_number":"1Z999","line_items":[{"product_variant_id":101,"quantity":1}]}]}""",
        )
      }
      assert(r.status == HttpStatusCode.OK)
      val tracking = fakeShopify.createFulfillmentCalls.single().tracking
      assert(tracking.company == null)
      assert(tracking.url == null)
    }
  }

  @Test
  fun `sync-shipments ignores keys it does not know`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5004L))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          """{"shopify_subdomain":"acme","shopify_order_id":1001,"sent_at":"2026-09-10T10:00:00Z",""" +
            """"shipments":[{"tracking_number":"1Z999","carrier":null,"tracking_url":null,"weight_grams":250,""" +
            """"line_items":[{"product_variant_id":101,"quantity":1}]}]}""",
        )
      }
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<SyncShipmentsWithFulfillmentsResponse>().newFulfillmentIds == listOf(5004L))
    }
  }

  @Test
  fun `tracking-update accepts a body without the message key`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
      createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7002L))
    }
    withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(
          """{"shopify_subdomain":"acme","shopify_order_id":1001,"tracking_number":"1Z999",""" +
            """"status":"in_transit","happened_at":"2026-04-02T08:30:00Z"}""",
        )
      }
      assert(r.status == HttpStatusCode.OK)
      assert(fakeShopify.createFulfillmentEventCalls.single().message == null)
    }
  }

  // ---------- the shop on every log line ----------

  /** The handler's line comes after Shopify's answer, a suspension away from where the shop was resolved. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `sync-shipments logs its lines with the shop in the MDC`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(ShopifyError.Network("connection reset"))
      orderForDssDelay = 20.milliseconds
    }
    val lines = capturingLogs {
      withDssApp(testDependencies(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
        val r = client.post(Paths.syncShipmentsWithFulfillments) {
          contentType(ContentType.Application.Json)
          setBody(validSyncRequest())
        }
        assert(r.status == HttpStatusCode.BadGateway)
      }
    }
    assert(mdcOf(lines.single { "sync-shipments failed" in it })[SHOP_MDC_KEY] == "acme.myshopify.com")
  }

  /** No shop parsed, no shop named: the call's summary line, logged in `DEV` only, carries the route and no shop. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `sync-shipments with an unparseable shopify_subdomain logs its 400 without a shop`() {
    val lines = capturingLogs {
      withDssApp(testDependencies(config = testConfig(mode = DssMode.DEV)), authenticateAsMonolith = true) { client ->
        val r = client.post(Paths.syncShipmentsWithFulfillments) {
          contentType(ContentType.Application.Json)
          setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
        }
        assert(r.status == HttpStatusCode.BadRequest)
      }
    }
    val summary = mdcOf(lines.single { "POST ${Paths.syncShipmentsWithFulfillments} -> 400" in it })
    assert(summary[ROUTE_MDC_KEY] == Paths.syncShipmentsWithFulfillments)
    assert(SHOP_MDC_KEY !in summary)
  }

  // ---------- helpers ----------

  /**
   * The `error` field of the contract's own [ApiError]. Substring-matching the raw body would
   * keep passing if the envelope changed shape, which is exactly the break the monolith would feel.
   */
  private suspend fun HttpResponse.errorMessage(): String = body<ApiError>().error

  private fun validSyncRequest(): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = listOf(
        shipment(),
      ),
    )

  private fun validTrackingRequest(): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = "1Z999",
      status = "in_transit",
      happenedAt = "2026-04-02T08:30:00Z",
      message = null,
    )
}
