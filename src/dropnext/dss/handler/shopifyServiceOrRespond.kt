package dropnext.dss.handler

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopLookup
import io.ktor.server.application.ApplicationCall


/**
 * The shop's Graphql service, or the JSON answer that explains why there is none: a `401` for a shop without a token,
 * and a `502` the monolith retries when the token lookup itself did not get an answer. The OAuth callback keeps a
 * mapping of its own: there the token was remembered a moment earlier, so a lookup that still finds nothing is not a
 * missing token but a failure on our side.
 */
suspend fun ApplicationCall.shopifyServiceOrRespond(
  factory: ShopifyGraphqlServiceFactory,
  shop: ShopDomain,
): ShopifyGraphqlService? =
  when (val lookup = factory.forShop(shop)) {
    is ShopLookup.Found -> lookup.value
    ShopLookup.Missing -> {
      respondError(DssError.MissingShopifyAdminToken)
      null
    }
    ShopLookup.Unavailable -> {
      respondError(DssError.ShopifyAdminTokenUnavailable)
      null
    }
  }
