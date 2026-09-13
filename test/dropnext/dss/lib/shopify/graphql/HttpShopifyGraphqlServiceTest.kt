package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.config.Config
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.throwingHttpClient
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.ProductsCount
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.enums.CountPrecision
import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.fulfillmentcancelmutation.Fulfillment as CancelledFulfillment
import dropnext.graphql.generated.fulfillmentcancelmutation.FulfillmentCancelPayload
import dropnext.graphql.generated.fulfillmentcancelmutation.UserError as CancelUserError
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError as CreateUserError
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEvent as CreatedFulfillmentEvent
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEventCreatePayload
import dropnext.graphql.generated.fulfillmenteventcreatemutation.UserError as EventUserError

import dropnext.graphql.generated.getproductbyid.Shop as GetProductByIdShop
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscription as ExistingSubscription
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.registerwebhook.UserError as RegisterUserError
import dropnext.graphql.generated.registerwebhook.WebhookSubscription as CreatedSubscription
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import dropnext.graphql.generated.productscount.Count
import dropnext.graphql.generated.shopidentity.Shop as ShopIdentityShop
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.URL

import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance


/**
 * One wire-level test per `ShopifyGraphqlService` method, each with a response that carries data, so
 * the deserialization is exercised rather than skipped over an empty payload. Without them a renamed
 * variable in a `.graphql` file first shows up as a failing *workflow* test, whose message points at
 * the wrong layer.
 */
private const val CALLBACK_URL = "https://dss.test/webhooks/shopify"


@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Stop it from unnecessarily reconstructing per instance.
class HttpShopifyGraphqlServiceTest {

