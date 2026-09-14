package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.mapper.toProductVariantItems
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Mirrors one Shopify product into the monolith after a `products/create` or `products/update` webhook:
 * loads it (the webhook body is id-only), maps its variants, and upserts them. The outcome tells the
 * handler whether a redelivery of the webhook is worth asking for.
 */
suspend fun syncShopifyProductToMonolith(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  productGid: String,
): WebhookMirrorOutcome {
  val shopProduct = when (val loaded = shopify.productById(productGid)) {
    is Failure -> {
      log.warn { "Webhook product: could not load productGid=$productGid error=${loaded.reason.message}" }
      return WebhookMirrorOutcome.ShopifyFailed(loaded.reason)
    }
    is Success -> loaded.value ?: run {
      log.info { "Webhook product: Shopify has no product productGid=$productGid (deleted meanwhile?)" }
      return WebhookMirrorOutcome.Skipped(WebhookSkipReason.PRODUCT_GONE)
    }
  }
  log.info { "Webhook product loaded id=$productGid title=${shopProduct.product.title}" }
  val variantItems = shopProduct.product.toProductVariantItems(shopProduct.shopCurrencyCode)
  if (variantItems.isEmpty()) return WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES)

  val request = UpsertProductVariantsRequest(
    shopifySubdomain = shopify.shop.subdomainOnly,
    productVariants = variantItems,
  )
  return when (val result = monolith.upsertProductVariants(request)) {
    is Success -> {
      log.info { "Monolith upsert variants ok: ${result.value} upserted" }
      WebhookMirrorOutcome.Mirrored
    }
    is Failure -> {
      logMonolithFailure("upsertProductVariants", result.reason)
      WebhookMirrorOutcome.MonolithFailed(result.reason)
    }
  }
}
