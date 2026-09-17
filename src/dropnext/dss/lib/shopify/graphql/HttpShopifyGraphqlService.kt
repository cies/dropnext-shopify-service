package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyQueryCost
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.graphql.generated.CurrentAppInstallationAccessScopes
import dropnext.graphql.generated.DeleteWebhookSubscription
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetProductVariantIdsPage
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.ProductsCount
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.UpdateWebhookSubscription
import dropnext.graphql.generated.enums.CountPrecision
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull


private val log = KotlinLogging.logger {}

/**
 * How much of each connection our operations ask for. The `.graphql` files take these as variables rather than
 * spelling a number, so each one is defined here once: the value the query used and the value a
 * [ShopifyError.Truncated] message names cannot drift apart.
 *
 * [PRODUCT_VARIANTS_PAGE_SIZE] lives beside the service interface, because the workflow that pages a product names it too.
 *
 * **These are our choices, bounded by Shopify's limits — not limits themselves.** What Shopify fixes is the
 * surrounding frame: a connection's `first:` may not exceed **250**, a single query may not cost more than
 * [SHOPIFY_QUERY_COST_CAP] points (a query past [QUERY_COST_WARNING_THRESHOLD] is warned about), and a product may have
 * up to **2048** variants. Raising one of these therefore costs query points against
 * the shop's bucket, which the live webhook traffic draws on too, and cannot buy completeness on its own — a product
 * past 250 variants needs paging, not a bigger page.
 *
 * `ORDER_FULFILLMENTS_PAGE_SIZE` is the odd one: `Order.fulfillments` is a plain truncating list, not a connection, so
 * it cannot report that it cut the answer short. It sits at Shopify's per-page ceiling because that is the only lever
 * there is, and what it still misses is documented in `docs/FULFILLMENT_VERIFICATION.md`.
 */
private const val PRODUCT_MEDIA_PAGE_SIZE = 20
private const val ORDER_LINE_ITEMS_PAGE_SIZE = 100
private const val ORDER_FULFILLMENT_ORDERS_PAGE_SIZE = 50
private const val ORDER_FULFILLMENTS_PAGE_SIZE = 250

/**
 * Production [ShopifyGraphqlService] — speaks real Graphql to a shop's Admin API endpoint over
 * the shared [GraphQLKtorClient], injecting the per-shop [accessToken] on every request.
 *
 * Instances are created by [HttpShopifyGraphqlServiceFactory], which passes [onTokenRejected] so a
 * `401` evicts the token from the store before the caller even sees the [ShopifyError.TokenRejected].
 * Stateless aside from its injected fields,
 * so a single instance is safe for concurrent use across requests targeting the same shop.
 */
