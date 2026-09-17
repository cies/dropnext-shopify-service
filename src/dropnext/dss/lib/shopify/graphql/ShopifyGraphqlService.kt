package dropnext.dss.lib.shopify.graphql

import dev.forkhandles.result4k.Result
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getproductbyid.Product
import kotlin.time.Duration


/**
 * Per-shop Shopify Admin API surface. Handlers and workflows program against this interface;
 * production wires [HttpShopifyGraphqlService] (real Graphql over HTTPS, per-shop access token);
 * tests wire `FakeShopifyGraphqlService` (in-memory stubs).
 *
 * Each instance is bound to a single [shop] — the per-shop access token is injected at
 * construction and never leaks back across the API.
 *
 * Methods are **single-shot Graphql primitives**, one per `.graphql` file, and everyone answers a [ShopifyResult]:
 * the transport failure, the top-level Graphql errors, and a mutation payload's `userErrors`
 * are all triaged once, here, so no caller has to know the wire shape.
 * Multistep orchestrations (scan-then-register, load-then-create) live as workflow functions under
 * `dropnext.dss.workflow.*` and compose these primitives.
 */
interface ShopifyGraphqlService {

  /** The [ShopDomain] this service is bound to. */
  val shop: ShopDomain

  // ---------- single-shot reads ----------

  /** `ShopIdentity` — used post-OAuth to capture the canonical `*.myshopify.com` host and legacy shop id. */
  suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo>

  /** `ProductsCount` — how many products the shop has; the install page shows it as proof that the token reads the catalogue. */
  suspend fun productCount(): ShopifyResult<ProductCount>

  /** `CurrentAppInstallationAccessScopes` — the scopes the shop granted this app, for a token whose exchange answer is long gone. */
  suspend fun accessScopeHandles(): ShopifyResult<List<String>>

  /**
   * `GetProductById` — one page of a product's variants, the first unless [variantsAfter] names Shopify's cursor to a
   * later one; a successful `null` means Shopify has no such product. The answer is a whole product only when its
   * [ShopProduct.nextVariantsCursor] is `null`: mirror a product through `loadShopifyProduct`, which follows the cursor.
   */
  suspend fun productById(productGid: String, variantsAfter: String? = null): ShopifyResult<ShopProduct?>

  /**
   * `GetProductVariantIdsPage` — one page of the shop's variants, each with its product. [after] is Shopify's own cursor, passed back as it came;
   * `null` starts at the beginning. Single-shot on purpose — walking to the end is a workflow's job, because that
   * walk has to pace itself against the shop's rate budget and this layer runs one operation.
   */
  suspend fun productVariantIdsPage(first: Int, after: String?): ShopifyResult<ShopifyCatalogPage>

  /** `GetOrderForDss` — the order snapshot the fulfillment workflows and the monolith order sync work from; [ShopifyError.NotFound] when Shopify has no such order. */
  suspend fun orderForDss(orderGid: String): ShopifyResult<Order>

  // ---------- fulfillment primitives (composed by the workflow functions) ----------

  /** `FulfillmentCancel` — cancels a single Shopify fulfillment by GID; a success when Shopify's answer carries the fulfillment as cancelled, whatever user errors come with it. */
  suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit>

  /** `FulfillmentCreateWithLineItems` — creates one fulfillment spanning one or more fulfillment orders and answers its id. */
  suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId>

  /** `FulfillmentEventCreate` — appends a tracking event to an existing fulfillment and answers the event's id. */
  suspend fun createFulfillmentEvent(
    fulfillmentGid: String,
    status: FulfillmentEventStatus,
    happenedAt: String,
    message: String?,
  ): ShopifyResult<ShopifyFulfillmentEventId>

  // ---------- webhook subscription primitives (composed by workflow/registerShopifyWebhooks) ----------

  /** `GetWebhookSubscriptions` — every subscription this app has on the shop, whatever its topic or address. */
  suspend fun webhookSubscriptions(): ShopifyResult<List<WebhookSubscriptionStatus>>

  /** `RegisterWebhook` — subscribes the shop to one topic at [callbackUrl] with optional projected fields; answers the subscription's GID. */
  suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<String>

