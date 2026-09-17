package dropnext.dss.path


/**
 * Inbound HTTP paths this service exposes. Single source of truth for route definitions,
 * log strings, UI text, and the `/webhooks/shopify` callback URL we register with Shopify.
 *
 * Canonical contract for DSS-internal endpoints: `src/resources/monolith-dss-openapi.json` (the checked-in
 * copy of the spec the monolith serves at `/openapi.json`).
 * Keep these in sync with the spec — [dropnext.dss.lib.monolith.OutBoundMonolithPaths] is generated
 * from it; see [dropnext.dss.lib.shopify.oauth.OutBoundShopifyOAuthPaths] for the other outbound paths.
 */
@Suppress("ConstPropertyName") // Less shouty field names.
object Paths {
  // --- Diagnostics ---
  const val index = "/"
  const val health = "/health"
  const val api = "/api"
  const val apiRedirectUrl = "/api/redirect-url"

  // --- Webhook subscriptions (called by the monolith) ---
  const val apiCheck = "/api/check"
  const val apiWebhooksRegister = "/api/webhooks/register"

  // --- Shopify OAuth ---
  const val install = "/install"

  /** Default OAuth callback path; the actual value comes from `Config.oauthRedirectPath` (env `OAUTH_REDIRECT_PATH`). */
  const val defaultOAuthCallback = "/oauth/callback"

  // --- Shopify webhooks ---
  const val webhooksShopify = "/webhooks/shopify"

  // --- DSS internal REST (called by monolith) ---
  const val storesApiKey = "/stores/api-key"
  const val syncShipmentsWithFulfillments = "/sync-shipments-with-fulfillments"
  const val trackingUpdate = "/tracking-update"
  const val productsCatalog = "/products/catalog"
  const val productsFetch = "/products/fetch"
}
