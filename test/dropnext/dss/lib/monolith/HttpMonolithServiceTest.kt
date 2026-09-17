package dropnext.dss.lib.monolith

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.ProductStatus
import dropnext.dss.contract.ProductVariantItem
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.lib.json.MonolithJson
import dropnext.dss.lib.ktor.createMonolithHttpClient
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.withFakeMonolithServer
import io.ktor.callid.withCallId
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test


class HttpMonolithServiceTest {

  /** A monolith per test, started and stopped by the shared helper: no lifecycle pair and nothing to reset between them. */
  private fun withMonolith(block: suspend (FakeMonolithHttpServer, HttpClient, String) -> Unit) =
    withFakeMonolithServer { server, baseUrl ->
      val httpClient = testHttpClient()
      try {
        runBlocking { block(server, httpClient, baseUrl) }
      } finally {
        httpClient.close()
      }
    }

  private fun service(
    httpClient: HttpClient,
    baseUrl: String,
    apiPrefix: String? = null,
    apiKey: DssToMonolithApiKey? = null,
  ): HttpMonolithService =
    HttpMonolithService(
      httpClient = httpClient,
      baseUrl = baseUrl,
      apiPathPrefix = apiPrefix,
      apiKey = apiKey,
    )

  private fun rejectedOrNull(result: MonolithResult<*>): MonolithError.Rejected? =
    ((result as? Failure)?.reason as? MonolithError.Rejected)

  // ---------- postCreateOrder ----------