  /**
   * `UpdateWebhookSubscription` — points an existing subscription at [callbackUrl] with [includeFields] and answers it as
   * Shopify reports it afterwards. [includeFields] `null` resets it to the full payload: the client leaves out a variable
   * whose value is `null`, so the operation declares `= null` as the variable's default, which is what Shopify then reads.
   */
  suspend fun updateWebhookSubscription(
    subscriptionId: String,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<WebhookSubscriptionStatus>

  /** `DeleteWebhookSubscription` — removes one subscription, so Shopify stops delivering its topic to its address. */
  suspend fun deleteWebhookSubscription(subscriptionId: String): ShopifyResult<Unit>
}

/** What every [ShopifyGraphqlService] call returns. */
typealias ShopifyResult<T> = Result<T, ShopifyError>

/**
 * Why a Shopify call produced no answer.
 *
 * [UserError] and [NotFound] are Shopify refusing what we sent (a `400` / `404` for the caller);
 * [TokenRejected] is Shopify refusing *us* (a `401`, and no retry can help);
 * [GraphqlError], [HttpError] and [Network] are Shopify or the wire failing (a `502`);
 * [Undecodable] is an answer the generated client can no longer read;
 * [Truncated] is a list longer than this service loads, which no retry changes (a `500`);
 * [TimedOut] is work spanning many calls that did not finish within its budget (a `502`).
 */
sealed interface ShopifyError {
  val message: String

  /**
   * Whether asking again can go differently. Decided here, where each failure is recognised, so every reader draws the
   * same line: a webhook answered `502` for a failure a redelivery cannot fix only burns Shopify's retries, and enough
   * failed deliveries cost the subscription.
   */
  val isRetryable: Boolean

  /** The request never got an answer: a refused or reset connection, a timeout. */
  data class Network(override val message: String) : ShopifyError {
    override val isRetryable: Boolean get() = true
  }

  /**
   * Shopify answered `401`: the Admin token is no longer valid, which is what an uninstall looks like
   * from here. Kept apart from [HttpError] because it is the one failure a retry cannot fix and the
   * one an operator has to act on (reinstall the app), so it must never read as a network blip.
   */
  data class TokenRejected(val httpStatus: Int) : ShopifyError {
    override val message: String
      get() = "Shopify rejected the Admin token (HTTP $httpStatus): the app was uninstalled or the token revoked"
    override val isRetryable: Boolean get() = false
  }

  /**
   * Shopify answered an HTTP error instead of a Graphql envelope: `429` when throttled, `5xx` when
   * down, `423` for a locked shop. Only the status is kept: the body is Shopify's error page and
   * would otherwise end up in our logs.
   */
  data class HttpError(val httpStatus: Int) : ShopifyError {
    override val message: String get() = "Shopify answered HTTP $httpStatus"

    /** Throttling, a timeout and Shopify's own `5xx` pass; `402` (a frozen shop), `403`, `404` and `423` (a locked shop) come back the same. */
    override val isRetryable: Boolean get() = httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
  }

  /**
   * Shopify answered with top-level `errors`, or without the data asked for. These arrive with HTTP `200`, and [codes]
   * holds their `extensions.code` (`THROTTLED`, `ACCESS_DENIED`, `MAX_COST_EXCEEDED`, a validation error's code): the
   * only thing that tells a throttled request from one that will never be allowed.
   *
   * [rateBudget] is what Shopify said was left of the shop's bucket when it refused, if it said. Shopify throttles with
   * this error rather than with an HTTP status, and a throttled answer still carries the cost block, so this is where a
   * caller learns how long to wait before asking again.
   */
  data class GraphqlError(
    override val message: String,
    val codes: List<String> = emptyList(),
    val rateBudget: ShopifyRateBudget? = null,
  ) : ShopifyError {
    /**
     * Of the coded errors only throttling and Shopify's internal errors pass. An error without any code is retried too:
     * most of those are this service's own, for an answer without the object it promised (no data, a mutation payload
     * without its fulfillment), and some are Shopify's, such as a variable it could not read, which no retry fixes.
     * The two cannot be told apart here, and the costs are lopsided: a wrong retry is eight wasted redeliveries, a
     * wrong refusal is a lost order.
     */
    override val isRetryable: Boolean get() = codes.isEmpty() || codes.any { it in RETRYABLE_GRAPHQL_ERROR_CODES }
  }

  /** A mutation payload's `userErrors`: the business rules of the shop refused the mutation. */
  data class UserError(val messages: List<String>) : ShopifyError {
    override val message: String get() = messages.joinToString("; ")
    override val isRetryable: Boolean get() = false
  }

  /** The referenced resource does not exist on the shop. */
  data class NotFound(override val message: String) : ShopifyError {
    override val isRetryable: Boolean get() = false
  }

