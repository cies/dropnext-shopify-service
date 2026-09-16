package dropnext.dss.handler

import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.workflow.WebhookMirrorOutcome
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull


/**
 * Runs one delivery's [mirror] under the time Shopify leaves for it, and answers what it produced or
 * [WebhookMirrorOutcome.TimedOut].
 *
 * The rule the two durations encode: past [budget] a delivery that has changed nothing is cancelled at once, because
 * nothing is lost and the redelivery asks again; a delivery whose monolith write is already on the wire is given
 * [writeGrace] more, because cancelling it would throw away what the monolith may just have committed. The token
 * lookup behind a delivery goes through the store's own service and never counts as a write.
 *
 * Its own function rather than a private method of [ShopifyWebhookHandlers] so that the policy can be driven directly
 * under virtual time: through the handler it can only be tested by waiting the durations out in real seconds, which
 * makes the assertion a race on a loaded machine and the suite slower for no gain.
 */
internal suspend fun mirrorWithinBudget(
  monolithService: MonolithService,
  budget: Duration,
  writeGrace: Duration,
  mirror: suspend (MonolithService) -> WebhookMirrorOutcome,
): WebhookMirrorOutcome =
  coroutineScope {
    val monolith = WriteTrackingMonolithService(monolithService)
    val work = async { mirror(monolith) }
    // Timing out an await leaves the awaited work running: it is a child of this scope, not of the timeout.
    val outcome = withTimeoutOrNull(budget) { work.await() }
      ?: if (monolith.writeStarted) withTimeoutOrNull(writeGrace) { work.await() } else null
    outcome ?: WebhookMirrorOutcome.TimedOut.also { work.cancel() }
  }

/**
 * One delivery's view of the monolith, noting when the delivery starts changing it: from then on the budget waits out
 * the write grace before cancelling. The token lookup goes through the token store's own service and never counts.
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
