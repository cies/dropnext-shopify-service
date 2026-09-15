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
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.graphql.generated.CurrentAppInstallationAccessScopes
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
import dropnext.graphql.generated.enums.CountPrecision
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull



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

  override suspend fun productById(productGid: String): ShopifyResult<ShopProduct?> =
    execute(GetProductById(GetProductById.Variables(productGid))).map { data ->
      data.product?.let { ShopProduct(product = it, shopCurrencyCode = data.shop.currencyCode.name) }
    }

  override suspend fun orderForDss(orderGid: String): ShopifyResult<Order> =
    execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))).flatMap { data ->
      data.order?.let { Success(it) }
        ?: Failure(ShopifyError.NotFound("order ${legacyIdFromGid(orderGid) ?: orderGid} not found"))
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
   */
  private suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): ShopifyResult<T> {
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

    val errors = response.errors
    if (!errors.isNullOrEmpty()) {
      return Failure(
        ShopifyError.GraphqlError(
          message = errors.joinToString("; ") { it.message },
          codes = errors.mapNotNull { it.code() }.distinct(),
        ),
      )
    }
    val data = response.data ?: return Failure(ShopifyError.GraphqlError("empty response"))
    return Success(data)
  }
}

private fun GraphQLClientError.code(): String? = when (val code = extensions?.get("code")) {
  is String -> code
  is JsonPrimitive -> code.contentOrNull
  else -> null
}
