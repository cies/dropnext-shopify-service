package dropnext.dss.domain


/**
 * What a shop granted, held against [ShopifyAccessScope]. The install builds it from the token exchange's answer and the
 * check from what Shopify reports for the installation, so both judge a grant the same way.
 */
data class ShopifyAccessScopeReport(
  val grantedHandles: List<String>,
  val missing: List<ShopifyAccessScope>,
) {
  val isComplete: Boolean get() = missing.isEmpty()

  companion object {
    fun from(grantedHandles: List<String>): ShopifyAccessScopeReport =
      ShopifyAccessScopeReport(grantedHandles, missingShopifyAccessScopes(grantedHandles))
  }
}
