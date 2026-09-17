package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.mapper.toProductVariantItems
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The pages a product webhook may load, 500 variants at the current page size. Every page and the monolith upsert share
 * the webhook's four-second budget, and running out of it is a `502` Shopify redelivers, again and again for the same
 * product, until it removes a subscription that keeps failing. A larger product answers [ShopifyError.Truncated]
 * instead, a `200` with an error line; such a product reaches the monolith only through the product fetch.
 */
const val WEBHOOK_MAX_VARIANT_PAGES: Int = 5

/**
 * Mirrors one Shopify product into the monolith after a `products/create` or `products/update` webhook:
 * loads it with every page of its variants (the webhook body is id-only), maps them, and upserts them.
 * The outcome tells the handler whether a redelivery of the webhook is worth asking for.
 */
suspend fun syncShopifyProductToMonolith(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  productGid: String,
): WebhookMirrorOutcome {
  val shopProduct = when (val loaded = loadShopifyProduct(shopify, productGid, maxPages = WEBHOOK_MAX_VARIANT_PAGES)) {
    is Failure -> {
      log.warn { "Webhook product: could not load productGid=$productGid error=${loaded.reason.message}" }
      return WebhookMirrorOutcome.ShopifyFailed(loaded.reason)
    }
    is Success -> loaded.value ?: run {
      log.info { "Webhook product: Shopify has no product productGid=$productGid (deleted meanwhile?)" }
      return WebhookMirrorOutcome.Skipped(WebhookSkipReason.PRODUCT_GONE)
    }
  }
  val variantCount = shopProduct.product.variants.edges.size
  log.info { "Webhook product loaded id=$productGid title=${shopProduct.product.title} variants=$variantCount" }
  val variantItems = shopProduct.product.toProductVariantItems(shopProduct.shopCurrencyCode)
  if (variantItems.isEmpty()) return WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES)

  val request = UpsertProductVariantsRequest(
    shopifySubdomain = shopify.shop.subdomainOnly,
    productVariants = variantItems,
    // The load failed rather than return a partial variant list, so the product is complete unless the mapper dropped
    // a variant. Only then may the monolith soft-delete the variants the request leaves out.
    productVariantsComplete = variantItems.size == variantCount,
  )
  return when (val result = monolith.upsertProductVariants(request)) {
    is Success -> {
      val written = result.value
      log.info {
        "Monolith upsert variants ok: upserted=${written.upserted} deleted=${written.deleted} " +
          "complete=${request.productVariantsComplete}"
      }
      WebhookMirrorOutcome.Mirrored
    }
    is Failure -> {
      logMonolithFailure("upsertProductVariants", result.reason)
      WebhookMirrorOutcome.MonolithFailed(result.reason)
    }
  }
}