  @Test
  fun `postCreateOrder answers Created on 200`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"shopify_order_id":1001}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = service(httpClient, baseUrl).postCreateOrder(req)
    assert(result == Success(CreateOrderOutcome.Created))
    val recorded = server.requests.single()
    assert(recorded.method == "POST")
    assert(recorded.path == "/orders")
    assert(recorded.contentType()?.startsWith("application/json") == true)
  }

  @Test
  fun `postCreateOrder treats 409 as AlreadyExisted (idempotent duplicate)`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.Conflict, """{"error":"Order already exists."}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    assert(service(httpClient, baseUrl).postCreateOrder(req) == Success(CreateOrderOutcome.AlreadyExisted))
  }

  @Test
  fun `postCreateOrder maps non-2xx, non-409 to Rejected with parsed body`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.BadRequest, """{"error":"bad","code":"InvalidOrder","trace_id":"t-1"}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val rejected = rejectedOrNull(service(httpClient, baseUrl).postCreateOrder(req))
    assert(rejected != null)
    assert(rejected!!.status == 400)
    assert(rejected.message == "bad")
    assert(rejected.body.monolithTraceId == "t-1")
  }

  @Test
  fun `postCreateOrder answers Transport on connection failure`() = withMonolith { server, httpClient, baseUrl ->
    // A server that accepts and then resets, rather than a just-freed port that another test's
    // `port = 0` bind could claim between the close and the connect.
    FakeFlakyServer().use { unreachable ->
      val deadService = HttpMonolithService(httpClient, unreachable.baseUrl, null, null)
      val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
      val result = deadService.postCreateOrder(req)
      assert(result is Failure)
      assert(result.failureReason() is MonolithError.Transport)
    }
  }

  /**
   * A monolith that does not answer in time makes the client throw a timeout. Only because that is an `IOException` is
   * it a transport failure, which the caller answers with a `502`, rather than an escaped exception and a `500`.
   */
  @Test
  fun `a call that times out is Transport`() = runBlocking {
    FakeFlakyServer(stallFirstConnections = Int.MAX_VALUE).use { hung ->
      val baseClient = testHttpClient()
      val monolithClient = createMonolithHttpClient(baseClient, requestTimeoutMillis = 300)
      try {
        val result = HttpMonolithService(monolithClient, hung.baseUrl, null, null).getStore("acme")
        assert(result.failureReason() is MonolithError.Transport)
      } finally {
        monolithClient.close()
        baseClient.close()
      }
    }
  }

  // ---------- putStoreApiKey ----------

  @Test
  fun `putStoreApiKey on 200 deserialises the storeId`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":42}""")
    val result =
      service(httpClient, baseUrl).putStoreApiKey(
        UpdateStoreApiKeyRequest(shopifySubdomain = "acme", shopifyShopId = 99L, apiKey = "shpat_x"),
      )
    assert(result == Success(StoreId(42L)))
    val recorded = server.requests.single()
    assert(recorded.method == "PUT")
    assert(recorded.path == "/stores/api-key")
    assert("\"api_key\":\"shpat_x\"" in recorded.body)
  }

  /** A proxy's maintenance page or a drifted contract is an expected failure, not a bug to answer a 500 for. */
  @Test
  fun `putStoreApiKey on a 200 with an unreadable body is Undecodable, not an exception`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, "<html>maintenance</html>")
    val result = service(httpClient, baseUrl).putStoreApiKey(UpdateStoreApiKeyRequest("acme", 99L, "shpat_x"))
    val error = result.failureReason()
    assert(error is MonolithError.Undecodable)
    assert((error as MonolithError.Undecodable).status == 200)
    assert("<html>" !in error.message)
  }

  /**
   * A contract that drifted answers well-formed JSON of the wrong shape, which fails in the decoder rather than the
   * parser. The complaint names what is missing and carries nothing of the body, which may hold what the log must not.
   */
  @Test
  fun `putStoreApiKey on a 200 whose JSON lacks store_id is Undecodable`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"storeId":42,"note":"not-for-the-log"}""")
    val result = service(httpClient, baseUrl).putStoreApiKey(UpdateStoreApiKeyRequest("acme", 99L, "shpat_x"))
    val error = result.failureReason()
    assert(error is MonolithError.Undecodable)
    assert((error as MonolithError.Undecodable).status == 200)
    assert("store_id" in error.detail)
    assert("not-for-the-log" !in error.detail)
  }

  @Test
  fun `every request asks the monolith for JSON`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":42}""")
    service(httpClient, baseUrl).putStoreApiKey(UpdateStoreApiKeyRequest("acme", 99L, "shpat_x"))
    assert(server.requests.single().headers["Accept"] == listOf("application/json"))
  }

  @Test
  fun `putStoreApiKey on 500 is Rejected`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
    val result = service(httpClient, baseUrl).putStoreApiKey(UpdateStoreApiKeyRequest("acme", 99L, "shpat_x"))
    assert(rejectedOrNull(result)?.status == 500)
  }

  // ---------- getStore ----------

  @Test
  fun `getStore on 200 deserialises the StoreResponse`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":"shpat_x"}""")
    val result = service(httpClient, baseUrl).getStore("acme")
    assert(result == Success(MonolithStore(StoreId(1L), ShopifyShopId(99L), ShopifyAdminToken("shpat_x"))))
    val recorded = server.requests.single()
    assert(recorded.method == "GET")
    assert(recorded.path == "/stores")
    assert(recorded.query["shopify_subdomain"] == listOf("acme"))
  }

  @Test
  fun `getStore on 200 without a token answers a store without one`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    assert(service(httpClient, baseUrl).getStore("acme") == Success(MonolithStore(StoreId(1L), ShopifyShopId(99L), null)))
  }

  @Test
  fun `getStore on 404 is a successful null`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.NotFound, """{"error":"missing"}""")
    assert(service(httpClient, baseUrl).getStore("acme") == Success(null))
  }

  @Test
  fun `getStore on 500 is Rejected`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
    assert(rejectedOrNull(service(httpClient, baseUrl).getStore("acme"))?.status == 500)
  }

  // ---------- upsertProductVariants ----------

  @Test
  fun `upsertProductVariants on 200 answers the upserted and deleted counts`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"upserted":3,"deleted":1}""")
    val request =
      UpsertProductVariantsRequest(shopifySubdomain = "acme", productVariants = listOf(sampleVariantItem()))
    val result = service(httpClient, baseUrl).upsertProductVariants(request)
    assert(result == Success(UpsertedProductVariants(upserted = 3, deleted = 1)))
    val recorded = server.requests.single()
    assert(recorded.method == "POST")
    assert(recorded.path == "/product-variants")
  }

  @Test
  fun `upsertProductVariants on 502 is Rejected`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.BadGateway, "")
    val result = service(httpClient, baseUrl).upsertProductVariants(UpsertProductVariantsRequest("acme", listOf(sampleVariantItem())))
    assert(rejectedOrNull(result)?.status == 502)
  }

  // ---------- deleteProductVariants ----------

  @Test
  fun `deleteProductVariants on 200 answers the deleted count`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"deleted":2}""")
    val result =
      service(httpClient, baseUrl).deleteProductVariants(
        DeleteProductVariantsRequest(shopifySubdomain = "acme", productId = 8000000001L),
      )
    assert(result == Success(2))
    val recorded = server.requests.single()
    assert(recorded.method == "DELETE")
    assert(recorded.path == "/product-variants")
    assert("\"product_id\":8000000001" in recorded.body)
  }

  @Test
  fun `deleteProductVariants on 500 is Rejected`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"nope"}""")
    val result = service(httpClient, baseUrl).deleteProductVariants(DeleteProductVariantsRequest("acme", 1L))
    assert(rejectedOrNull(result)?.status == 500)
  }

  // ---------- the bodies as the monolith's parser sees them ----------

  /**
   * The body is the contract: snake_case keys and every nullable field written out, because the
   * monolith's parser treats a missing key and a `null` differently. Pinned key by key, so a
   * regenerated DTO or a changed `MonolithJson` fails here rather than in the monolith.
   */
  @Test
  fun `postCreateOrder sends the order as the contract's snake_case JSON with explicit nulls`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"shopify_order_id":1001}""")
    service(httpClient, baseUrl).postCreateOrder(orderToCreateShopifyOrderRequest("acme", minimalOrder()))

    val sent = MonolithJson.parseToJsonElement(server.requests.single().body).jsonObject
    assert(
      sent.keys == setOf(
        "shopify_subdomain", "shopify_order_id", "name", "financial_status", "fulfillment_status",
        "created_at", "shipping_address", "line_items", "total_as_string", "currency", "email",
      ),
    )
    assert(sent["shopify_order_id"]?.jsonPrimitive?.long == 1001L)
    assert(sent["fulfillment_status"] == JsonNull)
    assert(sent["email"] == JsonNull)
    assert(sent["total_as_string"]?.jsonPrimitive?.content == "39.98")
    val line = sent["line_items"]!!.jsonArray.single().jsonObject
    assert(
      line.keys == setOf(
        "shopify_line_item_id", "product_variant_id", "quantity",
        "snapshot_of_variant_title", "snapshot_of_product_title", "snapshot_of_price_as_string",
        "shopify_product_id",
      ),
    )
    assert(line["shopify_product_id"]?.jsonPrimitive?.content == "501")
    assert(line["snapshot_of_price_as_string"]?.jsonPrimitive?.content == "19.99")
    val address = sent["shipping_address"]!!.jsonObject
    assert(address["first_name"] == JsonNull)
    assert(address["country_code"]?.jsonPrimitive?.content == "")
  }

  @Test
  fun `upsertProductVariants sends every variant field in snake_case with explicit nulls`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"upserted":1,"deleted":0}""")
    val request = UpsertProductVariantsRequest(
      shopifySubdomain = "acme",
      productVariants = listOf(sampleVariantItem()),
      productVariantsComplete = true,
    )
    service(httpClient, baseUrl).upsertProductVariants(request)

    val sent = MonolithJson.parseToJsonElement(server.requests.single().body).jsonObject
    assert(sent.keys == setOf("shopify_subdomain", "product_variants", "product_variants_complete"))
    assert(sent["product_variants_complete"]?.jsonPrimitive?.content == "true")
    val variant = sent["product_variants"]!!.jsonArray.single().jsonObject
    assert(
      variant.keys == setOf(
        "product_variant_id", "product_id", "product_title", "product_description", "product_description_html",
        "product_vendor", "product_type", "product_tags", "product_handle", "product_status", "product_images",
        "product_published_at", "product_created_at", "product_updated_at", "title", "sku", "barcode",
        "price_as_string", "price_currency", "selected_options", "image_url",
      ),
    )
    assert(variant["product_status"]?.jsonPrimitive?.content == "active")
    assert(variant["sku"] == JsonNull)
    assert(variant["product_published_at"] == JsonNull)
    assert(variant["price_as_string"]?.jsonPrimitive?.content == "19.95")
  }

  // ---------- MonolithErrorBody integration ----------


  @Test
  fun `monolith trace_id is parsed and propagated through the rejection`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(
      HttpStatusCode.InternalServerError,
      """{"error":"backend boom","code":"InternalError","trace_id":"mt-9bf3"}""",
    )
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val rejected = rejectedOrNull(service(httpClient, baseUrl).postCreateOrder(req))
    assert(rejected != null)
    assert(rejected!!.status == 500)
    assert(rejected.message == "backend boom")
    assert(rejected.body.monolithTraceId == "mt-9bf3")
    assert(rejected.body.code == "InternalError")
  }

  @Test
  fun `monolith trace_id is parsed for getStore errors too`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(
      HttpStatusCode.InternalServerError,
      """{"error":"unavailable","code":"DbDown","trace_id":"mt-store"}""",
    )
    assert(rejectedOrNull(service(httpClient, baseUrl).getStore("acme"))?.body?.monolithTraceId == "mt-store")
  }

  // ---------- url composition & headers ----------

  @Test
  fun `apiPathPrefix is inserted between baseUrl and path`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    service(httpClient, baseUrl, apiPrefix = "api/v1").getStore("acme")
    assert(server.requests.single().path == "/api/v1/stores")
  }

  @Test
  fun `apiPathPrefix with surrounding slashes is normalised`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    service(httpClient, baseUrl, apiPrefix = "/api/v1/").getStore("acme")
    assert(server.requests.single().path == "/api/v1/stores")
  }

  @Test
  fun `apiKey header behavior — null, blank, and non-blank`() = withMonolith { server, httpClient, baseUrl ->
    val cases = listOf<Pair<DssToMonolithApiKey?, String?>>(
      null to null,
      DssToMonolithApiKey("") to null,
      DssToMonolithApiKey("   ") to null,
      DssToMonolithApiKey("key-abc") to "Bearer key-abc",
    )
    cases.forEach { (apiKey, expected) ->
      server.clear()
      server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
      service(httpClient, baseUrl, apiKey = apiKey).getStore("acme")
      val actual = server.requests.single().authorization()
      assert(actual == expected) {
        "apiKey=$apiKey expected Authorization=$expected but got $actual"
      }
    }
  }

  /**
   * Forwarding the trace id is the production client's job, not the service's: the server's `CallId` plugin
   * puts the id in the coroutine context and `createMonolithHttpClient` reads it from there. These two cases
   * pin the combination the service runs with.
   */
  @Test
  fun `the current trace id is forwarded as X-Trace-Id`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    withProductionClient(httpClient, baseUrl) { service -> withCallId("trace-42") { service.getStore("acme") } }
    assert(server.requests.single().headers["X-Trace-Id"] == listOf("trace-42"))
  }

  @Test
  fun `no X-Trace-Id is sent outside a request`() = withMonolith { server, httpClient, baseUrl ->
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    withProductionClient(httpClient, baseUrl) { service -> service.getStore("acme") }
    assert(server.requests.single().headers["X-Trace-Id"] == null)
  }

  private suspend fun withProductionClient(
    httpClient: HttpClient,
    baseUrl: String,
    block: suspend (HttpMonolithService) -> Unit,
  ) {
    val monolithClient = createMonolithHttpClient(httpClient)
    try {
      block(HttpMonolithService(monolithClient, baseUrl, null, null))
    } finally {
      monolithClient.close()
    }
  }

  private fun sampleVariantItem(): ProductVariantItem =
    ProductVariantItem(
      productVariantId = 1L,
      productId = 10L,
      productTitle = "T-Shirt",
      productDescription = "",
      productDescriptionHtml = "",
      productVendor = "",
      productType = "",
      productTags = emptyList(),
      productHandle = "t-shirt",
      productStatus = ProductStatus.ACTIVE,
      productImages = emptyList(),
      productPublishedAt = null,
      productCreatedAt = "2026-04-01T00:00:00Z",
      productUpdatedAt = "2026-04-01T00:00:00Z",
      title = "Default",
      sku = null,
      barcode = null,
      priceAsString = "19.95",
      priceCurrency = "USD",
      selectedOptions = emptyList(),
      imageUrl = null,
    )
}
