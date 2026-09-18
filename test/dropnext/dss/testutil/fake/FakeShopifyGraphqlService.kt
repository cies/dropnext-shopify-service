package dropnext.dss.testutil.fake

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.fulfillment.PackageContent
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.FulfillmentTracking
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyCatalogPage
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.testutil.helper.MutableTimeSource
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

  var accessScopeHandlesResult: ShopifyResult<List<String>> = Success(ShopifyAccessScope.entries.map { it.handle })
  val accessScopeHandlesCalls: MutableList<ShopDomain> = mutableListOf()

  var productByIdResult: ShopifyResult<ShopProduct?> = Success(null)

  /** Drained one page per call, so a test can serve a product's variants in pages; the single result is used once it runs out. */
  val productByIdResultQueue: MutableList<ShopifyResult<ShopProduct?>> = mutableListOf()
  val productByIdCalls: MutableList<String> = mutableListOf()

  /** The `variantsAfter` cursor of each `productById` call, in step with [productByIdCalls]. */
  val productByIdCursors: MutableList<String?> = mutableListOf()

  /** How long each `productById` call takes to answer: what makes a product of many pages outlive a webhook's budget. */
  var productByIdDelay: Duration = Duration.ZERO

  var productVariantIdsPageResult: ShopifyResult<ShopifyCatalogPage> =
    Success(ShopifyCatalogPage(entries = emptyList(), nextCursor = null))

  /** Drained one page per call, so a test can walk several pages; the single result is used once it runs out. */
  val productVariantIdsPageResultQueue: MutableList<ShopifyResult<ShopifyCatalogPage>> = mutableListOf()

  val productVariantIdsPageCalls: MutableList<RecordedCatalogPageCall> = mutableListOf()

  var orderForDssResult: ShopifyResult<Order> = Failure(ShopifyError.NotFound("order not found"))

  /** Drained one load per call, so a test can serve the order before and after a cancel; the single result is used once it runs out. */
  val orderForDssResultQueue: MutableList<ShopifyResult<Order>> = mutableListOf()

  /** How long `orderForDss` takes to answer: what makes a webhook outlive its time budget. */
  var orderForDssDelay: Duration = Duration.ZERO

  /** When set, `orderForDss` waits for it after recording the call, so a test can hold a delivery provably mid-flight. */
  var orderForDssGate: CompletableDeferred<Unit>? = null
  val orderForDssCalls: MutableList<String> = mutableListOf()

  /** The content of each fulfillment by gid; a fulfillment not in here is not found. */
  val fulfillmentContentResults: MutableMap<String, ShopifyResult<PackageContent>> = mutableMapOf()
  val fulfillmentContentCalls: MutableList<String> = mutableListOf()

  var cancelFulfillmentResult: ShopifyResult<Unit> = Success(Unit)

  /** The answer for one fulfillment gid; [cancelFulfillmentResult] answers the others. */
  val cancelFulfillmentResults: MutableMap<String, ShopifyResult<Unit>> = mutableMapOf()
  val cancelFulfillmentCalls: MutableList<String> = mutableListOf()

  /**
   * The clock a shipments sync measures its budget on, and how long each call it makes takes on it: what makes a sync
   * outlive its budget without a test waiting for it.
   */
  var shipmentSyncTimeSource: MutableTimeSource? = null
  var shipmentSyncCallTakes: Duration = Duration.ZERO

  var createFulfillmentResult: ShopifyResult<ShopifyFulfillmentId> =
    Failure(ShopifyError.UserError(listOf("fulfillment missing in response")))
  val createFulfillmentResultQueue: MutableList<ShopifyResult<ShopifyFulfillmentId>> = mutableListOf()
  val createFulfillmentCalls: MutableList<RecordedCreateFulfillmentCall> = mutableListOf()

  var createFulfillmentEventResult: ShopifyResult<ShopifyFulfillmentEventId> =
    Failure(ShopifyError.NotFound("missing fulfillment event id"))
  val createFulfillmentEventCalls: MutableList<RecordedFulfillmentEventCall> = mutableListOf()

  var webhookSubscriptionsResult: ShopifyResult<List<WebhookSubscriptionStatus>> = Success(emptyList())
  val webhookSubscriptionsCalls: MutableList<Unit> = mutableListOf()

  var registerWebhookResult: ShopifyResult<String> = Success("gid://shopify/WebhookSubscription/1")

  /** Each entry is the `(topic, callbackUrl, includeFields)` that was sent to `registerWebhook`. */
  val registerWebhookCalls: MutableList<Triple<WebhookSubscriptionTopic, String, List<String>?>> = mutableListOf()

  var updateWebhookSubscriptionResult: ShopifyResult<WebhookSubscriptionStatus> =
    Failure(ShopifyError.GraphqlError("webhook subscription missing in response"))
  val updateWebhookSubscriptionCalls: MutableList<RecordedUpdateWebhookSubscriptionCall> = mutableListOf()

  var deleteWebhookSubscriptionResult: ShopifyResult<Unit> = Success(Unit)
  val deleteWebhookSubscriptionCalls: MutableList<String> = mutableListOf()

  // ---------- interface impls ----------

  override suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo> {
    shopIdentityCalls.add(shop)
    return shopIdentityResult
  }

  override suspend fun productCount(): ShopifyResult<ProductCount> = productCountResult

  override suspend fun accessScopeHandles(): ShopifyResult<List<String>> {
    accessScopeHandlesCalls.add(shop)
    return accessScopeHandlesResult
  }

  override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> {
    productByIdCalls.add(productGid)
    productByIdCursors.add(variantsAfter)
    delay(productByIdDelay)
    return if (productByIdResultQueue.isNotEmpty()) productByIdResultQueue.removeAt(0) else productByIdResult
  }

  override suspend fun productVariantIdsPage(first: Int, after: String?): ShopifyResult<ShopifyCatalogPage> {
    productVariantIdsPageCalls.add(RecordedCatalogPageCall(first, after))
    return if (productVariantIdsPageResultQueue.isNotEmpty()) productVariantIdsPageResultQueue.removeAt(0)
    else productVariantIdsPageResult
  }

  override suspend fun orderForDss(orderGid: String): ShopifyResult<Order> {
    orderForDssCalls.add(orderGid)
    delay(orderForDssDelay)
    orderForDssGate?.await()
    passShipmentSyncCallTime()
    return if (orderForDssResultQueue.isNotEmpty()) orderForDssResultQueue.removeAt(0) else orderForDssResult
  }

  override suspend fun fulfillmentContent(fulfillmentGid: String): ShopifyResult<PackageContent> {
    fulfillmentContentCalls.add(fulfillmentGid)
    passShipmentSyncCallTime()
    // A test that reuses a tracking number has to say what the fulfillment holds; without a stub it would silently
    // take the "gone" path and may still pass.
    return fulfillmentContentResults[fulfillmentGid]
      ?: error("no fulfillmentContent stubbed for $fulfillmentGid")
  }

  override suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit> {
    cancelFulfillmentCalls.add(fulfillmentGid)
    passShipmentSyncCallTime()
    return cancelFulfillmentResults[fulfillmentGid] ?: cancelFulfillmentResult
  }

  private fun passShipmentSyncCallTime() {
    shipmentSyncTimeSource?.plusAssign(shipmentSyncCallTakes)
  }

  override suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId> {
    createFulfillmentCalls.add(RecordedCreateFulfillmentCall(lines = lines, tracking = tracking, notifyCustomer = notifyCustomer))
    passShipmentSyncCallTime()
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

  override suspend fun webhookSubscriptions(): ShopifyResult<List<WebhookSubscriptionStatus>> {
    webhookSubscriptionsCalls.add(Unit)
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

  override suspend fun deleteWebhookSubscription(subscriptionId: String): ShopifyResult<Unit> {
    deleteWebhookSubscriptionCalls.add(subscriptionId)
    return deleteWebhookSubscriptionResult
  }

  override fun clear() {
    shopIdentityCalls.clear()
    accessScopeHandlesCalls.clear()
    productByIdCalls.clear()
    productByIdCursors.clear()
    productByIdResultQueue.clear()
    productByIdDelay = Duration.ZERO
    productVariantIdsPageCalls.clear()
    productVariantIdsPageResultQueue.clear()
    orderForDssCalls.clear()
    orderForDssResultQueue.clear()
    orderForDssDelay = Duration.ZERO
    orderForDssGate = null
    fulfillmentContentResults.clear()
    fulfillmentContentCalls.clear()
    cancelFulfillmentResults.clear()
    cancelFulfillmentCalls.clear()
    shipmentSyncTimeSource = null
    shipmentSyncCallTakes = Duration.ZERO
    createFulfillmentCalls.clear()
    createFulfillmentResultQueue.clear()
    createFulfillmentEventCalls.clear()
    webhookSubscriptionsCalls.clear()
    registerWebhookCalls.clear()
    updateWebhookSubscriptionCalls.clear()
    deleteWebhookSubscriptionCalls.clear()
  }
}

/** One page asked of the catalog walk: the size it wanted and the cursor it carried. */
data class RecordedCatalogPageCall(val first: Int, val after: String?)

data class RecordedCreateFulfillmentCall(
  val lines: List<FulfillmentLine>,
  val tracking: FulfillmentTracking,
  /** Recorded because it decides whether Shopify emails the retailer's customer about this package. */
  val notifyCustomer: Boolean,
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
