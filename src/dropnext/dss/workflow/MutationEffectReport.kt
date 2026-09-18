package dropnext.dss.workflow

import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.ShopifyError


/**
 * What [effectShopifyMutations] did: one effect per mutation Shopify answered, in the order they were sent, and the
 * mutation that stopped the run, if one did. The mutations after [stop] were not sent.
 */
data class MutationEffectReport(
  val effects: List<MutationEffect>,
  val stop: MutationStop? = null,
)

sealed interface MutationEffect {
  val mutation: ShopifyMutation

  data class Created(
    override val mutation: ShopifyMutation.FulfillmentCreate,
    val fulfillmentId: ShopifyFulfillmentId,
  ) : MutationEffect

  data class Canceled(override val mutation: ShopifyMutation.FulfillmentCancel) : MutationEffect

  /** Shopify's business rules said no to this one mutation ([messages] are its user errors); the others still ran. */
  data class Refused(override val mutation: ShopifyMutation, val messages: List<String>) : MutationEffect
}

/**
 * [mutation] could not be sent, or Shopify answered it with something other than a user error. [mutation] is `null`
 * when the run stopped before sending the next one, because its time budget would not hold another Shopify call.
 */
data class MutationStop(val mutation: ShopifyMutation?, val cause: ShopifyError)
