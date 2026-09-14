package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.TrackingUpdateResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.workflow.persistTokenToMonolith
import dropnext.dss.workflow.syncShopifyShipmentsToFulfillments
import dropnext.dss.workflow.syncShopifyTrackingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/**
 * Handlers for the monolith's webhook REST endpoints: resolve the shop, hand the request to a workflow
 * and map its answer onto the response. Everything before that is the framework's: the bearer token is
 * checked by the `authenticate` block in [dropnext.dss.routing.monolithWebhookRoutes], and a body that
 * does not decode or does not validate is answered by `StatusPages` before `receive` returns. Once the shop parses,
 * it is in the MDC, so every line the request causes names it as a field.
 */
class MonolithWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopTokens: ShopTokenStore,
) {

  suspend fun handleSyncShipments(call: ApplicationCall) {
    val request = call.receive<SyncShipmentsWithFulfillmentsRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      when (val synced = syncShopifyShipmentsToFulfillments(shopify, request)) {
        is Success ->
          call.respond(SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = synced.value.map { it.value }))

        is Failure -> {
          // The log gets Shopify's whole message; the caller may be told less (see `toDssError`).
          log.warn { "sync-shipments failed error=${synced.reason.message}" }
          call.respondError(synced.reason.toDssError())
        }
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val request = call.receive<TrackingUpdateRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      when (val synced = syncShopifyTrackingEvent(shopify, request)) {
        is Success ->
          call.respond(TrackingUpdateResponse(fulfillmentEventId = synced.value.value))

        is Failure -> {
          log.warn { "tracking-update failed error=${synced.reason.message}" }
          call.respondError(synced.reason.toDssError())
        }
      }
    }
  }

  /**
   * `PUT /stores/api-key` — remembers a Shopify Admin token and forwards it to the monolith. The token
   * is cached before the monolith is asked and stays cached when that fails; the answer is then an
   * error, so the caller knows the monolith does not have it, rather than a `200` with a made-up id.
   */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    val request = call.receive<UpdateStoreApiKeyRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val token = ShopifyAdminToken(request.apiKey)
      val shopId = request.shopifyShopId?.let(::ShopifyShopId)

      shopTokens.remember(shop, token)
      log.info { "PUT stores/api-key: token cached in memory" }

      when (val persisted = persistTokenToMonolith(monolithService, shop, shopId, token)) {
        is MonolithPersistOutcome.Persisted -> call.respond(UpdateStoreApiKeyResponse(storeId = persisted.storeId.value))
        is MonolithPersistOutcome.Failed -> call.respondError(persisted.toDssError())
      }
    }
  }
}
