package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondTextError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.path.Paths
import dropnext.dss.presentation.renderOAuthInstallPage
import dropnext.dss.workflow.installShop
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.util.getOrFail


private val log = KotlinLogging.logger {}

/**
 * Handlers for the Shopify install / OAuth-callback flow. The callback verifies what Shopify sent
 * (HMAC, signed state), exchanges the code, and leaves the rest of the installation to
 * [installShop]. Failures in this flow are plain-text errors, not JSON: a merchant's browser is
 * what reads them.
 */
class OAuthHandlers(
  private val dssBaseUrl: String,
  private val shopifyOAuthService: ShopifyOAuthService,
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopTokens: ShopTokenStore,
  private val shopifyHmacVerifierService: ShopifyHmacVerifierService,
) {

  suspend fun handleInstall(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespondText(rawShop, "shop") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val state = shopifyOAuthService.signedState(shop)
      call.respondRedirect(shopifyOAuthService.authorizeUrl(shop, state))
    }
  }

  suspend fun handleOAuthCallback(call: ApplicationCall) {
    val params = call.request.queryParameters
    val hmac = params.getOrFail("hmac")
    val rawShop = params.getOrFail("shop")
    val shop = call.shopDomainOrRespondText(rawShop, "shop") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val state = params.getOrFail("state")
      val code = params.getOrFail("code")

      if (!shopifyHmacVerifierService.verifyOAuthCallback(params, hmac)) {
        return@withMdcEntries call.respondTextError(DssError.InvalidSignature("Invalid HMAC"))
      }
      if (!shopifyOAuthService.isSignedStateValid(state, shop)) {
        return@withMdcEntries call.respondTextError(DssError.InvalidSignature("Invalid or expired state"))
      }

      val grant = when (val exchanged = shopifyOAuthService.exchangeCode(shop, code)) {
        is Success -> exchanged.value
        is Failure -> {
          log.warn { "OAuth code exchange failed: ${exchanged.reason.message}" }
          return@withMdcEntries call.respondTextError(exchanged.reason.toDssError())
        }
      }

      // Remembered under the shop Shopify redirected for; the workflow remembers it again under the
      // canonical domain once it has asked Shopify, so the factory can hand out a service right away.
      shopTokens.remember(shop, grant.token)
      val shopify = (shopifyGraphqlServiceFactory.forShop(shop) as? ShopLookup.Found)?.value
        ?: return@withMdcEntries call.respondTextError(DssError.UpstreamFailure("could not build a Shopify service for $shop"))

      val report = installShop(
        shopify = shopify,
        monolith = monolithService,
        tokens = shopTokens,
        token = grant.token,
        grantedScopeHandles = grant.scopeHandles,
        webhookCallbackUrl = "$dssBaseUrl${Paths.webhooksShopify}",
      )
      call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
      call.respondText(renderOAuthInstallPage(report), ContentType.Text.Html, HttpStatusCode.OK)
    }
  }
}
