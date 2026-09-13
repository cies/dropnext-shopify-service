package dropnext.dss.testutil.fake

import dropnext.dss.boot.warmup.WarmUpLoopbackService
import dropnext.dss.domain.ShopDomain
import kotlin.time.Duration
import kotlinx.coroutines.delay


/** In-memory [WarmUpLoopbackService]: records which shop and trace id each request named and answers the configured status. */
class FakeWarmUpLoopbackService : WarmUpLoopbackService, RecordingFake {
  data class RecordedLoopbackRequest(val shop: ShopDomain, val traceId: String)

  val apiCheckCalls: MutableList<RecordedLoopbackRequest> = mutableListOf()
  val webhookDeliveryCalls: MutableList<RecordedLoopbackRequest> = mutableListOf()

  /** The status each request is answered with; `null` is no answer at all. */
  var apiCheckStatus: Int? = 200
  var webhookDeliveryStatus: Int? = 200

  /** How long each answer takes once recorded: a real suspension, for what the workflow logs after one. */
  var answerDelay: Duration = Duration.ZERO

  override suspend fun apiCheck(shop: ShopDomain, traceId: String): Int? {
    apiCheckCalls.add(RecordedLoopbackRequest(shop, traceId))
    delay(answerDelay)
    return apiCheckStatus
  }

  override suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int? {
    webhookDeliveryCalls.add(RecordedLoopbackRequest(shop, traceId))
    delay(answerDelay)
    return webhookDeliveryStatus
  }

  override fun clear() {
    apiCheckCalls.clear()
    webhookDeliveryCalls.clear()
    apiCheckStatus = 200
    webhookDeliveryStatus = 200
    answerDelay = Duration.ZERO
  }
}
