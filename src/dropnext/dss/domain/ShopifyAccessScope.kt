package dropnext.dss.domain


/**
 * The access scopes the install asks a merchant to grant, in the order the authorize URL lists them. Hardcoded, never a
 * setting: a change takes a deploy and a re-authorization of every installed shop either way. [neededFor] is what the
 * install page tells whoever installs.
 *
 * **NOTE**: Mirror the list in the Shopify Partner Dashboard configuration page for this "App".
 */
enum class ShopifyAccessScope(val handle: String, val neededFor: String) {
  READ_PRODUCTS("read_products", "Loading a product for the product webhooks, and the product count on the install page."),
  READ_ORDERS("read_orders", "Loading an order for the order webhook, the shipment sync and tracking updates."),
  WRITE_MERCHANT_MANAGED_FULFILLMENT_ORDERS(
    "write_merchant_managed_fulfillment_orders",
    "Seeing and fulfilling order lines at the merchant's own locations.",
  ),
  // Shopify leaves fulfillment orders the app has no scope for out of an order rather than refusing the query: without
  // this one, a line routed to a fulfillment service app looks as if the order had no open line for it.
  WRITE_THIRD_PARTY_FULFILLMENT_ORDERS(
    "write_third_party_fulfillment_orders",
    "Seeing and fulfilling order lines at locations a fulfillment service app manages.",
  ),
  // Shopify documents no scope for `fulfillmentCancel`; this one is expected to cover it.
  WRITE_FULFILLMENTS("write_fulfillments", "Adding tracking events to a fulfillment, and canceling a fulfillment."),
}

/**
 * The scopes a grant of [grantedHandles] does not cover, in entry order. Shopify may answer only the write scope of a
 * resource it granted both for, because writing includes reading, so a `read_` scope counts as covered by its `write_`.
 */
fun missingShopifyAccessScopes(grantedHandles: Collection<String>): List<ShopifyAccessScope> {
  val granted = grantedHandles.map { it.trim() }.toSet()
  return ShopifyAccessScope.entries.filterNot { scope -> scope.coveringHandles.any { it in granted } }
}

/** A read scope is covered by itself or by the write scope of its resource; a write scope only by itself. */
private val ShopifyAccessScope.coveringHandles: List<String>
  get() = if (handle.startsWith("read_")) listOf(handle, "write_" + handle.removePrefix("read_")) else listOf(handle)
