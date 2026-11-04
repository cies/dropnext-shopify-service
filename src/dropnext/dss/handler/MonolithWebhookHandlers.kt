package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.FetchProductsRequest
import dropnext.dss.contract.FetchProductsResponse
import dropnext.dss.contract.ShopCatalogProduct
import dropnext.dss.contract.ShopCatalogResponse
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.TrackingUpdateResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.inWholeSecondsRoundedUp
import dropnext.dss.lib.ktor.respondContract
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.mapper.toSyncShipmentsResponse
import dropnext.dss.workflow.ACCESS_PROBLEM_REPORT_BUDGET
import dropnext.dss.workflow.CATALOG_BUDGET_FLOOR
import dropnext.dss.workflow.CATALOG_READ_BUDGET
import dropnext.dss.workflow.FETCH_PRODUCTS_BUDGET
import dropnext.dss.workflow.SHIPMENT_SYNC_BUDGET
import dropnext.dss.workflow.ShipmentSyncBudget
import dropnext.dss.workflow.UnknownProductStatusWarning
import dropnext.dss.workflow.fetchShopifyProducts
import dropnext.dss.workflow.persistTokenToMonolith
import dropnext.dss.workflow.readShopifyCatalog
import dropnext.dss.workflow.reportShopifyAccessProblem
import dropnext.dss.workflow.syncShopifyShipmentsToFulfillments
import dropnext.dss.workflow.syncShopifyTrackingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.util.getOrFail
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource


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
  private val shopifyReadPause: suspend (Duration) -> Unit,
  private val unknownProductStatusWarning: UnknownProductStatusWarning,
  /** When an access problem was observed, the time the monolith's race rule compares. */
  private val clock: Clock,
  private val shipmentSyncTimeSource: TimeSource = TimeSource.Monotonic,
  private val shipmentSyncBudget: Duration = SHIPMENT_SYNC_BUDGET,
) {

  suspend fun handleSyncShipments(call: ApplicationCall) {
    // From the arrival on: resolving the shop's token may already ask the monolith, and the monolith's wait runs meanwhile.
    val budget = ShipmentSyncBudget.startedAt(shipmentSyncTimeSource.markNow(), shipmentSyncBudget)
    val request = call.receive<SyncShipmentsWithFulfillmentsRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      when (val synced = syncShopifyShipmentsToFulfillments(shopify, request, budget)) {
        is Success -> call.respondContract(
          SyncShipmentsWithFulfillmentsResponse.serializer(),
          synced.value.toSyncShipmentsResponse(),
        )

        is Failure -> {
          val stopped = synced.reason
          // The log gets Shopify's whole message; the caller may be told less (see `toDssError`).
          log.warn { "sync-shipments failed error=${stopped.cause.message}" }
          // Inside what is left of the sync's budget, so the report cannot keep the monolith waiting past its timeout.
          val reportBudget = minOf(budget.remaining(), ACCESS_PROBLEM_REPORT_BUDGET)
          val observedDuring = "sync-shipments-with-fulfillments"
          val observedAt = clock.instant()
          reportShopifyAccessProblem(monolithService, shop, stopped.cause, observedDuring, observedAt, reportBudget)
          val report = stopped.report.toSyncShipmentsResponse()
          call.respondError(DssError.ShipmentSyncStopped(stopped.cause.toDssError(), report))
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
          call.respondContract(
            TrackingUpdateResponse.serializer(),
            TrackingUpdateResponse(fulfillmentEventId = synced.value.value),
          )

        is Failure -> {
          log.warn { "tracking-update failed error=${synced.reason.message}" }
          reportShopifyAccessProblem(monolithService, shop, synced.reason, "tracking-update", clock.instant())
          call.respondError(synced.reason.toDssError())
        }
      }
    }
  }

  /**
   * `GET /products/catalog?shop=` — every variant the shop has, grouped under its product.
   *
   * The shop travels in the query string rather than a body because this is a read; the walk behind it can take
   * minutes on a large catalog, which is why the monolith calls it from a job.
   */
  suspend fun handleProductsCatalog(call: ApplicationCall) {
    val arrived = TimeSource.Monotonic.markNow()
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespond(rawShop, "shop") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries
      when (val catalog = readShopifyCatalog(shopify, pause = shopifyReadPause)) {
        is Success -> call.respondContract(
          ShopCatalogResponse.serializer(),
          ShopCatalogResponse(
            products = catalog.value.products.map {
              ShopCatalogProduct(
                productId = it.productId.value,
                productVariantIds = it.productVariantIds.map { variantId -> variantId.value },
              )
            },
            productCount = catalog.value.products.size,
            productVariantCount = catalog.value.productVariantCount,
            nextRequestAfterSeconds = catalog.value.rateBudget.nextRequestAfterSeconds(),
          )
        )
        // The walk has logged its failure, with how far it got.
        is Failure -> {
          val reportBudget = arrived.reportBudgetWithin(CATALOG_READ_BUDGET)
          val observedAt = clock.instant()
          reportShopifyAccessProblem(monolithService, shop, catalog.reason, "products/catalog", observedAt, reportBudget)
          call.respondError(catalog.reason.toDssError())
        }
      }
    }
  }

  /** `POST /products/fetch` — the named products as the variant items the monolith's upsert already takes. */
  suspend fun handleFetchProducts(call: ApplicationCall) {
    val arrived = TimeSource.Monotonic.markNow()
    val request = call.receive<FetchProductsRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries
      val productIds = request.shopifyProductIds.map(::ShopifyProductId)
      val reportProblem: suspend (ShopifyError) -> Unit = { error ->
        val reportBudget = arrived.reportBudgetWithin(FETCH_PRODUCTS_BUDGET)
        reportShopifyAccessProblem(monolithService, shop, error, "products/fetch", clock.instant(), reportBudget)
      }
      val fetched = fetchShopifyProducts(shopify, productIds, unknownProductStatusWarning, pause = shopifyReadPause)
      when (fetched) {
        is Success -> {
          // The products it loaded are the answer, but what stopped it before the rest is news all the same.
          fetched.value.stoppedBy?.let { reportProblem(it) }
          call.respondContract(
            FetchProductsResponse.serializer(),
            FetchProductsResponse(
              productVariants = fetched.value.productVariants,
              missingProductIds = fetched.value.missingProductIds.map { it.value },
              nextRequestAfterSeconds = fetched.value.nextRequestAfter.inWholeSecondsRoundedUp(),
              unfetchedProductIds = fetched.value.unfetchedProductIds.map { it.value },
            )
          )
        }
        // The fetch has logged its failure, naming the product it failed on, which is more than this could say.
        is Failure -> {
          reportProblem(fetched.reason)
          call.respondError(fetched.reason.toDssError())
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

      // Forwarded as they came: the monolith sends none, and a caller that knows the grant is the one to say it.
      val persisted = persistTokenToMonolith(
        monolithService,
        shop,
        shopId,
        token,
        grantedAccessScopes = request.grantedAccessScopes,
        missingAccessScopes = request.missingAccessScopes,
      )
      when (persisted) {
        is MonolithPersistOutcome.Persisted -> call.respondContract(
          UpdateStoreApiKeyResponse.serializer(),
          UpdateStoreApiKeyResponse(storeId = persisted.storeId.value),
        )
        is MonolithPersistOutcome.Failed -> call.respondError(persisted.toDssError())
      }
    }
  }
}

/**
 * What a report may take, counted from the request's arrival: what is left of [budget], the time the monolith is
 * known to wait for, and never more than one attempt of the monolith client.
 */
private fun TimeMark.reportBudgetWithin(budget: Duration): Duration =
  minOf((budget - elapsedNow()).coerceAtLeast(Duration.ZERO), ACCESS_PROBLEM_REPORT_BUDGET)

/**
 * How long the monolith should wait before its next call for this shop: the refill a bucket under
 * [CATALOG_BUDGET_FLOOR] needs to get back to it, or nothing. Shopify's bucket model stays on this side of the contract;
 * the monolith only honors the number. A shop that reported no budget is not asked to wait.
 */
private fun ShopifyRateBudget?.nextRequestAfterSeconds(): Int =
  this?.takeIf { it.isBelow(CATALOG_BUDGET_FLOOR) }?.refillTo(CATALOG_BUDGET_FLOOR)?.inWholeSecondsRoundedUp() ?: 0
