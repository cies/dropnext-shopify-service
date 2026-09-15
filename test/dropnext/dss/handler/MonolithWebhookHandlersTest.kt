package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.boot.config.DssMode
import dropnext.dss.DssDependencies

import dropnext.dss.contract.ApiError
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.TrackingUpdateResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.dssDependencies
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory

import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.slf4j.ROUTE_MDC_KEY
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.mdcOf
import dropnext.dss.testutil.helper.withDssApp
import dropnext.graphql.generated.enums.FulfillmentStatus
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private val defaultInternalSecret = "z".repeat(32)


class MonolithWebhookHandlersTest {

  // ---------- internal-secret gate ----------

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() {
    val secret = "y".repeat(32)
    withDssApp(deps(secret = secret)) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
    }
  }

  /**
   * Verifies the gate passes when the secret matches: the response is still 401, but with the
   * downstream missing-token reason — proving the request reached the handler. If the gate had
   * failed, the body would be empty and carry the bearer challenge instead.
   */
  @Test
  fun `sync-shipments reaches handler when internal secret matches`() {
    val secret = "y".repeat(32)
    withDssApp(deps(secret = secret), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        header("Authorization", "Bearer $secret")
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.errorMessage())
    }
  }

  // ---------- validation ----------

  @Test
  fun `sync-shipments returns 400 for non-positive shopify_order_id`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifyOrderId = 0))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_order_id" in r.errorMessage())
  }

  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.errorMessage())
  }

  @Test
  fun `sync-shipments returns 400 for malformed JSON body`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("invalid request body" in r.errorMessage())
  }

  @Test
  fun `sync-shipments returns 400 for duplicate tracking numbers`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
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
    assert("duplicate tracking_number" in r.errorMessage())
  }

  // ---------- sync-shipments success + dry-run ----------

  @Test
  fun `sync-shipments returns 200 with new_fulfillment_ids`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(minimalOrder())
    fakeShopify.createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(body.newFulfillmentIds == listOf(5001L))
      assert("new_fulfillment_ids" in r.bodyAsText())
      assert(r.headers["X-Trace-Id"] != null)
    }
  }

  @Test
  fun `sync-shipments returns 400 on dry-run quantity failure before create`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(minimalOrder())
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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

  @Test
  fun `sync-shipments returns 200 for partial match with unmatched variant skipped`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(minimalOrder())
    fakeShopify.createFulfillmentResult = Success(ShopifyFulfillmentId(5002L))
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(
              Shipment(
                trackingNumber = "TRK-MIXED",
                carrier = "UPS",
                trackingUrl = null,
                lineItems = listOf(
                  ShipmentLineItem(productVariantId = 101L, quantity = 1),
                  ShipmentLineItem(productVariantId = 999L, quantity = 1),
                ),
              ),
            ),
          ),
        )
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(body.newFulfillmentIds == listOf(5002L))
      assert(fakeShopify.createFulfillmentCalls.size == 1)
    }
  }

  @Test
  fun `sync-shipments cross-FO shipment sends two fulfillment order groups to create`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(diagramCrossFoOrder())
    fakeShopify.createFulfillmentResult = Success(ShopifyFulfillmentId(5100L))
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest().copy(shipments = listOf(diagramCrossFoShipment())))
      }
      assert(r.status == HttpStatusCode.OK)
      val createCall = fakeShopify.createFulfillmentCalls.single()
      val foIds = createCall.lines.map { it.fulfillmentOrderId }.toSet()
      assert(foIds.size == 2)
      assert("gid://shopify/FulfillmentOrder/301" in foIds)
      assert("gid://shopify/FulfillmentOrder/302" in foIds)
      assert(createCall.tracking.number == "TRK-A")
    }
  }

  /** The monolith re-sends the whole payload after a `502`; what already landed must not be created a second time. */
  @Test
  fun `sync-shipments re-send answers 200 with only the new shipment's fulfillment id`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(
      orderWithFoQuantities(remaining = 1, total = 2).copy(fulfillments = listOf(fulfillment(5001L, listOf("TRK-A")))),
    )
    fakeShopify.createFulfillmentResult = Success(ShopifyFulfillmentId(5002L))
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest().copy(shipments = listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B"))))
      }
      assert(r.status == HttpStatusCode.OK)
      assert(r.body<SyncShipmentsWithFulfillmentsResponse>().newFulfillmentIds == listOf(5002L))
      assert(fakeShopify.createFulfillmentCalls.single().tracking.number == "TRK-B")
    }
  }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when shop has no Admin token`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.errorMessage())
  }

  @Test
  fun `tracking-update returns 401 when shop has no Admin token`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.post(Paths.trackingUpdate) {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.errorMessage())
  }

  // ---------- tracking-update ----------

  @Test
  fun `tracking-update creates a fulfillment event on the fulfillment with that tracking number`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    fakeShopify.createFulfillmentEventResult = Success(ShopifyFulfillmentEventId(7001L))
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.NotFound)
      assert("1Z999" in r.errorMessage())
    }
  }

  @Test
  fun `tracking-update returns 400 for an invalid shopify_subdomain`() =
    withDssApp(deps(), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(shopifySubdomain = "!!invalid!!"))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("shopify_subdomain" in r.errorMessage())
    }

  // ---------- handlePutStoreApiKey ----------

  @Test
  fun `PUT stores api-key caches token in the token store`() {
    val tokens = InMemoryShopTokenStore()
    withDssApp(deps(shopTokens = tokens), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_new_token", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<UpdateStoreApiKeyResponse>()
      assert(resp.storeId == 1L) // FakeMonolithService.putStoreApiKeyStoreId default
      assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_new_token"))
    }
  }

  @Test
  fun `PUT stores api-key forwards normalised request to monolith`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService()
    withDssApp(deps(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_new", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.OK)
      assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_new"))
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
    withDssApp(deps(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "the monolith answered HTTP 500; the token is cached in memory only")
      assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_x"))
      assert(fake.putStoreApiKeyCalls.size == 1)
    }
  }

  @Test
  fun `PUT stores api-key answers 404 when the monolith knows no store for the shop`() {
    val tokens = InMemoryShopTokenStore()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 404 }
    withDssApp(deps(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.NotFound)
      assert("knows no store" in r.errorMessage())
      assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_x"))
    }
  }

  /** Null is the contract's way of saying "not known"; it used to travel as `0`, which the monolith stored. */
  @Test
  fun `PUT stores api-key forwards a null shopify_shop_id as null`() {
    val fake = FakeMonolithService()
    withDssApp(deps(monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = null))
      }
      assert(r.status == HttpStatusCode.OK)
      assert(fake.putStoreApiKeyCalls.single().shopifyShopId == null)
    }
  }

  /** The handler caches before it persists, so the validator is what keeps a blank token from evicting a good one. */
  @Test
  fun `PUT stores api-key rejects a blank api_key as 400 without touching the cache`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_old")))
    val fake = FakeMonolithService()
    withDssApp(deps(shopTokens = tokens, monolith = fake), authenticateAsMonolith = true) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "   ", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("api_key is required" in r.errorMessage())
      assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_old"))
      assert(fake.putStoreApiKeyCalls.isEmpty())
    }
  }

  @Test
  fun `PUT stores api-key rejects a zero shopify_shop_id as 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.put(Paths.storesApiKey) {
      contentType(ContentType.Application.Json)
      setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 0L))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_shop_id" in r.errorMessage())
  }

  @Test
  fun `PUT stores api-key rejects invalid shopify_subdomain as 400`() = withDssApp(deps(), authenticateAsMonolith = true) { client ->
    val r = client.put(Paths.storesApiKey) {
      contentType(ContentType.Application.Json)
      setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "!!invalid!!", apiKey = "shpat", shopifyShopId = 99L))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.errorMessage())
  }

  @Test
  fun `PUT stores api-key requires internal secret when configured`() =
    withDssApp(deps(secret = "z".repeat(32))) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 99L))
      }
      assert(r.status == HttpStatusCode.Unauthorized)
    }

  // ---------- the auth guard on tracking-update ----------

  @Test
  fun `tracking-update returns 401 with the bearer challenge when the internal secret is missing`() = withDssApp(deps()) { client ->
    val r = client.post(Paths.trackingUpdate) {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
  }

  @Test
  fun `tracking-update returns 401 when the internal secret is wrong`() = withDssApp(deps()) { client ->
    val r = client.post(Paths.trackingUpdate) {
      header("Authorization", "Bearer ${"w".repeat(32)}")
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("rejected the shop's Admin token (HTTP 401)" in r.errorMessage())
      assert(fakeShopify.createFulfillmentCalls.isEmpty())
    }
  }

  @Test
  fun `tracking-update returns 401 naming the rejected token when Shopify refuses it`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("rejected the shop's Admin token (HTTP 401)" in r.errorMessage())
    }
  }

  // ---------- upstream failures ----------

  /** The token lookup behind the call got no answer from the monolith: a retry can help, so not the 401 of a shop without a token. */
  @Test
  fun `sync-shipments returns 502 when the token lookup could not reach the monolith`() {
    val factory = FakeShopifyGraphqlServiceFactory(tokenSourceUnavailable = true)
    withDssApp(deps(shopifyGraphqlServiceFactory = factory), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == DssError.ShopifyAdminTokenUnavailable.message)
    }
  }

  /** The decoder's complaint quotes Shopify's body, which carries customer data: the monolith learns that it failed, not what it said. */
  @Test
  fun `sync-shipments answers an unreadable Shopify response without the body it quoted`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Failure(
        ShopifyError.Undecodable("Unexpected JSON token at offset 12 at path: \$.data.order\nJSON input: {\"phone\":\"+31 6 1234 5678\"}"),
      )
    }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "Shopify's answer could not be read")
    }
  }

  @Test
  fun `sync-shipments returns 502 when Shopify cannot be reached`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.Network("connection reset")) }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "connection reset")
      assert(fakeShopify.createFulfillmentCalls.isEmpty())
    }
  }

  @Test
  fun `sync-shipments returns 502 when Shopify is throttling`() {
    val fakeShopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.HttpError(429)) }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "Shopify answered HTTP 429")
    }
  }

  @Test
  fun `tracking-update returns 502 when the event creation fails at Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
      createFulfillmentEventResult = Failure(ShopifyError.GraphqlError("throttled"))
    }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.errorMessage() == "throttled")
      assert(fakeShopify.createFulfillmentEventCalls.size == 1)
    }
  }

  // ---------- tracking-update validation, through the plugin ----------

  @Test
  fun `tracking-update returns 400 for a blank tracking_number before asking Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(trackingNumber = "   "))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("tracking_number is required" in r.errorMessage())
      assert(fakeShopify.orderForDssCalls.isEmpty())
    }
  }

  @Test
  fun `tracking-update returns 400 for a non-positive shopify_order_id`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(shopifyOrderId = 0L))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("shopify_order_id" in r.errorMessage())
      assert(fakeShopify.orderForDssCalls.isEmpty())
    }
  }

  /** Shopify would refuse the date with a top-level error that reads as its own failure; the request is at fault, so a 400. */
  @Test
  fun `tracking-update returns 400 for a happened_at without an offset before asking Shopify`() {
    val fakeShopify = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L, trackingNumbers = listOf("1Z999")))
    }
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
      val r = client.post(Paths.trackingUpdate) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest().copy(happenedAt = "2026-04-02T08:30:00"))
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("happened_at" in r.errorMessage())
      assert(fakeShopify.orderForDssCalls.isEmpty())
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
    withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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

  // ---------- helpers ----------

  /**
   * Default graph: an empty token store and a [FakeShopifyGraphqlServiceFactory] that answers `Missing`
   * for every shop — `forShop` therefore short-circuits to "missing token" (401). Tests that
   * need a working service pass their own [shopifyGraphqlServiceFactory] (typically wrapping a
   * [FakeShopifyGraphqlService]).
   */

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
      withDssApp(deps(shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify)), authenticateAsMonolith = true) { client ->
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
      withDssApp(deps(mode = DssMode.DEV), authenticateAsMonolith = true) { client ->
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

  private fun deps(
    secret: String = defaultInternalSecret,
    monolith: MonolithService = FakeMonolithService(),
    shopTokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
    shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = null),
    mode: DssMode = DssMode.PROD,
  ): DssDependencies = dssDependencies(
    config = testConfig(monolithToDssApiKey = secret, mode = mode),
    monolithService = monolith,
    shopTokens = shopTokens,
    shopifyGraphqlServiceFactory = shopifyGraphqlServiceFactory,
  )

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
        Shipment(
          trackingNumber = "1Z999",
          carrier = "UPS",
          trackingUrl = null,
          lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
        ),
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
