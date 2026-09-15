package dropnext.dss.domain


/**
 * Everything the install confirmation page shows after an OAuth callback. [shopId] is `null` and
 * [productCount] is `null` when the respective Shopify lookup failed: the install still
 * succeeded, the page just says less.
 */
data class ShopInstallReport(
  val shop: ShopDomain,
  val shopId: ShopifyShopId?,
  val accessScopes: ShopifyAccessScopeReport,
  val monolithPersist: MonolithPersistOutcome,
  val productCount: ProductCount?,
  val webhookCallbackUrl: String,
  val webhooks: WebhookRegistrationReport,
)
