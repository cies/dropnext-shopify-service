package dropnext.dss.lib.shopify.graphql

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.boot.config.Config
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.json.AppJson
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_ADMIN_TOKEN
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.testutil.helper.shopifyServiceOn
import dropnext.dss.testutil.helper.successValue
import dropnext.dss.testutil.helper.throwingHttpClient
import dropnext.graphql.generated.DeleteWebhookSubscription
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.ProductsCount
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.UpdateWebhookSubscription
import dropnext.graphql.generated.deletewebhooksubscription.UserError as DeleteUserError
import dropnext.graphql.generated.deletewebhooksubscription.WebhookSubscriptionDeletePayload
import dropnext.graphql.generated.enums.CountPrecision
import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionFormat
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
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.Shop as GetProductByIdShop
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscription as ExistingSubscription
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.productscount.Count
import dropnext.graphql.generated.registerwebhook.UserError as RegisterUserError
import dropnext.graphql.generated.registerwebhook.WebhookSubscription as CreatedSubscription
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import dropnext.graphql.generated.shopidentity.Shop as ShopIdentityShop
import dropnext.graphql.generated.updatewebhooksubscription.UserError as UpdateUserError
import dropnext.graphql.generated.updatewebhooksubscription.WebhookSubscription as UpdatedSubscription
import dropnext.graphql.generated.updatewebhooksubscription.WebhookSubscriptionUpdatePayload
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * One wire-level test per `ShopifyGraphqlService` method, each with a response that carries data, so
 * the deserialization is exercised rather than skipped over an empty payload. Without them a renamed
 * variable in a `.graphql` file first shows up as a failing *workflow* test, whose message points at
 * the wrong layer.
 */
private const val CALLBACK_URL = "https://dss.test/webhooks/shopify"


@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Stop it from unnecessarily reconstructing per instance.
class HttpShopifyGraphqlServiceTest {

  // One server for the class, as `val`s rather than a `lateinit` trio filled in by a `@BeforeAll`, and closed by JUnit
  // once the class is done (`@AutoClose` on a per-class instance), so there is no teardown method to forget. The
  // `@BeforeEach` below clears the fake, so no test has to remember to. The client rewrites the shop's real Admin URL
  // onto the fake, which is the URL production would have built.
  @AutoClose("stop")
  private val fake = FakeShopifyGraphqlServer()

  @AutoClose
  private val shopifyClient = shopifyRewritingHttpClient(fake.start())

  private val shopify: ShopifyGraphqlService = shopifyServiceOn(shopifyClient)

  /** A service whose token-rejected hook is observable; the shared [shopify] has none. */
  private fun serviceReporting(onTokenRejected: () -> Unit): ShopifyGraphqlService =
    shopifyServiceOn(shopifyClient, onTokenRejected = onTokenRejected)

  @BeforeEach
  fun clearFakeBetweenTests() {
    fake.clear()
  }

  @Test
  fun `every request carries the shop's Admin token`() = runBlocking {
    stubShopIdentity()
    shopify.shopIdentity()
    assert(fake.calls.single().authorization == TEST_ADMIN_TOKEN.value)
  }

