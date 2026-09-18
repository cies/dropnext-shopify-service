package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.shopify.fulfillmentGid
import dropnext.dss.lib.shopify.graphql.FulfillmentTracking
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult


/**
 * Applies [mutations] to Shopify in order.
 *
 * A user error refuses that mutation alone, so the run goes on: it says nothing about the next one, and stopping would
 * block the same order on every run.
 * Any other failure stops the run, because Shopify is unreachable or unwell and the next call would fare no better.
 * So does a [budget] that would not hold another call: the mutations not sent are left to the caller's retry.
 */
suspend fun effectShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  mutations: List<ShopifyMutation>,
  budget: ShipmentSyncBudget,
): MutationEffectReport {
  val effects = mutableListOf<MutationEffect>()
  mutations.forEach { mutation ->
    if (!budget.allowsAnotherCall()) {
      return MutationEffectReport(effects, MutationStop(mutation = null, budget.timedOut()))
    }
    val effect = when (val sent = shopifyGqlService.send(mutation)) {
      is Success -> sent.value
      is Failure -> when (val cause = sent.reason) {
        is ShopifyError.UserError -> MutationEffect.Refused(mutation, cause.messages)
        else -> return MutationEffectReport(effects, MutationStop(mutation, cause))
      }
    }
    effects += effect
  }
  return MutationEffectReport(effects)
}

private suspend fun ShopifyGraphqlService.send(mutation: ShopifyMutation): ShopifyResult<MutationEffect> =
  when (mutation) {
    is ShopifyMutation.FulfillmentCancel ->
      when (val canceled = cancelFulfillment(fulfillmentGid(mutation.fulfillmentId))) {
        is Failure -> canceled
        is Success -> Success(MutationEffect.Canceled(mutation))
      }

    is ShopifyMutation.FulfillmentCreate -> {
      val tracking = FulfillmentTracking(
        company = mutation.carrier,
        number = mutation.trackingNumber,
        url = mutation.trackingUrl,
      )
      when (val created = createFulfillment(mutation.lineItems, tracking, mutation.notifyCustomer)) {
        is Failure -> created
        is Success -> Success(MutationEffect.Created(mutation, created.value))
      }
    }
  }