  private val acme = ShopDomain.parse("acme.myshopify.com")!!

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeAll
  fun startServer() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = testHttpClient()
    gqlUrl = URI(shopifyGraphqlUrl(port)).toURL()
    shopify = HttpShopifyGraphqlService(acme, GraphQLKtorClient(gqlUrl, httpClient), ShopifyAdminToken("shpat_test"))
  }

  private lateinit var gqlUrl: URL

  /** A service whose token-rejected hook is observable; the shared [shopify] has none. */
  private fun serviceReporting(onTokenRejected: () -> Unit): ShopifyGraphqlService =
    HttpShopifyGraphqlService(acme, GraphQLKtorClient(gqlUrl, httpClient), ShopifyAdminToken("shpat_test"), onTokenRejected)

  @AfterAll
  fun stopServer() {
    httpClient.close()
    fake.stop()
  }

  @BeforeTest
  fun clearFakeBetweenTests() {
    fake.clear()
  }

  @Test
  fun `every request carries the shop's Admin token`() = runBlocking {
    stubShopIdentity()
    shopify.shopIdentity()
    assert(fake.calls.single().authorization == "shpat_test")
  }

  @Test
  fun `shopIdentity answers the numeric shop id and the canonical domain`() = runBlocking {
    stubShopIdentity(id = "gid://shopify/Shop/9988", domain = "Acme.myshopify.com")
    val result = shopify.shopIdentity()
    assert(result == Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = acme)))
  }

  @Test
  fun `productCount answers the count and whether Shopify stopped at its cap`() = runBlocking {
    fake.stubData(
      "ProductsCount",
      ProductsCount.Result(productsCount = Count(count = 10000, precision = CountPrecision.AT_LEAST)),
      ProductsCount.Result.serializer(),
    )
    assert(shopify.productCount() == Success(ProductCount(count = 10000, isExact = false)))
  }

  @Test
  fun `productCount without a count in the payload is a GraphqlError`() = runBlocking {
    fake.stubData("ProductsCount", ProductsCount.Result(productsCount = null), ProductsCount.Result.serializer())
    assert((shopify.productCount() as Failure).reason is ShopifyError.GraphqlError)
  }

  @Test
  fun `productById pairs the product with the shop's currency`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR), product = sampleProduct()),
      GetProductById.Result.serializer(),
    )
    val result = shopify.productById("gid://shopify/Product/501")
    assert(result is Success)
    val shopProduct = (result as Success).value
    assert(shopProduct?.shopCurrencyCode == "EUR")
    assert(shopProduct?.product?.title == "Sample")
  }

  @Test
  fun `productById answers a successful null when Shopify has no such product`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR), product = null),
      GetProductById.Result.serializer(),
    )
    assert(shopify.productById("gid://shopify/Product/999") == Success(null))
  }

  @Test
  fun `top-level errors are a GraphqlError even when data is present`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"data":{"shop":{"id":"gid://shopify/Shop/1","myshopifyDomain":"acme.myshopify.com"}},"errors":[{"message":"Throttled"}]}""")
    val result = shopify.shopIdentity()
    assert(result is Failure)
    assert((result as Failure).reason == ShopifyError.GraphqlError("Throttled"))
  }

  /** Shopify sends its error codes with a `200`; they are what tells a throttled request from one that will never be allowed. */
  @Test
  fun `top-level errors carry Shopify's error codes, and a throttled request is retryable`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}]}""")
    val error = (shopify.shopIdentity() as Failure).reason
    assert(error == ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")))
    assert(error.isRetryable)
  }

  @Test
  fun `a top-level access denied is not retryable`() = runBlocking {
    fake.stubRaw(
      "ShopIdentity",
      """{"data":null,"errors":[{"message":"Access denied for shop field.","extensions":{"code":"ACCESS_DENIED","documentation":"https://shopify.dev/api/usage/access-scopes"}}]}""",
    )
    val error = (shopify.shopIdentity() as Failure).reason
    assert((error as ShopifyError.GraphqlError).codes == listOf("ACCESS_DENIED"))
    assert(!error.isRetryable)
  }

  @Test
  fun `a response without data is a GraphqlError`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"data":null}""")
    assert((shopify.shopIdentity() as Failure).reason is ShopifyError.GraphqlError)
  }

  /** Schema drift reads the same on every attempt, and the decoder's complaint quotes the body: logged, never retried. */
  @Test
  fun `an unreadable response is Undecodable and not retryable`() = runBlocking {
    fake.stubRaw("ShopIdentity", "{not-json")
    val error = (shopify.shopIdentity() as Failure).reason
    assert(error is ShopifyError.Undecodable)
    assert(!error.isRetryable)
  }

  /** What Shopify says the fulfillment is decides, whatever user error comes along with it. */
  @Test
  fun `cancelFulfillment treats a fulfillment Shopify reports as cancelled as done, user error or not`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = CancelledFulfillment(id = "gid://shopify/Fulfillment/8000", status = FulfillmentStatus.CANCELLED),
          userErrors = listOf(CancelUserError(field = listOf("id"), message = "Fulfillment is already canceled.")),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    assert(shopify.cancelFulfillment("gid://shopify/Fulfillment/8000") == Success(Unit))
  }

  /** A refusal that merely mentions "already" is still a refusal: this fulfillment was delivered, not cancelled. */
  @Test
  fun `cancelFulfillment does not read a user error mentioning already as a cancel`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = null,
          userErrors = listOf(CancelUserError(field = listOf("id"), message = "Fulfillment has already been delivered.")),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    val result = shopify.cancelFulfillment("gid://shopify/Fulfillment/8000")
    assert((result as Failure).reason == ShopifyError.UserError(listOf("Fulfillment has already been delivered.")))
  }

  @Test
  fun `cancelFulfillment without a cancelled fulfillment or a user error is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(fulfillmentCancel = FulfillmentCancelPayload(fulfillment = null, userErrors = emptyList())),
      FulfillmentCancelMutation.Result.serializer(),
    )
    assert((shopify.cancelFulfillment("gid://shopify/Fulfillment/8000") as Failure).reason is ShopifyError.GraphqlError)
  }

  @Test
  fun `cancelFulfillment surfaces any other user error`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = null,
          userErrors = listOf(CancelUserError(field = listOf("id"), message = "Fulfillment cannot be cancelled.")),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    val result = shopify.cancelFulfillment("gid://shopify/Fulfillment/8000")
    assert((result as Failure).reason == ShopifyError.UserError(listOf("Fulfillment cannot be cancelled.")))
  }

  @Test
  fun `cancelFulfillment succeeds on a clean payload`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = CancelledFulfillment(id = "gid://shopify/Fulfillment/8000", status = FulfillmentStatus.CANCELLED),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    assert(shopify.cancelFulfillment("gid://shopify/Fulfillment/8000") == Success(Unit))
  }

  @Test
  fun `orderForDss sends the order gid and decodes the order`() = runBlocking {
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = minimalOrder()), GetOrderForDss.Result.serializer())
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result is Success)
    assert((result as Success).value.legacyResourceId == "1001")
    assert(fake.calls.single().variables.jsonObject["id"]?.jsonPrimitive?.content == "gid://shopify/Order/1001")
  }

  @Test
  fun `orderForDss answers NotFound naming the legacy id when Shopify has no such order`() = runBlocking {
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert((result as Failure).reason == ShopifyError.NotFound("order 1001 not found"))
  }

  @Test
  fun `createFulfillment groups the lines by fulfillment order and answers the new id`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(id = "gid://shopify/Fulfillment/5001", legacyResourceId = "5001"),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(
        FulfillmentLine(fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301", lineItemId = "gid://shopify/FulfillmentOrderLineItem/401", quantity = 1),
        FulfillmentLine(fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301", lineItemId = "gid://shopify/FulfillmentOrderLineItem/402", quantity = 2),
        FulfillmentLine(fulfillmentOrderId = "gid://shopify/FulfillmentOrder/302", lineItemId = "gid://shopify/FulfillmentOrderLineItem/403", quantity = 1),
      ),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = false,
    )
    assert(result == Success(ShopifyFulfillmentId(5001L)))
    // Two fulfillment orders in, two groups out: Shopify rejects a duplicated fulfillmentOrderId.
    val groups = fake.calls.single().variables.jsonObject["lineItemsByFulfillmentOrder"]!!.jsonArray
    assert(groups.size == 2)
    assert(groups[0].jsonObject["fulfillmentOrderLineItems"]!!.jsonArray.size == 2)
  }

  /** Shopify has answered an empty `legacyResourceId` on a fresh fulfillment; the gid carries the same number. */
  @Test
  fun `createFulfillment resolves the id from the gid when legacyResourceId is empty`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(id = "gid://shopify/Fulfillment/7777", legacyResourceId = ""),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = false,
    )
    assert(result == Success(ShopifyFulfillmentId(7777L)))
  }

  /** No user error and no fulfillment is Shopify misbehaving: an upstream failure the monolith retries, not a `400` it drops. */
  @Test
  fun `createFulfillment without a fulfillment or a user error in the payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(fulfillmentCreate = FulfillmentCreatePayload(fulfillment = null, userErrors = emptyList())),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = false,
    )
    assert((result as Failure).reason == ShopifyError.GraphqlError("fulfillment missing in response"))
  }

  /** What a restarting Shopify edge looks like from here: the connection is accepted and reset. */
  @Test
  fun `a reset connection is a Network failure`() = runBlocking {
    FakeFlakyServer().use { unreachable ->
      val deadUrl = URI("${unreachable.baseUrl}/admin/api/${Config.SHOPIFY_API_VERSION}/graphql.json").toURL()
      val deadShopify = HttpShopifyGraphqlService(acme, GraphQLKtorClient(deadUrl, httpClient), ShopifyAdminToken("shpat_test"))
      val result = deadShopify.orderForDss("gid://shopify/Order/1001")
      assert((result as Failure).reason is ShopifyError.Network)
    }
  }

  /** A bug answered as a network failure would be retried by Shopify and the monolith and never reach the 500 log line. */
  @Test
  fun `a failure that is not a transport failure propagates instead of reading as a Network failure`() = runBlocking {
    throwingHttpClient(IllegalStateException("client misconfigured")).use { broken ->
      val brokenShopify = HttpShopifyGraphqlService(acme, GraphQLKtorClient(gqlUrl, broken), ShopifyAdminToken("shpat_test"))
      val thrown = runCatching { brokenShopify.orderForDss("gid://shopify/Order/1001") }.exceptionOrNull()
      assert(thrown is IllegalStateException)
    }
  }

  @Test
  fun `createFulfillment surfaces a payload user error`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = null,
          userErrors = listOf(CreateUserError(field = listOf("tracking"), message = "Tracking number is invalid.")),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "bad", url = null),
      notifyCustomer = false,
    )
    assert((result as Failure).reason == ShopifyError.UserError(listOf("Tracking number is invalid.")))
  }

  @Test
  fun `createFulfillmentEvent sends the input and answers the new event id`() = runBlocking {
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(
          fulfillmentEvent = CreatedFulfillmentEvent(
            id = "gid://shopify/FulfillmentEvent/7001",
            status = FulfillmentEventStatus.IN_TRANSIT,
            message = null,
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createFulfillmentEvent(
      fulfillmentGid = "gid://shopify/Fulfillment/5001",
      status = FulfillmentEventStatus.IN_TRANSIT,
      happenedAt = "2026-04-02T08:30:00Z",
      message = null,
    )
    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    val input = fake.calls.single().variables.jsonObject["fulfillmentEvent"]!!.jsonObject
    assert(input["fulfillmentId"]?.jsonPrimitive?.content == "gid://shopify/Fulfillment/5001")
    assert(input["happenedAt"]?.jsonPrimitive?.content == "2026-04-02T08:30:00Z")
  }

  @Test
  fun `webhookSubscriptions decodes the subscriptions Shopify already has`() = runBlocking {
    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(
        webhookSubscriptions = WebhookSubscriptionConnection(
          nodes = listOf(
            ExistingSubscription(id = "gid://shopify/WebhookSubscription/1", topic = WebhookSubscriptionTopic.ORDERS_CREATE, uri = CALLBACK_URL),
            ExistingSubscription(id = "gid://shopify/WebhookSubscription/2", topic = WebhookSubscriptionTopic.PRODUCTS_UPDATE, uri = CALLBACK_URL),
          ),
        ),
      ),
      GetWebhookSubscriptions.Result.serializer(),
    )
    val result = shopify.webhookSubscriptions(
      topics = listOf(WebhookSubscriptionTopic.ORDERS_CREATE, WebhookSubscriptionTopic.PRODUCTS_UPDATE),
      callbackUrl = CALLBACK_URL,
    )
    assert(result is Success)
    assert((result as Success).value.map { it.topic } == listOf("ORDERS_CREATE", "PRODUCTS_UPDATE"))
    assert(result.value.first().id == "gid://shopify/WebhookSubscription/1")
  }

  @Test
  fun `registerWebhook answers the new subscription gid`() = runBlocking {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = emptyList(),
          webhookSubscription = CreatedSubscription(
            id = "gid://shopify/WebhookSubscription/9",
            topic = WebhookSubscriptionTopic.ORDERS_CREATE,
            includeFields = listOf("id"),
          ),
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )
    val result = shopify.registerWebhook(WebhookSubscriptionTopic.ORDERS_CREATE, CALLBACK_URL, includeFields = listOf("id"))
    assert(result == Success("gid://shopify/WebhookSubscription/9"))
    assert(fake.calls.single().variables.jsonObject["uri"]?.jsonPrimitive?.content == CALLBACK_URL)
  }

  /** Shopify reports a rejected callback URL as a field error, which is worth carrying to the install page. */
  @Test
  fun `registerWebhook prefixes a user error with the field it names`() = runBlocking {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = listOf(RegisterUserError(field = listOf("webhookSubscription", "uri"), message = "is not allowed")),
          webhookSubscription = null,
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )
    val result = shopify.registerWebhook(WebhookSubscriptionTopic.ORDERS_CREATE, CALLBACK_URL, includeFields = null)
    assert((result as Failure).reason == ShopifyError.UserError(listOf("webhookSubscription,uri: is not allowed")))
  }

  // ---------- what a non-200 status from Shopify becomes ----------

  /** The client runs with `expectSuccess`, so a status arrives as an exception; the triage has to name it rather than call it a network problem. */
  @Test
  fun `a 401 from Shopify is a rejected token, not a network failure`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)
    val result = shopify.shopIdentity()
    assert((result as Failure).reason == ShopifyError.TokenRejected(401))
  }

  @Test
  fun `a 401 reports the rejected token before answering, so the store can evict it`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)
    var reported = 0
    val result = serviceReporting { reported++ }.shopIdentity()
    assert((result as Failure).reason == ShopifyError.TokenRejected(401))
    assert(reported == 1)
  }

  @Test
  fun `no other failure reports a rejected token`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"Throttled"}""", HttpStatusCode.TooManyRequests)
    var reported = 0
    serviceReporting { reported++ }.shopIdentity()
    assert(reported == 0)
  }

  @Test
  fun `a 429 from Shopify is an HttpError carrying the status`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"Throttled"}""", HttpStatusCode.TooManyRequests)
    assert((shopify.shopIdentity() as Failure).reason == ShopifyError.HttpError(429))
  }

  @Test
  fun `a 503 from Shopify is an HttpError carrying the status`() = runBlocking {
    fake.stubRaw("ShopIdentity", "<html>maintenance</html>", HttpStatusCode.ServiceUnavailable)
    assert((shopify.shopIdentity() as Failure).reason == ShopifyError.HttpError(503))
  }

  /** Ktor's exception quotes the response body and the URL; both belong to Shopify, not to our logs. */
  @Test
  fun `an HTTP failure's message carries neither the response body nor the url`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"body-that-must-not-be-logged"}""", HttpStatusCode.PaymentRequired)
    val error = (shopify.shopIdentity() as Failure).reason
    assert(error == ShopifyError.HttpError(402))
    assert("body-that-must-not-be-logged" !in error.message)
    assert("graphql.json" !in error.message)
  }

  // ---------- payloads that carry neither an error nor the thing asked for ----------

  @Test
  fun `createFulfillmentEvent surfaces a payload user error`() = runBlocking {
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(
          fulfillmentEvent = null,
          userErrors = listOf(EventUserError(field = listOf("happenedAt"), message = "happenedAt is invalid")),
        ),
      ),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createFulfillmentEvent("gid://shopify/Fulfillment/5001", FulfillmentEventStatus.IN_TRANSIT, "not-a-date", null)
    assert((result as Failure).reason == ShopifyError.UserError(listOf("happenedAt is invalid")))
  }

  /** No user error and no event is Shopify misbehaving: an upstream failure, not a resource we could not find. */
  @Test
  fun `createFulfillmentEvent without an event in the payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(fulfillmentEvent = null, userErrors = emptyList()),
      ),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createFulfillmentEvent("gid://shopify/Fulfillment/5001", FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z", null)
    assert((result as Failure).reason is ShopifyError.GraphqlError)
  }

  @Test
  fun `createFulfillment with a fulfillment whose id carries no number is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(id = "gid://shopify/Fulfillment/", legacyResourceId = ""),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = false,
    )
    assert((result as Failure).reason is ShopifyError.GraphqlError)
  }

  @Test
  fun `registerWebhook without a subscription in the payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(userErrors = emptyList(), webhookSubscription = null),
      ),
      RegisterWebhook.Result.serializer(),
    )
    val result = shopify.registerWebhook(WebhookSubscriptionTopic.ORDERS_CREATE, CALLBACK_URL, includeFields = null)
    assert((result as Failure).reason is ShopifyError.GraphqlError)
  }

  // ---------- helpers ----------

  private fun stubShopIdentity(
id: String = "gid://shopify/Shop/1", domain: String = "acme.myshopify.com") {
    fake.stubData(
      "ShopIdentity",
      ShopIdentity.Result(shop = ShopIdentityShop(id = id, myshopifyDomain = domain)),
      ShopIdentity.Result.serializer(),
    )
  }
}
