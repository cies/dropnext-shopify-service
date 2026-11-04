package dropnext.dss.testutil.fake

import dropnext.dss.boot.warmup.LoopbackAnswer
import dropnext.dss.boot.warmup.WarmUpLoopbackService
import dropnext.dss.domain.ShopDomain
import kotlin.time.Duration
import kotlinx.coroutines.delay


/** In-memory [WarmUpLoopbackService]: records which shop and trace id each request named and answers the configured status. */
class FakeWarmUpLoopbackService : WarmUpLoopbackService, RecordingFake {
  data class RecordedLoopbackRequest(val shop: ShopDomain, val traceId: String)

  val shopifyStoreStatusCalls: MutableList<RecordedLoopbackRequest> = mutableListOf()
  val webhookDeliveryCalls: MutableList<RecordedLoopbackRequest> = mutableListOf()

  /** The status each request is answered with; `null` is no answer at all. */
  var shopifyStoreStatusCode: Int? = 200
  var webhookDeliveryStatus: Int? = 200

  /** Whether the `shopifyStoreStatus` answer is our bearer provider's challenge rather than a handler's answer. */
  var shopifyStoreStatusBearerRejected: Boolean = false

  /** How long each answer takes once recorded: a real suspension, for what the workflow logs after one. */
  var answerDelay: Duration = Duration.ZERO

  override suspend fun shopifyStoreStatus(shop: ShopDomain, traceId: String): LoopbackAnswer? {
    shopifyStoreStatusCalls.add(RecordedLoopbackRequest(shop, traceId))
    delay(answerDelay)
    return shopifyStoreStatusCode?.let { LoopbackAnswer(it, bearerRejected = shopifyStoreStatusBearerRejected) }
  }

  override suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int? {
    webhookDeliveryCalls.add(RecordedLoopbackRequest(shop, traceId))
    delay(answerDelay)
    return webhookDeliveryStatus
  }

  override fun clear() {
    shopifyStoreStatusCalls.clear()
    webhookDeliveryCalls.clear()
    shopifyStoreStatusCode = 200
    webhookDeliveryStatus = 200
    shopifyStoreStatusBearerRejected = false
    answerDelay = Duration.ZERO
  }
}
