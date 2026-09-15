package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic


/**
 * The resource id and nothing more: the product or the order is loaded through Graphql right after the delivery, so the
 * rest of the payload (megabytes for a product with many variants) would only be bytes to verify and discard. It does
 * not spare the app protected customer data access: loading the order, with its shipping address and email, needs that
 * access just as a full payload would.
 */
private val idOnlyFields = listOf("id", "admin_graphql_api_id")

/**
 * Inbound webhook topics this service routes on. [Other] captures any unhandled topic header,
 * preserving the raw string so monitoring can flag unexpected topics by name.
 * A nullable enum would collapse "unknown topic 'foo/bar'" and "missing header" into the same `null`
 * and lose that signal.
 *
 * Each known case carries the matching Shopify Admin Graphql [subscriptionTopic] and the fields the subscription projects,
 * so the webhook registration (`workflow/registerShopifyWebhooks`) derives everything from [known]: adding a topic in one
 * place is enough, and removing one there has the registration delete the subscription it leaves behind.
 */
sealed interface ShopifyWebhookTopic {
  val raw: String

  /** Admin Graphql enum value used for outbound subscription registration. `null` for [Other]. */
  val subscriptionTopic: WebhookSubscriptionTopic?

  /**
   * The payload fields the subscription is restricted to; `null` means the full payload, both when a subscription is
   * created and when one is updated back to it.
   */
  val includeFields: List<String>? get() = null

  /** Shopify reports a full-payload subscription as `[]` and keeps no order of the names, so `null` matches `[]` and the names compare as a set. */
  fun matchesIncludeFields(reported: List<String>): Boolean = reported.toSet() == includeFields.orEmpty().toSet()

  /**
   * The subscription delivers what this topic's handler reads: the declared payload fields, every event (a filter would
   * drop some) and JSON, the only format the webhook parsers read. Its URI is compared on its own.
   */
  fun matchesSubscription(subscription: WebhookSubscriptionStatus): Boolean =
    matchesIncludeFields(subscription.includeFields) && !subscription.hasFilter && subscription.isJson

  data object ProductsCreate : ShopifyWebhookTopic {
    override val raw = "products/create"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_CREATE
    override val includeFields = idOnlyFields
  }

  data object ProductsUpdate : ShopifyWebhookTopic {
    override val raw = "products/update"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_UPDATE
    override val includeFields = idOnlyFields
  }

  /** The full payload: it is `{"id": …}` already, so a field list would only be one more thing Shopify could report back differently. */
  data object ProductsDelete : ShopifyWebhookTopic {
    override val raw = "products/delete"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_DELETE
  }

  data object OrdersCreate : ShopifyWebhookTopic {
    override val raw = "orders/create"
    override val subscriptionTopic = WebhookSubscriptionTopic.ORDERS_CREATE
    override val includeFields = idOnlyFields
  }

  data class Other(override val raw: String) : ShopifyWebhookTopic {
    override val subscriptionTopic: WebhookSubscriptionTopic? = null
  }

  companion object {
    /** Every named topic the service registers and handles, in install-time registration order. */
    val known: List<ShopifyWebhookTopic> = listOf(
      ProductsUpdate,
      ProductsCreate,
      ProductsDelete,
      OrdersCreate,
    )

    fun parse(header: String?): ShopifyWebhookTopic =
      when (val v = header?.trim().orEmpty()) {
        ProductsCreate.raw -> ProductsCreate
        ProductsUpdate.raw -> ProductsUpdate
        ProductsDelete.raw -> ProductsDelete
        OrdersCreate.raw -> OrdersCreate
        else -> Other(v)
      }
  }
}
