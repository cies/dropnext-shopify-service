package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The variants of a product Shopify deleted get soft-deleted in the monolith too. Only the product
 * id is known: the webhook carries nothing else and the product can no longer be looked up, so the
 * monolith resolves the variants from its own copy. Needs no Shopify Admin token.
 */
suspend fun deleteShopifyProductFromMonolith(
  monolith: MonolithService,
  shop: ShopDomain,
  productId: ShopifyProductId,
): WebhookMirrorOutcome {
  val request = DeleteProductVariantsRequest(
    shopifySubdomain = shop.subdomainOnly,
    productId = productId.value,
  )
  return when (val result = monolith.deleteProductVariants(request)) {
    is Success -> {
      log.info { "Monolith delete variants ok: ${result.value} deleted productId=$productId" }
      WebhookMirrorOutcome.Mirrored
    }
    is Failure -> {
      logMonolithFailure("deleteProductVariants", result.reason, "productId=$productId")
      WebhookMirrorOutcome.MonolithFailed(result.reason)
    }
  }
}