  @Test
  fun `shopIdentity answers the numeric shop id and the canonical domain`() = runBlocking {
    stubShopIdentity(id = "gid://shopify/Shop/9988", domain = "Acme.myshopify.com")
    val result = shopify.shopIdentity()
    assert(result == Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = ACME_SHOP)))
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
    assert(shopify.productCount().failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `accessScopeHandles answers the handles Shopify reports`() = runBlocking {
    fake.stubRaw(
      "CurrentAppInstallationAccessScopes",
      """{"data":{"currentAppInstallation":{"accessScopes":[{"handle":"read_orders"},{"handle":"write_fulfillments"}]}}}""",
    )
    assert(shopify.accessScopeHandles() == Success(listOf("read_orders", "write_fulfillments")))
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
    val shopProduct = result.successValue()
    assert(shopProduct?.shopCurrencyCode == "EUR")
    assert(shopProduct?.product?.title == "Sample")
  }

  /**
   * Why `GetProductById` keeps its `Video`, `ExternalVideo` and `Model3d` fragments although nothing reads them. The
   * generated client decodes a `Media` node into the class generated for its `__typename`, and a `__typename` without
   * one fails the whole response. The generated `DefaultMediaImplementation` would take those nodes, but only once it is
   * registered with the client's serializer, which this service does not do. Without the fragments this test fails, and
   * in production a product with a video would never reach the monolith.
   */
  @Test
  fun `productById decodes a product whose media holds a video`() = runBlocking {
    fake.stubRaw(
      "GetProductById",
      """
      {"data":{"shop":{"currencyCode":"EUR"},"product":{
        "legacyResourceId":"501","title":"Sample","description":"","descriptionHtml":"","vendor":"","productType":"",
        "tags":[],"handle":"sample","status":"ACTIVE","publishedAt":null,
        "createdAt":"2026-04-01T00:00:00Z","updatedAt":"2026-04-01T00:00:00Z",
        "media":{"pageInfo":{"hasNextPage":false},"edges":[
          {"node":{"__typename":"Video","id":"gid://shopify/Video/1"}},
          {"node":{"__typename":"MediaImage","image":{"url":"https://cdn.example/cover.jpg"}}}
        ]},
        "variants":{"pageInfo":{"hasNextPage":false},"edges":[]}
      }}}
      """.trimIndent(),
    )
    val result = shopify.productById("gid://shopify/Product/501")
    val media = result.successValue()!!.product.media.edges.map { it.node }
    assert(media.size == 2)
    assert(media.first() !is MediaImage)
    assert((media.last() as MediaImage).image?.url == "https://cdn.example/cover.jpg")
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

  /**
   * The page sizes live in one place and reach Shopify as variables, so this pins the whole point of that: the number
   * the query asked for is the number a pager's truncation message counts in. A first page sends no cursor, which
   * Shopify reads as "from the start".
   */
  @Test
  fun `productById asks for the page sizes it counts in and no cursor for the first page`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(
        shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR),
        product = sampleProduct(variantId = "601"),
      ),
      GetProductById.Result.serializer(),
    )
    shopify.productById("gid://shopify/Product/501")

    val variables = fake.calls.single().variables.jsonObject
    assert(variables["mediaFirst"]?.jsonPrimitive?.int == 20)
    assert(variables["variantsFirst"]?.jsonPrimitive?.int == PRODUCT_VARIANTS_PAGE_SIZE)
    assert("variantsAfter" !in variables)
  }

  @Test
  fun `productById sends the cursor of a later page`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(
        shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR),
        product = sampleProduct(variantId = "701"),
      ),
      GetProductById.Result.serializer(),
    )
    val page = shopify.productById("gid://shopify/Product/501", variantsAfter = "cursor-1").successValue()

    val variables = fake.calls.single().variables.jsonObject
    assert(variables["variantsAfter"]?.jsonPrimitive?.content == "cursor-1")
    assert(page?.product?.variants?.edges?.single()?.node?.legacyResourceId == "701")
    assert(page?.nextVariantsCursor == null)
  }

  @Test
  fun `orderForDss asks for every one of its page sizes`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder()),
      GetOrderForDss.Result.serializer(),
    )
    shopify.orderForDss("gid://shopify/Order/1001")

    val variables = fake.calls.single().variables.jsonObject
    assert(variables["lineItemsFirst"]?.jsonPrimitive?.int == 100)
    assert(variables["fulfillmentOrdersFirst"]?.jsonPrimitive?.int == 50)
    assert(variables["fulfillmentOrderLineItemsFirst"]?.jsonPrimitive?.int == 100)
    // Not a connection, so it cannot report truncation: it sits at Shopify's per-page ceiling.
    assert(variables["fulfillmentsFirst"]?.jsonPrimitive?.int == 250)
  }

  /** A product with more variants than a page is not a failure here: the answer says where the next page starts. */
  @Test
  fun `productById answers a page of variants and the cursor to the next`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(
        shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR),
        product = sampleProduct(variantId = "601", variantsCursor = "cursor-1"),
      ),
      GetProductById.Result.serializer(),
    )
    val page = shopify.productById("gid://shopify/Product/501").successValue()

    assert(page?.nextVariantsCursor == "cursor-1")
    assert(page?.product?.variants?.edges?.single()?.node?.legacyResourceId == "601")
  }

  /** An image count must not stop a variant sync: the product still reaches the monolith with the images it has. */
  @Test
  fun `productById answers a product whose media alone was truncated`() = runBlocking {
    fake.stubData(
      "GetProductById",
      GetProductById.Result(
        shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR),
        product = sampleProduct(variantId = "601", moreMedia = true),
      ),
      GetProductById.Result.serializer(),
    )
    val result = shopify.productById("gid://shopify/Product/501")
    assert(result is Success)
    assert(result.successValue()?.product?.legacyResourceId == "501")
  }

  @Test
  fun `orderForDss refuses an order whose line items Shopify has more of`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder(moreLineItems = true)),
      GetOrderForDss.Result.serializer(),
    )
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result is Failure)
    assert(result.failureReason() == ShopifyError.Truncated("order.lineItems", 100))
  }

  @Test
  fun `orderForDss refuses an order whose fulfillment orders Shopify has more of`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder(moreFulfillmentOrders = true)),
      GetOrderForDss.Result.serializer(),
    )
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result is Failure)
    assert(result.failureReason() == ShopifyError.Truncated("order.fulfillmentOrders", 50))
  }

  /** The name says which level ran over, so an operator reads the nesting off the error. */
  @Test
  fun `orderForDss names the nested connection when one fulfillment order has more lines`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder(moreFoLineItems = true)),
      GetOrderForDss.Result.serializer(),
    )
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result is Failure)
    assert(result.failureReason() == ShopifyError.Truncated("fulfillmentOrders.lineItems", 100))
  }

  @Test
  fun `productVariantIdsPage reads the variants with their products and the next cursor`() = runBlocking {
    fake.stubRaw(
      "GetProductVariantIdsPage",
      """
      {"data":{"productVariants":{
        "pageInfo":{"hasNextPage":true,"endCursor":"eyJsYXN0X2lkIjo0NH0="},
        "nodes":[
          {"legacyResourceId":"44","product":{"legacyResourceId":"8"}},
          {"legacyResourceId":"45","product":{"legacyResourceId":"8"}}
        ]
      }}}
      """.trimIndent(),
    )
    val page = shopify.productVariantIdsPage(first = 250, after = "previous").successValue()

    assert(page.entries.map { it.productVariantId.value } == listOf(44L, 45L))
    assert(page.entries.all { it.productId.value == 8L })
    assert(page.nextCursor == "eyJsYXN0X2lkIjo0NH0=")
    val variables = fake.calls.single().variables.jsonObject
    assert(variables["first"]?.jsonPrimitive?.int == 250)
    assert(variables["after"]?.jsonPrimitive?.content == "previous")
  }

  /** Shopify keeps a cursor on the last page too; answering it would make the walk ask for a page that is not there. */
  @Test
  fun `productVariantIdsPage answers no cursor on the last page`() = runBlocking {
    fake.stubRaw(
      "GetProductVariantIdsPage",
      """{"data":{"productVariants":{"pageInfo":{"hasNextPage":false,"endCursor":"last"},"nodes":[]}}}""",
    )
    val page = shopify.productVariantIdsPage(first = 250, after = null).successValue()

    assert(page.nextCursor == null)
    assert(page.entries.isEmpty())
    // The client leaves out a variable whose value is null, which Shopify reads as "from the start".
    assert("after" !in fake.calls.single().variables.jsonObject)
  }

  @Test
  fun `productVariantIdsPage leaves out a variant whose id does not parse`() = runBlocking {
    fake.stubRaw(
      "GetProductVariantIdsPage",
      """
      {"data":{"productVariants":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[
        {"legacyResourceId":"not-a-number","product":{"legacyResourceId":"8"}},
        {"legacyResourceId":"45","product":{"legacyResourceId":"8"}}
      ]}}}
      """.trimIndent(),
    )
    val page = shopify.productVariantIdsPage(first = 250, after = null).successValue()

    assert(page.entries.map { it.productVariantId.value } == listOf(45L))
  }

  @Test
  fun `the rate budget is read off the response envelope`() = runBlocking {
    fake.stubRaw(
      "GetProductVariantIdsPage",
      """
      {"data":{"productVariants":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[]}},
       "extensions":{"cost":{"requestedQueryCost":252,"actualQueryCost":12,
         "throttleStatus":{"maximumAvailable":2000.0,"currentlyAvailable":1988,"restoreRate":100.0}}}}
      """.trimIndent(),
    )
    val page = shopify.productVariantIdsPage(first = 250, after = null).successValue()

    // `currentlyAvailable` arrives as an integer here; the budget reads either.
    assert(page.rateBudget == ShopifyRateBudget(maximumAvailable = 2000.0, currentlyAvailable = 1988.0, restoreRate = 100.0))
  }

  @Test
  fun `productById carries the rate budget Shopify reported with the product`() = runBlocking {
    fake.stubRaw(
      "GetProductById",
      """
      {"data":{"shop":{"currencyCode":"EUR"},"product":{
        "legacyResourceId":"501","title":"Sample","description":"","descriptionHtml":"","vendor":"","productType":"",
        "tags":[],"handle":"sample","status":"ACTIVE","publishedAt":null,
        "createdAt":"2026-04-01T00:00:00Z","updatedAt":"2026-04-01T00:00:00Z",
        "media":{"pageInfo":{"hasNextPage":false},"edges":[]},
        "variants":{"pageInfo":{"hasNextPage":false},"edges":[]}
      }},
       "extensions":{"cost":{"throttleStatus":{"maximumAvailable":1000.0,"currentlyAvailable":700.0,"restoreRate":50.0}}}}
      """.trimIndent(),
    )
    val product = shopify.productById("gid://shopify/Product/501").successValue()

    assert(product?.rateBudget == ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 700.0, restoreRate = 50.0))
  }

  /**
   * The cost is reported on every answer that carries one, a refusal included, under the operation's own name: the
   * warning is only useful if it says which query to change.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the cost Shopify reports is warned about by operation, on a refusal too`() {
    val product = """
      {"data":{"shop":{"currencyCode":"EUR"},"product":null},
       "extensions":{"cost":{"requestedQueryCost":812,"actualQueryCost":3}}}
    """.trimIndent()
    val throttled = """
      {"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}],
       "extensions":{"cost":{"requestedQueryCost":900}}}
    """.trimIndent()
    fake.stubRaw("GetProductById", product)
    fake.stubRaw("ShopIdentity", throttled)
    val service = shopifyServiceOn(shopifyClient, costReporter = ShopifyQueryCostReporter())

    val lines = capturingLogs {
      runBlocking {
        service.productById("gid://shopify/Product/501")
        service.shopIdentity()
      }
    }

    val warnings = lines.filter { it.startsWith("WARN Shopify query cost is near the cap") }
    assert(warnings.size == 2)
    assert("operation=GetProductById requested=812 actual=3" in warnings[0])
    assert("operation=ShopIdentity requested=900 actual=null" in warnings[1])
  }

  /** A statistic must never fail a call: an envelope without the block, or with a block of the wrong shape, is no budget. */
  @Test
  fun `a missing or malformed cost block is no budget and still a success`() = runBlocking {
    val body = { extensions: String ->
      """{"data":{"productVariants":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[]}}$extensions}"""
    }
    listOf(
      "",
      ""","extensions":{}""",
      ""","extensions":{"cost":{"requestedQueryCost":1}}""",
      ""","extensions":{"cost":"not a map"}""",
      ""","extensions":{"cost":{"throttleStatus":{"maximumAvailable":"lots","currentlyAvailable":1,"restoreRate":1}}}""",
    ).forEach { extensions ->
      fake.clear()
      fake.stubRaw("GetProductVariantIdsPage", body(extensions))
      val result = shopify.productVariantIdsPage(first = 250, after = null)
      assert(result is Success)
      assert(result.successValue().rateBudget == null)
    }
  }

  /** Shopify throttles with a `200` and an error, and still reports the bucket: that is how long a retry has to wait. */
  @Test
  fun `a throttled answer carries the rate budget Shopify reported with it`() = runBlocking {
    fake.stubRaw(
      "GetProductVariantIdsPage",
      """
      {"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}],
       "extensions":{"cost":{"requestedQueryCost":252,
         "throttleStatus":{"maximumAvailable":1000.0,"currentlyAvailable":12.0,"restoreRate":50.0}}}}
      """.trimIndent(),
    )
    val result = shopify.productVariantIdsPage(first = 250, after = null)

    val error = result.failureReason() as ShopifyError.GraphqlError
    assert(error.codes == listOf("THROTTLED"))
    assert(error.rateBudget == ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 12.0, restoreRate = 50.0))
  }

  @Test
  fun `top-level errors are a GraphqlError even when data is present`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"data":{"shop":{"id":"gid://shopify/Shop/1","myshopifyDomain":"acme.myshopify.com"}},"errors":[{"message":"Throttled"}]}""")
    val result = shopify.shopIdentity()
    assert(result is Failure)
    assert(result.failureReason() == ShopifyError.GraphqlError("Throttled"))
  }

  /** Shopify sends its error codes with a `200`; they are what tells a throttled request from one that will never be allowed. */
  @Test
  fun `top-level errors carry Shopify's error codes, and a throttled request is retryable`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}]}""")
    val error = shopify.shopIdentity().failureReason()
    assert(error == ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")))
    assert(error.isRetryable)
  }

  @Test
  fun `a top-level access denied is not retryable`() = runBlocking {
    fake.stubRaw(
      "ShopIdentity",
      """{"data":null,"errors":[{"message":"Access denied for shop field.","extensions":{"code":"ACCESS_DENIED","documentation":"https://shopify.dev/api/usage/access-scopes"}}]}""",
    )
    val error = shopify.shopIdentity().failureReason()
    assert((error as ShopifyError.GraphqlError).codes == listOf("ACCESS_DENIED"))
    assert(!error.isRetryable)
  }

  @Test
  fun `a response without data is a GraphqlError`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"data":null}""")
    assert(shopify.shopIdentity().failureReason() is ShopifyError.GraphqlError)
  }

  /** Schema drift reads the same on every attempt, and the decoder's complaint quotes the body: logged, never retried. */
  @Test
  fun `an unreadable response is Undecodable and not retryable`() = runBlocking {
    fake.stubRaw("ShopIdentity", "{not-json")
    val error = shopify.shopIdentity().failureReason()
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
    assert(result.failureReason() == ShopifyError.UserError(listOf("Fulfillment has already been delivered.")))
  }

  @Test
  fun `cancelFulfillment without a cancelled fulfillment or a user error is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(fulfillmentCancel = FulfillmentCancelPayload(fulfillment = null, userErrors = emptyList())),
      FulfillmentCancelMutation.Result.serializer(),
    )
    assert(shopify.cancelFulfillment("gid://shopify/Fulfillment/8000").failureReason() is ShopifyError.GraphqlError)
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
    assert(result.failureReason() == ShopifyError.UserError(listOf("Fulfillment cannot be cancelled.")))
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
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = minimalOrder().copy(email = "buyer@example.com")), GetOrderForDss.Result.serializer())
    val order = shopify.orderForDss("gid://shopify/Order/1001").successValue()
    assert(order.name == "#1001")
    assert(order.email == "buyer@example.com")
    assert(fake.calls.single().variables.jsonObject["id"]?.jsonPrimitive?.content == "gid://shopify/Order/1001")
  }

  /** The sync tells a cancelled fulfillment from a live one by it, so the status has to survive the wire. */
  @Test
  fun `orderForDss decodes the status of the order's fulfillments`() = runBlocking {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("1Z999"), status = FulfillmentStatus.CANCELLED))
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = order), GetOrderForDss.Result.serializer())
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result.successValue().fulfillments.single().status == FulfillmentStatus.CANCELLED)
  }

  /**
   * Written by hand rather than encoded from the generated types: a typed stub decodes with the serializer that encoded
   * it, so a field this build names differently from Shopify would round-trip unnoticed. This body is the one
   * [minimalOrder] describes, in the shape `GetOrderForDss` asks for.
   */
  @Test
  fun `orderForDss decodes the order as Shopify writes it`() = runBlocking {
    fake.stubRaw("GetOrderForDss", rawOrderJson())
    val order = shopify.orderForDss("gid://shopify/Order/1001").successValue()
    assert(order == minimalOrder())
  }

  /**
   * Shopify adds enum values between API versions, and this build only knows the ones in its schema snapshot. A value
   * it does not know must land on the generated `__UNKNOWN_VALUE` default instead of failing the decode: an order that
   * does not decode is `Undecodable`, which `orders/create` acknowledges, and the order never reaches the monolith.
   */
  @Test
  fun `orderForDss decodes status values added after the schema snapshot as unknown`() = runBlocking {
    fake.stubRaw(
      "GetOrderForDss",
      rawOrderJson(
        displayFulfillmentStatus = "A_STATUS_ADDED_LATER",
        fulfillmentOrderStatus = "A_STATUS_ADDED_LATER",
        fulfillments = """[{"id":"gid://shopify/Fulfillment/8000","status":"A_STATUS_ADDED_LATER","trackingInfo":[{"number":"1Z999"}]}]""",
      ),
    )
    val order = shopify.orderForDss("gid://shopify/Order/1001").successValue()
    assert(order.displayFulfillmentStatus == OrderDisplayFulfillmentStatus.__UNKNOWN_VALUE)
    assert(order.fulfillmentOrders.edges.single().node.status == FulfillmentOrderStatus.__UNKNOWN_VALUE)
    assert(order.fulfillments.single().status == FulfillmentStatus.__UNKNOWN_VALUE)
  }

  @Test
  fun `orderForDss answers NotFound naming the legacy id when Shopify has no such order`() = runBlocking {
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
    val result = shopify.orderForDss("gid://shopify/Order/1001")
    assert(result.failureReason() == ShopifyError.NotFound("order 1001 not found"))
  }

  @Test
  fun `createFulfillment sends the lines grouped by fulfillment order with the tracking and answers the new id`() = runBlocking {
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
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = "https://track.example/1Z999"),
      notifyCustomer = false,
    )
    assert(result == Success(ShopifyFulfillmentId(5001L)))
    // Two fulfillment orders in, two groups out: Shopify rejects a duplicated fulfillmentOrderId. Each line keeps its
    // own id and quantity, which is what decides which item Shopify marks as shipped.
    val expected = AppJson.parseToJsonElement(
      """
      {
        "lineItemsByFulfillmentOrder": [
          {
            "fulfillmentOrderId": "gid://shopify/FulfillmentOrder/301",
            "fulfillmentOrderLineItems": [
              {"id": "gid://shopify/FulfillmentOrderLineItem/401", "quantity": 1},
              {"id": "gid://shopify/FulfillmentOrderLineItem/402", "quantity": 2}
            ]
          },
          {
            "fulfillmentOrderId": "gid://shopify/FulfillmentOrder/302",
            "fulfillmentOrderLineItems": [
              {"id": "gid://shopify/FulfillmentOrderLineItem/403", "quantity": 1}
            ]
          }
        ],
        "tracking": {"company": "UPS", "number": "1Z999", "url": "https://track.example/1Z999"},
        "notifyCustomer": false
      }
      """,
    )
    assert(fake.calls.single().variables == expected)
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
    assert(result.failureReason() == ShopifyError.GraphqlError("fulfillment missing in response"))
  }

  /** What a restarting Shopify edge looks like from here: the connection is accepted and reset. */
  @Test
  fun `a reset connection is a Network failure`() = runBlocking {
    FakeFlakyServer().use { unreachable ->
      val deadShopify = shopifyServiceOn(shopifyClient, url = "${unreachable.baseUrl}/admin/api/${Config.SHOPIFY_API_VERSION}/graphql.json")
      val result = deadShopify.orderForDss("gid://shopify/Order/1001")
      assert(result.failureReason() is ShopifyError.Network)
    }
  }

  /** A bug answered as a network failure would be retried by Shopify and the monolith and never reach the 500 log line. */
  @Test
  fun `a failure that is not a transport failure propagates instead of reading as a Network failure`() = runBlocking {
    throwingHttpClient(IllegalStateException("client misconfigured")).use { broken ->
      val brokenShopify = shopifyServiceOn(broken)
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
    assert(result.failureReason() == ShopifyError.UserError(listOf("Tracking number is invalid.")))
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
      message = "Left the sorting center",
    )
    assert(result == Success(ShopifyFulfillmentEventId(7001L)))
    val input = fake.calls.single().variables.jsonObject["fulfillmentEvent"]!!.jsonObject
    assert(input["fulfillmentId"]?.jsonPrimitive?.content == "gid://shopify/Fulfillment/5001")
    assert(input["happenedAt"]?.jsonPrimitive?.content == "2026-04-02T08:30:00Z")
    assert(input["status"]?.jsonPrimitive?.content == "IN_TRANSIT")
    assert(input["message"]?.jsonPrimitive?.content == "Left the sorting center")
  }

  @Test
  fun `webhookSubscriptions decodes the subscriptions Shopify already has`() = runBlocking {
    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(
        webhookSubscriptions = WebhookSubscriptionConnection(
          nodes = listOf(
            ExistingSubscription(
              id = "gid://shopify/WebhookSubscription/1",
              topic = WebhookSubscriptionTopic.ORDERS_CREATE,
              uri = CALLBACK_URL,
              includeFields = listOf("id", "admin_graphql_api_id"),
              filter = "vendor:Acme",
              format = WebhookSubscriptionFormat.JSON,
            ),
            ExistingSubscription(
              id = "gid://shopify/WebhookSubscription/2",
              topic = WebhookSubscriptionTopic.PRODUCTS_UPDATE,
              uri = CALLBACK_URL,
              includeFields = emptyList(),
              format = WebhookSubscriptionFormat.XML,
            ),
          ),
        ),
      ),
      GetWebhookSubscriptions.Result.serializer(),
    )
    val subscriptions = shopify.webhookSubscriptions().successValue()
    assert(subscriptions.map { it.topic } == listOf("ORDERS_CREATE", "PRODUCTS_UPDATE"))
    assert(subscriptions.first().id == "gid://shopify/WebhookSubscription/1")
    assert(subscriptions.map { it.includeFields } == listOf(listOf("id", "admin_graphql_api_id"), emptyList()))
    assert(subscriptions.map { it.filter } == listOf("vendor:Acme", null))
    assert(subscriptions.map { it.format } == listOf("JSON", "XML"))
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

  /** The scan asks for every subscription this app has; a variable here would silently narrow what a reinstall repairs. */
  @Test
  fun `webhookSubscriptions asks for them all, with no variables at all`() = runBlocking {
    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList())),
      GetWebhookSubscriptions.Result.serializer(),
    )

    shopify.webhookSubscriptions()

    assert(fake.calls.single().variables == JsonNull)
  }

  /**
   * `null` fields mean the full payload, and the client says so by leaving the variable out entirely: a variable sent
   * as JSON `null` would be a field Shopify reads as "no fields". `products/delete` is registered exactly this way,
   * and the workflow test can only see the typed `null` it passed, not what went on the wire.
   */
  @Test
  fun `registerWebhook with null fields leaves the variable out rather than sending a null`() = runBlocking {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = emptyList(),
          webhookSubscription = CreatedSubscription(
            id = "gid://shopify/WebhookSubscription/9",
            topic = WebhookSubscriptionTopic.PRODUCTS_DELETE,
            includeFields = emptyList(),
          ),
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )

    shopify.registerWebhook(WebhookSubscriptionTopic.PRODUCTS_DELETE, CALLBACK_URL, includeFields = null)

    assert("includeFields" !in fake.calls.single().variables.jsonObject)
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
    assert(result.failureReason() == ShopifyError.UserError(listOf("webhookSubscription,uri: is not allowed")))
  }

  // ---------- what a non-200 status from Shopify becomes ----------

  /** The client runs with `expectSuccess`, so a status arrives as an exception; the triage has to name it rather than call it a network problem. */
  @Test
  fun `a 401 from Shopify is a rejected token, not a network failure`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)
    val result = shopify.shopIdentity()
    assert(result.failureReason() == ShopifyError.TokenRejected(401))
  }

  @Test
  fun `a 401 reports the rejected token before answering, so the store can evict it`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)
    var reported = 0
    val result = serviceReporting { reported++ }.shopIdentity()
    assert(result.failureReason() == ShopifyError.TokenRejected(401))
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
    assert(shopify.shopIdentity().failureReason() == ShopifyError.HttpError(429))
  }

  @Test
  fun `a 503 from Shopify is an HttpError carrying the status`() = runBlocking {
    fake.stubRaw("ShopIdentity", "<html>maintenance</html>", HttpStatusCode.ServiceUnavailable)
    assert(shopify.shopIdentity().failureReason() == ShopifyError.HttpError(503))
  }

  /** Ktor's exception quotes the response body and the URL; both belong to Shopify, not to our logs. */
  @Test
  fun `an HTTP failure's message carries neither the response body nor the url`() = runBlocking {
    fake.stubRaw("ShopIdentity", """{"errors":"body-that-must-not-be-logged"}""", HttpStatusCode.PaymentRequired)
    val error = shopify.shopIdentity().failureReason()
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
    assert(result.failureReason() == ShopifyError.UserError(listOf("happenedAt is invalid")))
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
    assert(result.failureReason() is ShopifyError.GraphqlError)
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
    assert(result.failureReason() is ShopifyError.GraphqlError)
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
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `updateWebhookSubscription sends the id, the callback url and the fields, and answers the updated subscription`() = runBlocking {
    stubUpdatedSubscription(includeFields = listOf("id", "admin_graphql_api_id"))

    val result = shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, listOf("id", "admin_graphql_api_id"))

    val expected = WebhookSubscriptionStatus(
      id = "gid://shopify/WebhookSubscription/7",
      topic = "ORDERS_CREATE",
      uri = CALLBACK_URL,
      includeFields = listOf("id", "admin_graphql_api_id"),
      filter = null,
      format = "JSON",
    )
    assert(result == Success(expected))
    val variables = fake.calls.single().variables.jsonObject
    assert(variables["id"]?.jsonPrimitive?.content == "gid://shopify/WebhookSubscription/7")
    assert(variables["uri"]?.jsonPrimitive?.content == CALLBACK_URL)
    assert(variables["includeFields"]?.jsonArray?.map { it.jsonPrimitive.content } == listOf("id", "admin_graphql_api_id"))
  }

  /**
   * The client leaves out a variable whose value is null, and an `includeFields` left out of the input would keep the
   * subscription's fields: the operation's `= null` default is what turns the missing variable into the reset.
   */
  @Test
  fun `updateWebhookSubscription with null fields leaves the variable to the operation's null default`() = runBlocking {
    stubUpdatedSubscription(includeFields = emptyList())

    shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, includeFields = null)

    val call = fake.calls.single()
    assert("includeFields" !in call.variables.jsonObject)
    assert("\$includeFields: [String!] = null" in call.rawBody)
  }

  /** The service reads every event, and JSON only, so every update resets both whatever the subscription had. */
  @Test
  fun `updateWebhookSubscription resets the filter and the format in the operation itself`() = runBlocking {
    stubUpdatedSubscription(includeFields = emptyList())

    shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, includeFields = null)

    assert("filter: null, format: JSON" in fake.calls.single().rawBody)
  }

  @Test
  fun `updateWebhookSubscription prefixes a user error with the field it names`() = runBlocking {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(
        webhookSubscriptionUpdate = WebhookSubscriptionUpdatePayload(
          userErrors = listOf(UpdateUserError(field = listOf("id"), message = "Webhook subscription does not exist")),
          webhookSubscription = null,
        ),
      ),
      UpdateWebhookSubscription.Result.serializer(),
    )
    val result = shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, includeFields = null)
    assert(result.failureReason() == ShopifyError.UserError(listOf("id: Webhook subscription does not exist")))
  }

  @Test
  fun `deleteWebhookSubscription sends the id and succeeds on the deleted id`() = runBlocking {
    stubDeletePayload(WebhookSubscriptionDeletePayload(userErrors = emptyList(), deletedWebhookSubscriptionId = "gid://shopify/WebhookSubscription/7"))

    val result = shopify.deleteWebhookSubscription("gid://shopify/WebhookSubscription/7")

    assert(result == Success(Unit))
    assert(fake.calls.single().variables.jsonObject["id"]?.jsonPrimitive?.content == "gid://shopify/WebhookSubscription/7")
  }

  @Test
  fun `deleteWebhookSubscription prefixes a user error with the field it names`() = runBlocking {
    stubDeletePayload(
      WebhookSubscriptionDeletePayload(
        userErrors = listOf(DeleteUserError(field = listOf("id"), message = "Webhook subscription does not exist")),
        deletedWebhookSubscriptionId = null,
      ),
    )
    val result = shopify.deleteWebhookSubscription("gid://shopify/WebhookSubscription/7")
    assert(result.failureReason() == ShopifyError.UserError(listOf("id: Webhook subscription does not exist")))
  }

  /** A payload without the deleted id and without a user error has not said the subscription is gone. */
  @Test
  fun `deleteWebhookSubscription without a deleted id in the payload is a GraphqlError`() = runBlocking {
    stubDeletePayload(WebhookSubscriptionDeletePayload(userErrors = emptyList(), deletedWebhookSubscriptionId = null))
    val result = shopify.deleteWebhookSubscription("gid://shopify/WebhookSubscription/7")
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  // ---------- what the schema allows Shopify to answer ----------

  /** A gid without a number would be Shopify changing its id format; the install must still learn the domain. */
  @Test
  fun `shopIdentity answers a null shop id when the gid carries no number`() = runBlocking {
    stubShopIdentity(id = "gid://shopify/Shop/")
    assert(shopify.shopIdentity().successValue().shopId == null)
  }

  /** The canonical domain is what the install remembers the shop under; an unparseable one must not lose the shop. */
  @Test
  fun `shopIdentity keeps the shop it asked about when the canonical domain does not parse`() = runBlocking {
    stubShopIdentity(domain = "not a host at all")
    assert(shopify.shopIdentity().successValue().domain == ACME_SHOP)
  }

  /** Below Shopify's cap the count is the catalogue; above it the install page has to say "at least". */
  @Test
  fun `productCount reports a count Shopify did not cap as exact`() = runBlocking {
    fake.stubData(
      "ProductsCount",
      ProductsCount.Result(productsCount = Count(count = 42, precision = CountPrecision.EXACT)),
      ProductsCount.Result.serializer(),
    )
    assert(shopify.productCount() == Success(ProductCount(count = 42, isExact = true)))
  }

  /** A gid we cannot shorten is still the only thing that identifies the order the webhook named. */
  @Test
  fun `orderForDss names the gid itself when it carries no legacy id`() = runBlocking {
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
    assert(shopify.orderForDss("gid://shopify/Order/").failureReason() == ShopifyError.NotFound("order gid://shopify/Order/ not found"))
  }

  /** The merchant's shipping notification hangs off this flag; a mutation that dropped it would stop the emails silently. */
  @Test
  fun `createFulfillment sends the customer notification flag as it was asked`() = runBlocking {
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
    shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = true,
    )
    assert(fake.calls.single().variables.jsonObject["notifyCustomer"]!!.jsonPrimitive.boolean)
  }

  /** Shopify does not always say which field it objected to, and the message alone is what the install page then shows. */
  @Test
  fun `a user error without a field keeps its message unprefixed`() = runBlocking {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = listOf(RegisterUserError(field = null, message = "Address for this topic has already been taken")),
          webhookSubscription = null,
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )
    val result = shopify.registerWebhook(WebhookSubscriptionTopic.ORDERS_CREATE, CALLBACK_URL, includeFields = null)
    assert(result.failureReason() == ShopifyError.UserError(listOf("Address for this topic has already been taken")))
  }

  /** An empty `errors` array is Shopify saying there were none; reading it as a failure would throw away the data beside it. */
  @Test
  fun `an empty errors array is not a failure`() = runBlocking {
    fake.stubRaw(
      "ShopIdentity",
      """{"data":{"shop":{"id":"gid://shopify/Shop/1","myshopifyDomain":"acme.myshopify.com"}},"errors":[]}""",
    )
    assert(shopify.shopIdentity().successValue().shopId == ShopifyShopId(1L))
  }

  @Test
  fun `updateWebhookSubscription without a subscription in the payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(
        webhookSubscriptionUpdate = WebhookSubscriptionUpdatePayload(userErrors = emptyList(), webhookSubscription = null),
      ),
      UpdateWebhookSubscription.Result.serializer(),
    )
    val result = shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, includeFields = null)
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  // ---------- a mutation Shopify answered without a payload at all ----------

  /**
   * Every mutation field is nullable in the Admin schema, so a `200` may carry no payload at all,
   * with neither a result nor a user error to explain it. Read as an empty success, that would report
   * a fulfillment created, a webhook registered or a subscription deleted that never happened, so
   * each mutation answers it as the upstream failure the monolith retries.
   */
  @Test
  fun `cancelFulfillment without a payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(fulfillmentCancel = null),
      FulfillmentCancelMutation.Result.serializer(),
    )
    assert(shopify.cancelFulfillment("gid://shopify/Fulfillment/8000").failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `createFulfillment without a payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(fulfillmentCreate = null),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.createFulfillment(
      lines = listOf(FulfillmentLine("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrderLineItem/401", 1)),
      tracking = FulfillmentTracking(company = "UPS", number = "1Z999", url = null),
      notifyCustomer = false,
    )
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `createFulfillmentEvent without a payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(fulfillmentEventCreate = null),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createFulfillmentEvent("gid://shopify/Fulfillment/5001", FulfillmentEventStatus.IN_TRANSIT, "2026-04-02T08:30:00Z", null)
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `registerWebhook without a payload is a GraphqlError`() = runBlocking {
    fake.stubData("RegisterWebhook", RegisterWebhook.Result(webhookSubscriptionCreate = null), RegisterWebhook.Result.serializer())
    val result = shopify.registerWebhook(WebhookSubscriptionTopic.ORDERS_CREATE, CALLBACK_URL, includeFields = null)
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `updateWebhookSubscription without a payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(webhookSubscriptionUpdate = null),
      UpdateWebhookSubscription.Result.serializer(),
    )
    val result = shopify.updateWebhookSubscription("gid://shopify/WebhookSubscription/7", CALLBACK_URL, includeFields = null)
    assert(result.failureReason() is ShopifyError.GraphqlError)
  }

  @Test
  fun `deleteWebhookSubscription without a payload is a GraphqlError`() = runBlocking {
    fake.stubData(
      "DeleteWebhookSubscription",
      DeleteWebhookSubscription.Result(webhookSubscriptionDelete = null),
      DeleteWebhookSubscription.Result.serializer(),
    )
    assert(shopify.deleteWebhookSubscription("gid://shopify/WebhookSubscription/7").failureReason() is ShopifyError.GraphqlError)
  }

  // ---------- helpers ----------

  private fun stubDeletePayload(payload: WebhookSubscriptionDeletePayload) {
    fake.stubData(
      "DeleteWebhookSubscription",
      DeleteWebhookSubscription.Result(webhookSubscriptionDelete = payload),
      DeleteWebhookSubscription.Result.serializer(),
    )
  }

  private fun stubUpdatedSubscription(includeFields: List<String>) {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(
        webhookSubscriptionUpdate = WebhookSubscriptionUpdatePayload(
          userErrors = emptyList(),
          webhookSubscription = UpdatedSubscription(
            id = "gid://shopify/WebhookSubscription/7",
            topic = WebhookSubscriptionTopic.ORDERS_CREATE,
            uri = CALLBACK_URL,
            includeFields = includeFields,
            format = WebhookSubscriptionFormat.JSON,
          ),
        ),
      ),
      UpdateWebhookSubscription.Result.serializer(),
    )
  }

  private fun stubShopIdentity(
id: String = "gid://shopify/Shop/1", domain: String = "acme.myshopify.com") {
    fake.stubData(
      "ShopIdentity",
      ShopIdentity.Result(shop = ShopIdentityShop(id = id, myshopifyDomain = domain)),
      ShopIdentity.Result.serializer(),
    )
  }

  /** A `GetOrderForDss` answer written the way Shopify writes it, matching [minimalOrder] unless a case changes a value. */
  private fun rawOrderJson(
    displayFulfillmentStatus: String = "UNFULFILLED",
    fulfillmentOrderStatus: String = "OPEN",
    fulfillments: String = "[]",
  ): String =
    """
    {"data":{"order":{
      "id":"gid://shopify/Order/1001","name":"#1001","email":null,"createdAt":"2026-04-25T10:30:00+00:00",
      "totalPriceSet":{"shopMoney":{"amount":"39.98","currencyCode":"USD"}},
      "displayFinancialStatus":"PAID","displayFulfillmentStatus":"$displayFulfillmentStatus",
      "shippingAddress":null,
      "lineItems":{"pageInfo":{"hasNextPage":false},"edges":[{"node":{
        "id":"gid://shopify/LineItem/201","quantity":2,"name":"T-Shirt - Blue","title":"T-Shirt",
        "originalUnitPriceSet":{"shopMoney":{"amount":"19.99"}},"variant":{"legacyResourceId":"101"}
      }}]},
      "fulfillmentOrders":{"pageInfo":{"hasNextPage":false},"edges":[{"node":{
        "id":"gid://shopify/FulfillmentOrder/301","status":"$fulfillmentOrderStatus",
        "lineItems":{"pageInfo":{"hasNextPage":false},"edges":[{"node":{
          "id":"gid://shopify/FulfillmentOrderLineItem/401","remainingQuantity":2,"variant":{"legacyResourceId":"101"}
        }}]}
      }}]},
      "fulfillments":$fulfillments
    }}}
    """.trimIndent()
}
