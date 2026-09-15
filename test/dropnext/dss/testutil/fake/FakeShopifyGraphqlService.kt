package dropnext.dss.testutil.fake

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.FulfillmentTracking
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay


/**
 * In-memory [ShopifyGraphqlService] for handler/workflow tests. Each method has a stubbable
 * result field (some also a queue for multi-call flows) and a recording of its inputs — but only
 * for the ones tests actually read today. Adding more is intentionally a three-line change at the
 * point a test starts to need it; the fake should not carry speculative surface area.
 *
 * Reserve [FakeShopifyGraphqlServer] (HTTP fake) for the wire-level tests, which have to exercise
 * the actual Graphql wire format and the triage in `HttpShopifyGraphqlService`.
 */
class FakeShopifyGraphqlService(
  override val shop: ShopDomain = ShopDomain.parse("acme.myshopify.com")!!,
) : ShopifyGraphqlService, RecordingFake {

  // ---------- stubbable results + recordings (only what tests use today) ----------

  var shopIdentityResult: ShopifyResult<ShopIdentityInfo> =
    Success(ShopIdentityInfo(shopId = ShopifyShopId(0L), domain = shop))
  val shopIdentityCalls: MutableList<ShopDomain> = mutableListOf()

  var productCountResult: ShopifyResult<ProductCount> = Success(ProductCount(count = 0, isExact = true))
  val productCountCalls: MutableList<ShopDomain> = mutableListOf()

  var productByIdResult: ShopifyResult<ShopProduct?> = Success(null)
  val productByIdCalls: MutableList<String> = mutableListOf()

  var orderForDssResult: ShopifyResult<Order> = Failure(ShopifyError.NotFound("order not found"))
  val orderForDssResultQueue: MutableList<ShopifyResult<Order>> = mutableListOf()

  /** How long `orderForDss` takes to answer: what makes a webhook outlive its time budget. */
  var orderForDssDelay: Duration = Duration.ZERO

  /** When set, `orderForDss` waits for it after recording the call, so a test can hold a delivery provably mid-flight. */
  var orderForDssGate: CompletableDeferred<Unit>? = null
  val orderForDssCalls: MutableList<String> = mutableListOf()

  var cancelFulfillmentResult: ShopifyResult<Unit> = Success(Unit)
  val cancelFulfillmentCalls: MutableList<String> = mutableListOf()

  var createFulfillmentResult: ShopifyResult<ShopifyFulfillmentId> =
    Failure(ShopifyError.UserError(listOf("fulfillment missing in response")))
  val createFulfillmentResultQueue: MutableList<ShopifyResult<ShopifyFulfillmentId>> = mutableListOf()
  val createFulfillmentCalls: MutableList<RecordedCreateFulfillmentCall> = mutableListOf()

  var createFulfillmentEventResult: ShopifyResult<ShopifyFulfillmentEventId> =
    Failure(ShopifyError.NotFound("missing fulfillment event id"))
  val createFulfillmentEventCalls: MutableList<RecordedFulfillmentEventCall> = mutableListOf()

  var webhookSubscriptionsResult: ShopifyResult<List<WebhookSubscriptionStatus>> = Success(emptyList())
  val webhookSubscriptionsCalls: MutableList<List<WebhookSubscriptionTopic>> = mutableListOf()

  var registerWebhookResult: ShopifyResult<String> = Success("gid://shopify/WebhookSubscription/1")

  /** Each entry is the `(topic, callbackUrl, includeFields)` that was sent to `registerWebhook`. */
  val registerWebhookCalls: MutableList<Triple<WebhookSubscriptionTopic, String, List<String>?>> = mutableListOf()

  var updateWebhookSubscriptionResult: ShopifyResult<WebhookSubscriptionStatus> =
    Failure(ShopifyError.GraphqlError("webhook subscription missing in response"))
  val updateWebhookSubscriptionCalls: MutableList<RecordedUpdateWebhookSubscriptionCall> = mutableListOf()

  // ---------- interface impls ----------

  override suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo> {
    shopIdentityCalls.add(shop)
    return shopIdentityResult
  }

  override suspend fun productCount(): ShopifyResult<ProductCount> {
    productCountCalls.add(shop)
    return productCountResult
  }

  override suspend fun productById(productGid: String): ShopifyResult<ShopProduct?> {
    productByIdCalls.add(productGid)
    return productByIdResult
  }

  override suspend fun orderForDss(orderGid: String): ShopifyResult<Order> {
    orderForDssCalls.add(orderGid)
    delay(orderForDssDelay)
    orderForDssGate?.await()
    if (orderForDssResultQueue.isNotEmpty()) {
      return orderForDssResultQueue.removeAt(0)
    }
    return orderForDssResult
  }

  override suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit> {
    cancelFulfillmentCalls.add(fulfillmentGid)
    return cancelFulfillmentResult
  }

  override suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId> {
    createFulfillmentCalls.add(RecordedCreateFulfillmentCall(lines = lines, tracking = tracking))
    if (createFulfillmentResultQueue.isNotEmpty()) {
      return createFulfillmentResultQueue.removeAt(0)
    }
    return createFulfillmentResult
  }

  override suspend fun createFulfillmentEvent(
    fulfillmentGid: String,
    status: FulfillmentEventStatus,
    happenedAt: String,
    message: String?,
  ): ShopifyResult<ShopifyFulfillmentEventId> {
    createFulfillmentEventCalls.add(
      RecordedFulfillmentEventCall(fulfillmentGid = fulfillmentGid, status = status, happenedAt = happenedAt, message = message),
    )
    return createFulfillmentEventResult
  }

  override suspend fun webhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String?,
  ): ShopifyResult<List<WebhookSubscriptionStatus>> {
    webhookSubscriptionsCalls.add(topics)
    return webhookSubscriptionsResult
  }

  override suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<String> {
    registerWebhookCalls.add(Triple(topic, callbackUrl, includeFields))
    return registerWebhookResult
  }

  override suspend fun updateWebhookSubscription(
    subscriptionId: String,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<WebhookSubscriptionStatus> {
    updateWebhookSubscriptionCalls.add(RecordedUpdateWebhookSubscriptionCall(subscriptionId, callbackUrl, includeFields))
    return updateWebhookSubscriptionResult
  }

  override fun clear() {
    shopIdentityCalls.clear()
    productCountCalls.clear()
    productByIdCalls.clear()
    orderForDssCalls.clear()
    orderForDssResultQueue.clear()
    orderForDssDelay = Duration.ZERO
    orderForDssGate = null
    cancelFulfillmentCalls.clear()
    createFulfillmentCalls.clear()
    createFulfillmentResultQueue.clear()
    createFulfillmentEventCalls.clear()
    webhookSubscriptionsCalls.clear()
    registerWebhookCalls.clear()
    updateWebhookSubscriptionCalls.clear()
  }
}

data class RecordedCreateFulfillmentCall(
  val lines: List<FulfillmentLine>,
  val tracking: FulfillmentTracking,
)

data class RecordedUpdateWebhookSubscriptionCall(
  val subscriptionId: String,
  val callbackUrl: String,
  val includeFields: List<String>?,
)

data class RecordedFulfillmentEventCall(
  val fulfillmentGid: String,
  val status: FulfillmentEventStatus,
  val happenedAt: String,
  val message: String?,
)
