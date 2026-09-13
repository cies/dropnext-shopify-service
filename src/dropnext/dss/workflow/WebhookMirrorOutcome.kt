package dropnext.dss.workflow

import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.shopify.graphql.ShopifyError


/**
 * What mirroring one Shopify webhook into the monolith came to. The handler answers Shopify from
 * this: a `200` when redelivering the same webhook could not go better, a `5xx` when it could, so
 * Shopify's own redelivery (up to eight times in four hours, with a growing interval) is the retry.
 */
sealed interface WebhookMirrorOutcome {
  /** The monolith has what the webhook announced, whether it was new to it or not. */
  data object Mirrored : WebhookMirrorOutcome

  /** Nothing to mirror, or nothing this service can do about it: a redelivery would end the same way. */
  data class Skipped(val reason: WebhookSkipReason) : WebhookMirrorOutcome

  data class ShopifyFailed(val error: ShopifyError) : WebhookMirrorOutcome

  data class MonolithFailed(val error: MonolithError) : WebhookMirrorOutcome

  /** The shop's token is not in memory and the monolith, which holds it, could not be asked: not a shop without one. */
  data object TokenUnavailable : WebhookMirrorOutcome

  /** The work outlived the time Shopify waits for an answer and was cancelled; the redelivery starts it again. */
  data object TimedOut : WebhookMirrorOutcome

  /** Every mirror slot was taken when the delivery arrived, so no work was started; the redelivery finds a quieter service. */
  data object Overloaded : WebhookMirrorOutcome
}

/**
 * Why a delivery was skipped, as a closed set so a log query or a dashboard filter can count each kind. The delivery's
 * summary line is the one line a skip leaves, so the reasons an operator has to act on are logged at error level there.
 */
enum class WebhookSkipReason {
  /** Neither the header nor the body named a shop: a subscription made by hand, or Shopify changed its delivery. */
  NO_SHOP_DOMAIN,

  /**
   * No Admin token in memory nor at the monolith. Every delivery of the shop ends here until someone configures
   * `DSS_SHOP_ACCESS_TOKENS`, completes the OAuth install or persists a token through `PUT /stores/api-key`.
   */
  NO_ADMIN_TOKEN,

  /** The body carries no id to load the resource by. */
  NO_RESOURCE_ID,

  /** Shopify no longer has the product the webhook announced; its `products/delete` follows. */
  PRODUCT_GONE,

  /** No variant-backed line on a fulfillment order: a tip or custom-line order, nothing the monolith could match. */
  NO_MAPPABLE_LINES,

  /** A topic the service acknowledges without mirroring: `orders/updated`, and anything it never registered. */
  TOPIC_NOT_MIRRORED,
}

/**
 * Whether a redelivery has a chance: Shopify or the monolith not answering (in time), throttling, or answering a `5xx`
 * passes; a token Shopify refuses, a request either side refuses, a resource that is gone or an answer that no longer
 * reads does not, and answering a `5xx` for those would only make Shopify hammer a closed door. An answer that no
 * longer reads is judged the same on both sides: a success whose body is not the contract's DTO is the contract having
 * drifted, which only a deploy fixes, and the next attempt reads the same.
 */
val WebhookMirrorOutcome.isTransient: Boolean
  get() = when (this) {
    is WebhookMirrorOutcome.Mirrored, is WebhookMirrorOutcome.Skipped -> false
    is WebhookMirrorOutcome.ShopifyFailed -> error.isRetryable
    is WebhookMirrorOutcome.MonolithFailed -> when (val error = error) {
      is MonolithError.Transport -> true
      is MonolithError.Rejected -> error.status >= 500
      is MonolithError.Undecodable -> false
    }
    is WebhookMirrorOutcome.TokenUnavailable, is WebhookMirrorOutcome.TimedOut, is WebhookMirrorOutcome.Overloaded -> true
  }