  /**
   * A success whose body the generated client could not read: Shopify's answer moved away from the schema this build was
   * compiled against, and it reads the same on every attempt. [detail] is the decoder's complaint, which quotes the part
   * of the body it choked on; it is logged with the rest of this error, and `toDssError` keeps it from a caller.
   */
  data class Undecodable(val detail: String) : ShopifyError {
    override val message: String get() = "Shopify's answer could not be read: $detail"
    override val isRetryable: Boolean get() = false
  }

  /**
   * Shopify has more of [connection] than the [pageSize] this service asks for, so the snapshot is the first page of a
   * longer list. Answered instead of the snapshot, because every reader downstream treats a list as complete: a mirror
   * computed from a truncated one silently drops what it never saw, and a prune deletes it. Asking again loads the same
   * page, so no retry helps — the query has to page.
   */
  data class Truncated(val connection: String, val pageSize: Int) : ShopifyError {
    override val message: String get() = "Shopify has more $connection than the $pageSize this service loads"
    override val isRetryable: Boolean get() = false
  }

  /**
   * Work that spans many Shopify calls — reading a whole catalog — did not finish within [budget]. Its caller has
   * stopped waiting by then, so going on would spend the shop's rate budget on an answer nobody reads. Retryable: the
   * usual cause is a bucket drained by a burst of other traffic, which a later attempt may not meet.
   */
  data class TimedOut(val what: String, val budget: Duration) : ShopifyError {
    override val message: String get() = "$what did not finish within $budget"
    override val isRetryable: Boolean get() = true
  }
}

/**
 * A short, countable name for the failure, for a `key=value` log field that alerts and dashboards can group on; never
 * the message, which may be long or bulky. One place, so a webhook's summary line and the warm-up's count the same.
 */
val ShopifyError.errorLabel: String
  get() = when (this) {
    is ShopifyError.Network -> "shopify_network"
    is ShopifyError.HttpError -> "shopify_http_$httpStatus"
    is ShopifyError.TokenRejected -> "shopify_token_rejected"
    is ShopifyError.GraphqlError -> "shopify_graphql"
    is ShopifyError.UserError -> "shopify_user_error"
    is ShopifyError.NotFound -> "shopify_not_found"
    is ShopifyError.Undecodable -> "shopify_undecodable"
    is ShopifyError.Truncated -> "shopify_truncated"
    is ShopifyError.TimedOut -> "shopify_timed_out"
  }

/** The `extensions.code` values Shopify documents as passing conditions: its rate limit and its own internal error. */
private val RETRYABLE_GRAPHQL_ERROR_CODES = setOf("THROTTLED", "INTERNAL_SERVER_ERROR")

/** The shop's canonical host and numeric id, as Shopify reports them. */
data class ShopIdentityInfo(
  val shopId: ShopifyShopId?,
  val domain: ShopDomain,
)

/**
 * How many of a product's variants one `GetProductById` call asks for. A choice of ours under Shopify's 250 per page;
 * a product with more is paged, and the page cap of whoever pages it is counted in these.
 */
const val PRODUCT_VARIANTS_PAGE_SIZE: Int = 100

/**
 * A product together with the shop's currency, which the product payload itself does not carry, and what Shopify said
 * was left of the shop's point bucket when it answered. [rateBudget] is `null` when Shopify sent no cost block; only a
 * caller that paces itself over many products reads it, and the webhook path ignores it.
 *
 * [nextVariantsCursor] is Shopify's cursor to the product's next page of variants, and `null` once
 * [product] holds them all.
 */
data class ShopProduct(
  val product: Product,
  val shopCurrencyCode: String,
  val rateBudget: ShopifyRateBudget? = null,
  val nextVariantsCursor: String? = null,
)

/**
 * One page of the shop's variants. [nextCursor] is Shopify's `endCursor` while it has more and `null` at the end, so a
 * caller loops until it is `null` and never has to understand what a cursor is.
 */
data class ShopifyCatalogPage(
  val entries: List<ShopifyCatalogEntry>,
  val nextCursor: String?,
  val rateBudget: ShopifyRateBudget? = null,
)

/** One variant of the shop's catalog and the product it hangs under. */
data class ShopifyCatalogEntry(
  val productId: ShopifyProductId,
  val productVariantId: ShopifyVariantId,
)

/** One line of a fulfillment to create: [quantity] of a fulfillment-order line item. */
data class FulfillmentLine(
  val fulfillmentOrderId: String,
  val lineItemId: String,
  val quantity: Int,
)

data class FulfillmentTracking(
  val company: String?,
  val number: String,
  val url: String?,
)
