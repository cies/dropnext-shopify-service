package dropnext.dss.testutil.helper

import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.mapper.mapOrderForMonolith
import dropnext.graphql.generated.getorderfordss.Order


/** The mapper's request alone, keyed by the id the snapshot's own gid carries, for tests that have no use for the omissions. */
internal fun orderToCreateShopifyOrderRequest(shopifySubdomain: String, order: Order): CreateShopifyOrderRequest =
  mapOrderForMonolith(shopifySubdomain, ShopifyOrderId(legacyIdFromGid(order.id)!!), order).request
