package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.RegisterShopifyWebhooksRequest
import dropnext.dss.contract.RegisterShopifyWebhooksResponse
import dropnext.dss.contract.ShopifyStoreStatusResponse
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondContract
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.mapper.UNKNOWN_SHOPIFY_ACCESS_SCOPES
import dropnext.dss.mapper.toShopifyAccessScopesStatus
import dropnext.dss.mapper.toShopifyStoreStatusRows
import dropnext.dss.mapper.toShopifyWebhookRegistrationSummary
import dropnext.dss.path.Paths
import dropnext.dss.workflow.reportShopifyAccessProblem
import dropnext.dss.workflow.reregisterShopifyWebhooks
import dropnext.dss.workflow.scanShopifyWebhooks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.util.getOrFail
import java.time.Clock


private val log = KotlinLogging.logger {}

/**
 * The per-shop Shopify store endpoints the monolith calls, both behind the bearer auth
 * ([dropnext.dss.routing.shopifyStoreRoutes]): the status reads what Shopify has for a shop, the registration
 * brings the shop to what this version handles. Both answer the same rows.
 */
class ShopifyStoreHandlers(
  dssBaseUrl: String,
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  /** When an access problem was observed, the time the monolith's race rule compares. */
  private val clock: Clock,
) {
  private val webhookCallbackUrl = "$dssBaseUrl${Paths.webhooksShopify}"

  /**
   * Readiness for one shop: the token resolves, which of the scopes the install asks for its grant lacks, and, per
   * handled topic, what Shopify is subscribed to at our callback URL. The scan is read-only, so the question "is this
   * shop still subscribed" (Shopify removes a subscription whose deliveries keep failing) can be asked without a
   * reinstall. Behind the bearer auth: it drives a monolith lookup and two Shopify queries per call.
   */
  suspend fun handleShopifyStoreStatus(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shopify_subdomain")
    val shop = call.shopDomainOrRespond(rawShop, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      // The factory goes through the token store, monolith lookup included, so the status answers what a webhook would find.
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      // Not knowing the grant says nothing about the subscriptions, so a failed query is an answer, not an error.
      val accessScopes = when (val listed = shopify.accessScopeHandles()) {
        is Success -> ShopifyAccessScopeReport.from(listed.value).toShopifyAccessScopesStatus()
        is Failure -> {
          val reason = listed.reason
          log.warn { "shopify-store-status: access scopes query failed error=${reason.message}" }
          // A dead token found by a check is the same news as one found by a webhook, though the caller reads the 401
          // too. The scan would be refused with the same token, so the answer goes out now rather than after it.
          if (reason is ShopifyError.TokenRejected) {
            reportShopifyAccessProblem(monolithService, shop, reason, STORE_STATUS_OPERATION, clock.instant())
            return@withMdcEntries call.respondError(reason.toDssError())
          }
          UNKNOWN_SHOPIFY_ACCESS_SCOPES
        }
      }

      val webhooks = when (val scanned = scanShopifyWebhooks(shopify, webhookCallbackUrl)) {
        is Success -> scanned.value
        is Failure -> {
          log.warn { "shopify-store-status: webhook subscriptions query failed error=${scanned.reason.message}" }
          // Nothing was reported yet: a token the scopes query saw refused has answered above.
          reportShopifyAccessProblem(monolithService, shop, scanned.reason, STORE_STATUS_OPERATION, clock.instant())
          return@withMdcEntries call.respondError(scanned.reason.toScanDssError())
        }
      }

      val rows = webhooks.toShopifyStoreStatusRows()
      call.respondContract(
        ShopifyStoreStatusResponse.serializer(),
        ShopifyStoreStatusResponse(
          shopifySubdomain = shop.subdomainOnly,
          shopDomain = shop.normalizedShopifyHost,
          hasTokenMappedForShop = true,
          accessScopes = accessScopes,
          webhooks = rows.webhooks,
          obsoleteWebhooks = rows.obsoleteWebhooks,
        ),
      )
    }
  }

  /**
   * Registers one shop's webhook subscriptions the way an install does, without the merchant: how a change to the
   * handled topics or their payload fields reaches a shop installed before it. The monolith calls it once per shop it
   * wants to bring along. A `200` carries the rows the status answers, with what this run changed; a topic Shopify
   * refused is a row, not the status, because what the run did for the other topics stands. Running it again changes
   * nothing that is already right. The token is not touched: without the merchant there is no new one to exchange.
   */
  suspend fun handleRegisterShopifyWebhooks(call: ApplicationCall) {
    val request = call.receive<RegisterShopifyWebhooksRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      val report = when (val registered = reregisterShopifyWebhooks(shopify, webhookCallbackUrl)) {
        is Success -> registered.value
        is Failure -> {
          log.warn { "register-shopify-webhooks: webhook subscriptions query failed error=${registered.reason.message}" }
          // The monolith reads the 401 or 403 itself too; its reason texts make the two one notification.
          val observedAt = clock.instant()
          reportShopifyAccessProblem(monolithService, shop, registered.reason, "register-shopify-webhooks", observedAt)
          return@withMdcEntries call.respondError(registered.reason.toScanDssError())
        }
      }

      val rows = report.toShopifyStoreStatusRows()
      call.respondContract(
        RegisterShopifyWebhooksResponse.serializer(),
        RegisterShopifyWebhooksResponse(
          shopifySubdomain = shop.subdomainOnly,
          shopDomain = shop.normalizedShopifyHost,
          summary = report.toShopifyWebhookRegistrationSummary(),
          webhooks = rows.webhooks,
          obsoleteWebhooks = rows.obsoleteWebhooks,
        ),
      )
    }
  }
}


private const val STORE_STATUS_OPERATION = "shopify-store-status"

private val SUBSCRIPTIONS_UNLISTABLE = DssError.UpstreamFailure("could not list the shop's webhook subscriptions")

/**
 * A grant that does not reach the subscriptions is the merchant's to fix, and its `403` says so; a token Shopify
 * refuses is a reinstall, and its `401` says that. Any other failure is the scan's `502`, named apart from the token
 * lookup's own.
 */
private fun ShopifyError.toScanDssError(): DssError = when (this) {
  is ShopifyError.AccessDenied, is ShopifyError.TokenRejected -> toDssError()
  else -> SUBSCRIPTIONS_UNLISTABLE
}
