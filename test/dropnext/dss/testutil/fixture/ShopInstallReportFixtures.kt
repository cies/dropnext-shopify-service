package dropnext.dss.testutil.fixture

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookTopicRegistration


internal val EVERY_SCOPE_HANDLE: List<String> = ShopifyAccessScope.entries.map { it.handle }

/** An install that went right: the token saved, every scope granted, three products counted, no topic registered. */
internal fun shopInstallReport(
  shop: ShopDomain = ACME_SHOP,
  monolithPersist: MonolithPersistOutcome = MonolithPersistOutcome.Persisted(storeId = StoreId(1L)),
  topics: List<WebhookTopicRegistration> = emptyList(),
  obsolete: List<ObsoleteWebhookSubscription> = emptyList(),
  productCount: ProductCount? = ProductCount(count = 3, isExact = true),
  grantedScopeHandles: List<String> = EVERY_SCOPE_HANDLE,
  shopId: ShopifyShopId? = ShopifyShopId(9988L),
  webhookCallbackUrl: String = "https://dss.test/webhooks/shopify",
): ShopInstallReport =
  ShopInstallReport(
    shop = shop,
    shopId = shopId,
    accessScopes = ShopifyAccessScopeReport.from(grantedScopeHandles),
    monolithPersist = monolithPersist,
    productCount = productCount,
    webhookCallbackUrl = webhookCallbackUrl,
    webhooks = WebhookRegistrationReport(topics, obsolete),
  )
