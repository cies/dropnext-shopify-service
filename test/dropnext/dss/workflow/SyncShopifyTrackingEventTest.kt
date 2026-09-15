package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEvent as CreatedFulfillmentEvent
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEventCreatePayload
import dropnext.graphql.generated.fulfillmenteventcreatemutation.UserError as EventUserError
import io.ktor.client.HttpClient
import java.net.URI
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Stop it from unnecessarily reconstructing per instance.
class SyncShopifyTrackingEventTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeAll
  fun startServer() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = testHttpClient()
    val url = URI(shopifyGraphqlUrl(port)).toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, ShopifyAdminToken("tok"))
  }

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
  fun `createTrackingEvent rejects unsupported status as UserError`() = runBlocking {
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(status = "yeeted"))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.UserError)
    assert("unsupported tracking status" in (error as ShopifyError.UserError).messages.single())
    assert(fake.calls.isEmpty())
  }

  @Test
  fun `createTrackingEvent returns NotFound when order is missing`() = runBlocking {
    fake.stubGetOrderForDss(order = null)
    val result = syncShopifyTrackingEvent(shopify, trackingRequest())
    assert((result as Failure).reason is ShopifyError.NotFound)
  }

  @Test
  fun `createTrackingEvent returns NotFound when no fulfillment has the tracking number`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "OTHER-TRACK"))
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.NotFound)
    assert("no fulfillment with tracking number 1Z999" in error.message)
  }

  @Test
  fun `createTrackingEvent succeeds when the tracking number matches`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "1Z999"))
    fake.stubFulfillmentEventCreateOk(eventId = 7777L)
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
  }

  @Test
  fun `createTrackingEvent propagates event-creation UserError`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "1Z999"))
    fake.stubFulfillmentEventCreateUserError("happenedAt invalid")
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert((result as Failure).reason is ShopifyError.UserError)
  }

  /** A cancelled fulfillment keeps its tracking number, but the package it named is no longer on the order. */
  @Test
  fun `createTrackingEvent returns NotFound when only a cancelled fulfillment has the tracking number`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillments(fulfillment(5000L, listOf("1Z999"), status = FulfillmentStatus.CANCELLED)))
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert((result as Failure).reason is ShopifyError.NotFound)
  }

  @Test
  fun `createTrackingEvent matches a tracking number that differs only in surrounding whitespace`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "1Z999 "))
    fake.stubFulfillmentEventCreateOk(eventId = 7777L)
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = " 1Z999"))
    assert(result == Success(ShopifyFulfillmentEventId(7777L)))
  }

  // ---------- helpers ----------

  private fun orderWithTracking(trackingNumber: String) = orderWithFulfillment(id = 5000L, trackingNumbers = listOf(trackingNumber))

  private fun FakeShopifyGraphqlServer.stubGetOrderForDss(
    order: dropnext.graphql.generated.getorderfordss.Order?,
  ) = stubData(
    "GetOrderForDss",
    GetOrderForDss.Result(order = order),
    GetOrderForDss.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentEventCreateOk(eventId: Long) = stubData(
    "FulfillmentEventCreateMutation",
    FulfillmentEventCreateMutation.Result(
      fulfillmentEventCreate = FulfillmentEventCreatePayload(
        fulfillmentEvent = CreatedFulfillmentEvent(
          id = "gid://shopify/FulfillmentEvent/$eventId",
          status = FulfillmentEventStatus.IN_TRANSIT,
          message = null,
        ),
        userErrors = emptyList(),
      ),
    ),
    FulfillmentEventCreateMutation.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentEventCreateUserError(message: String) = stubData(
    "FulfillmentEventCreateMutation",
    FulfillmentEventCreateMutation.Result(
      fulfillmentEventCreate = FulfillmentEventCreatePayload(
        fulfillmentEvent = null,
        userErrors = listOf(EventUserError(field = listOf("fulfillmentEvent"), message = message)),
      ),
    ),
    FulfillmentEventCreateMutation.Result.serializer(),
  )

  private fun trackingRequest(
    trackingNumber: String = "1Z999",
    status: String = "in_transit",
  ): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = trackingNumber,
      status = status,
      happenedAt = "2026-04-02T08:30:00Z",
      message = null,
    )
}