class HttpShopifyGraphqlService(
  override val shop: ShopDomain,
  private val gqlClient: GraphQLKtorClient,
  private val accessToken: ShopifyAdminToken,
  private val onTokenRejected: () -> Unit = {},
  private val costReporter: ShopifyQueryCostReporter = ShopifyQueryCostReporter(),
) : ShopifyGraphqlService {

  override suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo> =
    execute(ShopIdentity()).map { data ->
      ShopIdentityInfo(
        shopId = legacyIdFromGid(data.shop.id)?.let(::ShopifyShopId),
        domain = ShopDomain.parse(data.shop.myshopifyDomain) ?: shop,
      )
    }

  override suspend fun productCount(): ShopifyResult<ProductCount> =
    execute(ProductsCount()).flatMap { data ->
      data.productsCount?.let { Success(ProductCount(count = it.count, isExact = it.precision == CountPrecision.EXACT)) }
        ?: Failure(ShopifyError.GraphqlError("products count missing in response"))
    }

  override suspend fun accessScopeHandles(): ShopifyResult<List<String>> =
    execute(CurrentAppInstallationAccessScopes()).map { data -> data.currentAppInstallation.accessScopes.map { it.handle } }

  override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> =
    executeCosted(
      GetProductById(
        GetProductById.Variables(
          id = productGid,
          mediaFirst = PRODUCT_MEDIA_PAGE_SIZE,
          variantsFirst = PRODUCT_VARIANTS_PAGE_SIZE,
          variantsAfter = variantsAfter,
        )
      )
    ).map { (data, cost) ->
      val product = data.product ?: return@map null
      // Images are the one truncation worth tolerating: a product carrying too many of them still has to reach the
      // monolith, and the variants -- what the catalog is actually made of -- are paged. A later page reads the same
      // images again, so only the first one says so.
      if (variantsAfter == null && product.media.pageInfo.hasNextPage) {
        log.warn { "Product media truncated productGid=$productGid first=$PRODUCT_MEDIA_PAGE_SIZE" }
      }
      val variantsPage = product.variants.pageInfo
      ShopProduct(
        product = product,
        shopCurrencyCode = data.shop.currencyCode.name,
        rateBudget = cost?.budget,
        nextVariantsCursor = variantsPage.endCursor.takeIf { variantsPage.hasNextPage },
      )
    }

  override suspend fun productVariantIdsPage(first: Int, after: String?): ShopifyResult<ShopifyCatalogPage> =
    executeCosted(GetProductVariantIdsPage(GetProductVariantIdsPage.Variables(first = first, after = after)))
      .map { (data, cost) ->
        val connection = data.productVariants
        ShopifyCatalogPage(
          // A variant or product whose id will not parse is left out rather than failing the page: it could never
          // match a monolith row, which keys on the numeric id, so it is not a difference anyone could act on.
          entries = connection.nodes.mapNotNull { node ->
            val variantId = node.legacyResourceId.toLongOrNull() ?: return@mapNotNull null
            val productId = node.product.legacyResourceId.toLongOrNull() ?: return@mapNotNull null
            ShopifyCatalogEntry(ShopifyProductId(productId), ShopifyVariantId(variantId))
          },
          nextCursor = connection.pageInfo.endCursor.takeIf { connection.pageInfo.hasNextPage },
          rateBudget = cost?.budget,
        )
      }

  override suspend fun orderForDss(orderGid: String): ShopifyResult<Order> =
    execute(
      GetOrderForDss(
        GetOrderForDss.Variables(
          id = orderGid,
          lineItemsFirst = ORDER_LINE_ITEMS_PAGE_SIZE,
          fulfillmentOrdersFirst = ORDER_FULFILLMENT_ORDERS_PAGE_SIZE,
          fulfillmentOrderLineItemsFirst = ORDER_LINE_ITEMS_PAGE_SIZE,
          fulfillmentsFirst = ORDER_FULFILLMENTS_PAGE_SIZE,
        )
      )
    ).flatMap { data ->
      val order = data.order
        ?: return@flatMap Failure(ShopifyError.NotFound("order ${legacyIdFromGid(orderGid) ?: orderGid} not found"))
      order.truncatedConnectionOrNull()?.let { return@flatMap Failure(it) }
      Success(order)
    }

  override suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit> =
    execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid))).flatMap { data ->
      val payload = data.fulfillmentCancel
      // The state decides, not the wording: a refusal that mentions "already" can be about a fulfillment that was
      // already delivered, and reading that as a cancel would report a fulfillment gone that Shopify still shows.
      if (payload?.fulfillment?.status == FulfillmentStatus.CANCELLED) return@flatMap Success(Unit)
      val userErrors = payload?.userErrors.orEmpty().map { it.message }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      Failure(ShopifyError.GraphqlError("fulfillment not cancelled in response"))
    }

  override suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId> {
    val lineItemsByFulfillmentOrder = lines
      .groupBy { it.fulfillmentOrderId }
      .map { (fulfillmentOrderId, lineItems) ->
        FulfillmentOrderLineItemsInput(
          fulfillmentOrderId = fulfillmentOrderId,
          fulfillmentOrderLineItems = lineItems.map { FulfillmentOrderLineItemInput(id = it.lineItemId, quantity = it.quantity) },
        )
      }
    val request = FulfillmentCreateWithLineItems(
      FulfillmentCreateWithLineItems.Variables(
        lineItemsByFulfillmentOrder = lineItemsByFulfillmentOrder,
        tracking = FulfillmentTrackingInput(company = tracking.company, number = tracking.number, url = tracking.url),
        notifyCustomer = notifyCustomer,
      ),
    )
    return execute(request).flatMap { data ->
      val payload = data.fulfillmentCreate
      val userErrors = payload?.userErrors.orEmpty().map { it.message }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      // No user error and no fulfillment is Shopify's answer being broken, not a refusal: an upstream failure the
      // monolith retries, the same as for `createFulfillmentEvent` below, rather than a `400` it drops for good.
      val fulfillment = payload?.fulfillment
        ?: return@flatMap Failure(ShopifyError.GraphqlError("fulfillment missing in response"))
      val id = fulfillment.legacyResourceId.toLongOrNull() ?: legacyIdFromGid(fulfillment.id)
        ?: return@flatMap Failure(ShopifyError.GraphqlError("unparseable fulfillment id ${fulfillment.id}"))
      Success(ShopifyFulfillmentId(id))
    }
  }

  override suspend fun createFulfillmentEvent(
    fulfillmentGid: String,
    status: FulfillmentEventStatus,
    happenedAt: String,
    message: String?,
  ): ShopifyResult<ShopifyFulfillmentEventId> {
    val input = FulfillmentEventInput(fulfillmentId = fulfillmentGid, happenedAt = happenedAt, status = status, message = message)
    return execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input))).flatMap { data ->
      val payload = data.fulfillmentEventCreate
      val userErrors = payload?.userErrors.orEmpty().map { it.message }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))

      // Shopify accepted the mutation, reported no user error, and still returned no event:
      // Shopify's answer is broken. We make it a `GraphqlError`, which becomes a 502 for the monolith,
      // which triggers retries (5xx are retried, 4xx are dropped).
      val id = payload?.fulfillmentEvent?.id?.let(::legacyIdFromGid)
        ?: return@flatMap Failure(ShopifyError.GraphqlError("fulfillment event missing in response"))

      Success(ShopifyFulfillmentEventId(id))
    }
  }

  override suspend fun webhookSubscriptions(): ShopifyResult<List<WebhookSubscriptionStatus>> =
    execute(GetWebhookSubscriptions()).map { data ->
      data.webhookSubscriptions.nodes.map {
        WebhookSubscriptionStatus(it.id, it.topic.name, it.uri, it.includeFields, filter = it.filter, format = it.format.name)
      }
    }

  override suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<String> =
    execute(RegisterWebhook(RegisterWebhook.Variables(topic = topic, uri = callbackUrl, includeFields = includeFields))).flatMap { data ->
      val payload = data.webhookSubscriptionCreate
      val userErrors = payload?.userErrors.orEmpty().map { fieldPrefixedUserError(it.field, it.message) }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      payload?.webhookSubscription?.id?.let { Success(it) }
        ?: Failure(ShopifyError.GraphqlError("webhook subscription missing in response"))
    }

  override suspend fun updateWebhookSubscription(
    subscriptionId: String,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<WebhookSubscriptionStatus> {
    val variables = UpdateWebhookSubscription.Variables(id = subscriptionId, uri = callbackUrl, includeFields = includeFields)
    return execute(UpdateWebhookSubscription(variables)).flatMap { data ->
      val payload = data.webhookSubscriptionUpdate
      val userErrors = payload?.userErrors.orEmpty().map { fieldPrefixedUserError(it.field, it.message) }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      payload?.webhookSubscription
        ?.let { Success(WebhookSubscriptionStatus(it.id, it.topic.name, it.uri, it.includeFields, filter = it.filter, format = it.format.name)) }
        ?: Failure(ShopifyError.GraphqlError("webhook subscription missing in response"))
    }
  }

  override suspend fun deleteWebhookSubscription(subscriptionId: String): ShopifyResult<Unit> =
    execute(DeleteWebhookSubscription(DeleteWebhookSubscription.Variables(id = subscriptionId))).flatMap { data ->
      val payload = data.webhookSubscriptionDelete
      val userErrors = payload?.userErrors.orEmpty().map { fieldPrefixedUserError(it.field, it.message) }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      if (payload?.deletedWebhookSubscriptionId == null) {
        return@flatMap Failure(ShopifyError.GraphqlError("deleted webhook subscription id missing in response"))
      }
      Success(Unit)
    }

  /** Shopify refuses a webhook input with the path of the field it objects to, which names the culprit on the install page. */
  private fun fieldPrefixedUserError(field: List<String>?, message: String): String {
    val path = field.orEmpty().joinToString(",")
    return if (path.isNotBlank()) "$path: $message" else message
  }

  /**
   * The single point that runs an operation: injects the per-shop `X-Shopify-Access-Token` header
   * and does the triage every caller used to repeat — a non-2xx status is [ShopifyError.TokenRejected]
   * or [ShopifyError.HttpError], a thrown transport failure is [ShopifyError.Network], a body the
   * generated types cannot read is [ShopifyError.Undecodable], top-level `errors` are a
   * [ShopifyError.GraphqlError] with their codes, and so is a response without `data`. The
   * named methods above only look at their payload.
   *
   * The status is caught as an exception because the Graphql client runs with `expectSuccess`. Its
   * message would carry the response body, so only the status survives.
   *
   * Only an [IOException] is a network failure: a refused or reset connection, an unknown host and every Ktor timeout
   * are one. Anything else thrown here is a bug, and a bug answered as a network failure would be retried by Shopify
   * eight times and by the monolith into its dead-letter queue while never reaching the unhandled-error line, so it
   * propagates to `StatusPages` and its `500` instead.
   *
   * Whatever Shopify says the query cost goes to the [costReporter], on a refusal too: a throttled answer reports the
   * bucket that refused it, which is when that number is most worth having.
   */
  private suspend fun <T : Any> executeCosted(request: GraphQLClientRequest<T>): ShopifyResult<Costed<T>> {
    val response = try {
      gqlClient.execute(request) { header("X-Shopify-Access-Token", accessToken.value) }
    } catch (e: ResponseException) {
      val status = e.response.status.value
      if (e.response.status != HttpStatusCode.Unauthorized) return Failure(ShopifyError.HttpError(status))
      onTokenRejected()
      return Failure(ShopifyError.TokenRejected(status))
    } catch (e: SerializationException) {
      return Failure(ShopifyError.Undecodable(e.message ?: "not the expected JSON"))
    } catch (e: IOException) {
      return Failure(ShopifyError.Network(e.message ?: "network error"))
    }

    val cost = response.extensions.toShopifyQueryCostOrNull()
    cost?.let { costReporter.report(shop, request.operationName ?: "unnamed", it) }
    val errors = response.errors
    if (!errors.isNullOrEmpty()) {
      return Failure(
        ShopifyError.GraphqlError(
          message = errors.joinToString("; ") { it.message },
          codes = errors.mapNotNull { it.code() }.distinct(),
          rateBudget = cost?.budget,
        ),
      )
    }
    val data = response.data ?: return Failure(ShopifyError.GraphqlError("empty response"))
    return Success(Costed(data, cost))
  }

  /** The data alone, for the callers that have nothing to pace. */
  private suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): ShopifyResult<T> =
    executeCosted(request).map { it.data }
}

