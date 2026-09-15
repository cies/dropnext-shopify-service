package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.token.ShopTokenStore
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Everything that happens after the OAuth code exchange handed us a shop's Admin token and its scopes: hold the
 * scopes against what the service needs, learn the shop's canonical domain and id, remember the token under that
 * domain, persist it to the monolith, count the catalogue as a smoke test, and register the webhook subscriptions.
 *
 * Nothing here fails the installation: each step that cannot be completed is reported on the
 * confirmation page instead, because the token is already ours and a merchant who sees an error
 * page would only reinstall.
 */
suspend fun installShop(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  tokens: ShopTokenStore,
  token: ShopifyAdminToken,
  grantedScopeHandles: List<String>,
  webhookCallbackUrl: String,
): ShopInstallReport {
  val accessScopes = ShopifyAccessScopeReport.from(grantedScopeHandles)
  if (!accessScopes.isComplete) {
    log.warn { "OAuth grant lacks required scopes missing=${accessScopes.missing.joinToString(",") { it.handle }}" }
  }
  val identity = when (val loaded = shopify.shopIdentity()) {
    is Success -> loaded.value
    is Failure -> {
      log.warn { "Shop identity lookup failed: ${loaded.reason.message}" }
      null
    }
  }
  val domain = identity?.domain ?: shopify.shop
  tokens.remember(domain, token)
  // The MDC names the shop Shopify redirected for; the canonical domain is worth naming only where it differs.
  val canonical = domain.normalizedShopifyHost.takeIf { it != shopify.shop.normalizedShopifyHost }
  log.info { "OAuth token cached in memory" + canonical?.let { " canonical_shop=$it" }.orEmpty() }

  val monolithPersist = persistTokenToMonolith(monolith, domain, identity?.shopId, token)

  val productCount = when (val counted = shopify.productCount()) {
    is Success -> counted.value.also {
      log.info { "Product count after OAuth: products=${it.count} exact=${it.isExact}" }
    }
    is Failure -> {
      log.warn { "Product count after OAuth failed: ${counted.reason.message}" }
      null
    }
  }

  return ShopInstallReport(
    shop = domain,
    shopId = identity?.shopId,
    accessScopes = accessScopes,
    monolithPersist = monolithPersist,
    productCount = productCount,
    webhookCallbackUrl = webhookCallbackUrl,
    webhooks = registerShopifyWebhooks(shopify, webhookCallbackUrl),
  )
}

/** Persists the freshly obtained Shopify Admin token to the monolith; answers what the page renders. */
suspend fun persistTokenToMonolith(
  monolith: MonolithService,
  shop: ShopDomain,
  shopId: ShopifyShopId?,
  token: ShopifyAdminToken,
): MonolithPersistOutcome {
  val request = UpdateStoreApiKeyRequest(
    shopifySubdomain = shop.subdomainOnly,
    // Null when the identity lookup failed: the monolith keeps the id it has. Zero used to stand in for
    // this and locked a fresh store to shop id zero, so every later, real install of it was a mismatch.
    shopifyShopId = shopId?.value,
    apiKey = token.value,
  )
  return when (val result = monolith.putStoreApiKey(request)) {
    is Success -> {
      log.info { "Monolith store api-key updated storeId=${result.value}" }
      MonolithPersistOutcome.Persisted(storeId = result.value)
    }

    is Failure -> {
      logMonolithFailure("putStoreApiKey", result.reason)
      when (val error = result.reason) {
        is MonolithError.Rejected -> MonolithPersistOutcome.Failed(httpStatus = error.status, detail = error.body.message)
        is MonolithError.Undecodable -> MonolithPersistOutcome.Failed(httpStatus = error.status, detail = error.message)
        is MonolithError.Transport -> MonolithPersistOutcome.Failed(httpStatus = null, detail = error.message)
      }
    }
  }
}
