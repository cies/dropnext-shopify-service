package dropnext.dss.handler

import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.MAX_CONCURRENT_REQUESTS_PER_HOST
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.ktor.toHttpStatus
import dropnext.dss.lib.logging.currentTraceId
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.lib.shopify.webhook.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.lib.shopify.webhook.productIdFromProductWebhook
import dropnext.dss.lib.shopify.webhook.shopDomainFromWebhook
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.WebhookSkipReason
import dropnext.dss.workflow.deleteShopifyProductFromMonolith
import dropnext.dss.workflow.isTransient
import dropnext.dss.workflow.syncShopifyOrderToMonolith
import dropnext.dss.workflow.syncShopifyProductToMonolith
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How long the work behind one delivery may take. Shopify waits five seconds for an answer and counts one that does not
 * come as a failed delivery, the same as a `5xx`. Four seconds leaves the fifth for reading and verifying the body, for a
 * monolith write's [WEBHOOK_WRITE_GRACE] and for the answer's way back. Past it the work is cancelled and the answer is a
 * `502`: what Shopify would have recorded anyway, and what gets the delivery sent again. Whatever the cancelled work
 * already did in the monolith is safe to repeat.
 */
val WEBHOOK_MIRROR_BUDGET: Duration = 4.seconds

/**
 * How long a monolith write already sent may run past [WEBHOOK_MIRROR_BUDGET]. Cancelling it would throw away what the
 * monolith may just have committed and ask for a redelivery; one that lands in the grace is answered `200`, most likely
 * still inside Shopify's five seconds (and if not, the redelivery finds the work done). Reads get no grace: nothing has
 * changed yet, and past the budget there is no time left for the write that would follow them.
 */
val WEBHOOK_WRITE_GRACE: Duration = 700.milliseconds

/**
 * How many deliveries may be mirrored at once. A mirror holds at most one call to Shopify and one to the monolith at a
 * time, so admitting more of them than the client lets through to one host only queues the rest on the dispatcher,
 * where each holds its delivery open until [WEBHOOK_MIRROR_BUDGET] cancels it: a bulk product edit, thousands of
 * deliveries in a minute, would fail them all and have them all redelivered into the same wall. A delivery that finds
 * no slot is answered `502` before any work, and Shopify's growing redelivery interval spreads the burst out.
 */
const val MAX_CONCURRENT_MIRRORS: Int = MAX_CONCURRENT_REQUESTS_PER_HOST

/**
 * How often a monolith call made for a webhook is repeated. The monolith client's three retries wait half a second,
 * one and two before each attempt: most of [WEBHOOK_MIRROR_BUDGET] spent on a monolith that answered a `5xx` three
 * times, for a `502` either way, while Shopify's redelivery is the retry that fits. One repeat stays, since it absorbs
 * a dropped connection at once, which a redelivery minutes later need not.
 */
const val WEBHOOK_MONOLITH_MAX_RETRIES: Int = 1

/**
 * The one inbound Shopify webhook endpoint: verifies the body signature, resolves the shop and its
 * service, and dispatches on the topic to the workflow that mirrors the change into the monolith.
 * The answer is chosen from the outcome: `200` for whatever a redelivery could not improve (done,
 * nothing to do, a token or a request that is refused), a `502` when Shopify or the monolith did not
 * answer or answered a `5xx`, so Shopify's own redelivery, up to eight times in four hours, is the retry.
 * Every verified delivery ends in one [WebhookDeliveryReport]: the one summary line it leaves in the log, at the
 * level the outcome asks for, and the response body. Nothing here logs an outcome a second time.
 *
 * [mirrorSlots] bounds how many deliveries are being mirrored at once ([MAX_CONCURRENT_MIRRORS]); a delivery that finds
 * none free is answered `502` without work.
 */
class ShopifyWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopifyHmacVerifierService: ShopifyHmacVerifierService,
  private val mirrorBudget: Duration = WEBHOOK_MIRROR_BUDGET,
  private val writeGrace: Duration = WEBHOOK_WRITE_GRACE,
  private val mirrorSlots: Semaphore = Semaphore(MAX_CONCURRENT_MIRRORS),
) {

  suspend fun handleShopifyWebhook(call: ApplicationCall) {
    val receivedAt = Instant.now()
    val startedNanos = System.nanoTime()
    val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
    val topic = ShopifyWebhookTopic.parse(call.request.headers["X-Shopify-Topic"])
    val shopDomainHeader = call.request.headers["X-Shopify-Shop-Domain"]
    val body = call.receive<ByteArray>()
    if (!shopifyHmacVerifierService.verifyWebhook(hmacHeader, body)) {
      // The one line an operator gets for a delivery that was never acted on: a rotated app secret,
      // a subscription left behind by another app, or a forged call all look the same from here.
      log.warn {
        "Webhook rejected: HMAC mismatch topic=${topic.raw} shopDomainHeader=$shopDomainHeader " +
          "hmacHeaderPresent=${hmacHeader != null} bodyBytes=${body.size}"
      }
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    val bodyString = body.decodeToString()
    log.debug { "Webhook verified topic=${topic.raw} shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}" }

    val shop = shopDomainFromWebhook(shopDomainHeader, bodyString)
    val outcome = when {
      shop == null -> WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_SHOP_DOMAIN)
      !mirrorSlots.tryAcquire() -> WebhookMirrorOutcome.Overloaded
      else -> try {
        mirrorWithinBudget(topic, shop, bodyString)
      } finally {
        mirrorSlots.release()
      }
    }

    val report = WebhookDeliveryReport(
      topic = topic.raw,
      shop = shop,
      webhookId = call.request.headers["X-Shopify-Webhook-Id"],
      lagMillis = call.request.headers["X-Shopify-Triggered-At"]?.let { lagMillis(it, receivedAt) },
      tookMillis = (System.nanoTime() - startedNanos) / 1_000_000,
      outcome = outcome,
    )
    val answer = if (outcome.isTransient) DssError.UpstreamFailure("not mirrored, please redeliver") else null
    val line = report.logLine(answeredStatus = answer?.toHttpStatus()?.value ?: HttpStatusCode.OK.value)
    when (report.logLevel) {
      WebhookDeliveryReport.LogLevel.INFO -> log.info { line }
      WebhookDeliveryReport.LogLevel.WARN -> log.warn { line }
      WebhookDeliveryReport.LogLevel.ERROR -> log.error { line }
    }
    if (answer != null) call.respondError(answer) else call.respond(HttpStatusCode.OK, report.toResponse(currentTraceId()))
  }

  private suspend fun mirrorWithinBudget(topic: ShopifyWebhookTopic, shop: ShopDomain, bodyString: String): WebhookMirrorOutcome =
    coroutineScope {
      val monolith = WriteTrackingMonolithService(monolithService)
      val work = async { mirror(topic, shop, bodyString, monolith) }
      // Timing out an await leaves the awaited work running: it is a child of this scope, not of the timeout.
      val outcome = withTimeoutOrNull(mirrorBudget) { work.await() }
        ?: if (monolith.writeStarted) withTimeoutOrNull(writeGrace) { work.await() } else null
      outcome ?: WebhookMirrorOutcome.TimedOut.also { work.cancel() }
    }

  // The Shopify service is resolved per branch: a delete needs none (the product is gone and the
  // body carries its id), and a shop without a token must not lose its deletes.
  private suspend fun mirror(
    topic: ShopifyWebhookTopic,
    shop: ShopDomain,
    bodyString: String,
    monolith: MonolithService,
  ): WebhookMirrorOutcome =
    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate -> withShopifyService(shop) { shopify ->
        val gid = graphqlResourceIdFromShopifyWebhook(topic.raw, bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        syncShopifyProductToMonolith(shopify, monolith, gid)
      }

      ShopifyWebhookTopic.ProductsDelete -> {
        val productId = productIdFromProductWebhook(bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        deleteShopifyProductFromMonolith(monolith, shop, productId)
      }

      ShopifyWebhookTopic.OrdersCreate -> withShopifyService(shop) { shopify ->
        val gid = graphqlResourceIdFromShopifyWebhook(topic.raw, bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        syncShopifyOrderToMonolith(shopify, monolith, gid, topic.raw)
      }

      // The `create` already carried the order; an update is acknowledged, not mirrored.
      ShopifyWebhookTopic.OrdersUpdated -> skipped(WebhookSkipReason.TOPIC_NOT_MIRRORED)

      is ShopifyWebhookTopic.Other -> skipped(WebhookSkipReason.TOPIC_NOT_MIRRORED)
    }

  private fun skipped(reason: WebhookSkipReason) = WebhookMirrorOutcome.Skipped(reason)

  /**
   * Runs [block] with the shop's Graphql service. A shop without a token is skipped; the delivery's summary line, at
   * error level, is what the operator gets. A token the monolith could not be asked for is a failure Shopify is asked
   * to redeliver instead: the shop may well have one, and acknowledging the delivery would lose it.
   */
  private suspend inline fun withShopifyService(
    shop: ShopDomain,
    block: (ShopifyGraphqlService) -> WebhookMirrorOutcome,
  ): WebhookMirrorOutcome =
    when (val lookup = shopifyGraphqlServiceFactory.forShop(shop)) {
      is ShopLookup.Found -> block(lookup.value)
      ShopLookup.Missing -> skipped(WebhookSkipReason.NO_ADMIN_TOKEN)
      // The lookup has logged the monolith's failure.
      ShopLookup.Unavailable -> WebhookMirrorOutcome.TokenUnavailable
    }
}

/**
 * One delivery's view of the monolith, noting when the delivery starts changing it: from then on the budget waits out
 * [WEBHOOK_WRITE_GRACE] before cancelling. The token lookup goes through the token store's own service and never counts.
 */
private class WriteTrackingMonolithService(private val delegate: MonolithService) : MonolithService by delegate {
  @Volatile
  var writeStarted: Boolean = false
    private set

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome> =
    write { delegate.postCreateOrder(request) }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<Int> =
    write { delegate.upsertProductVariants(request) }

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): MonolithResult<Int> =
    write { delegate.deleteProductVariants(request) }

  private inline fun <T> write(block: () -> T): T {
    writeStarted = true
    return block()
  }
}

/** Shopify sends `X-Shopify-Triggered-At` as ISO-8601; an unparseable value costs the field, not the delivery. */
private fun lagMillis(triggeredAt: String, receivedAt: Instant): Long? =
  runCatching { receivedAt.toEpochMilli() - Instant.parse(triggeredAt.trim()).toEpochMilli() }.getOrNull()