/**
 * The first connection of the snapshot that has another page, named the way the query nests it so an operator reads
 * which level ran over. One rule for all three readers of this order: the order ingest, the fulfillment sync and the
 * tracking update all treat what they were handed as the whole order.
 */
private fun Order.truncatedConnectionOrNull(): ShopifyError.Truncated? = when {
  lineItems.pageInfo.hasNextPage ->
    ShopifyError.Truncated("order.lineItems", ORDER_LINE_ITEMS_PAGE_SIZE)
  fulfillmentOrders.pageInfo.hasNextPage ->
    ShopifyError.Truncated("order.fulfillmentOrders", ORDER_FULFILLMENT_ORDERS_PAGE_SIZE)
  fulfillmentOrders.edges.any { it.node.lineItems.pageInfo.hasNextPage } ->
    ShopifyError.Truncated("fulfillmentOrders.lineItems", ORDER_LINE_ITEMS_PAGE_SIZE)
  else -> null
}

/** A payload and what Shopify said it cost, if it said. */
private data class Costed<T : Any>(val data: T, val cost: ShopifyQueryCost?)

private fun GraphQLClientError.code(): String? = when (val code = extensions?.get("code")) {
  is String -> code
  is JsonPrimitive -> code.contentOrNull
  else -> null
}
