package dropnext.dss.boot.warmup

import dropnext.dss.boot.warmup.WarmUpStepOutcome.Failed
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Ok
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Skipped
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeWarmUpLoopbackService
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The warm-up pays the JVM's one-time costs before the task takes traffic. What matters is not that the fakes were
 * called but what it does with what it finds: which shop it names, what it makes of a failure, and what it skips when
 * the budget or the server is not there, because every outcome ends in the two lines an operator reads after a deploy.
 */
class WarmUpBeforeTakingTrafficTest {

  private val serverBound = CompletableDeferred(Unit)

  /** The monolith cannot know the placeholder shop; its `404` has paid for the connection all the same. */
  @Test
  fun `the placeholder shop is asked of the monolith by subdomain, and a 404 is as good as a hit`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    val report = warmUp(monolith = monolith)
    assert(monolith.getStoreCalls == listOf(WARM_UP_PLACEHOLDER_SHOP.subdomainOnly))
    assert(report.outbound.monolith == Ok())
  }

  @Test
  fun `a monolith that does not answer fails its step and the rest still runs`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
    val loopback = FakeWarmUpLoopbackService()
    val report = warmUp(monolith = monolith, loopback = loopback)
    assert(report.outbound.monolith == Failed("monolith_transport"))
    assert(loopback.apiCheckCalls.size == 1)
    assert(loopback.webhookDeliveryCalls.size == 1)
  }

  @Test
  fun `both requests to ourselves name the placeholder shop and carry the same trace id`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService()
    warmUp(loopback = loopback)
    val check = loopback.apiCheckCalls.single()
    val delivery = loopback.webhookDeliveryCalls.single()
    assert(check.shop == WARM_UP_PLACEHOLDER_SHOP)
    assert(delivery.shop == WARM_UP_PLACEHOLDER_SHOP)
    assert(check.traceId == delivery.traceId)
    assert(check.traceId.isNotBlank())
  }

  /** A `401` is what the check answers for a shop without a token, the production case; the pipeline ran all the same. */
  @Test
  fun `an answer from ourselves is reported by its status, and only no answer or our own 500 is a failure`() = runBlocking {
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { apiCheckStatus = 401 }).inbound.apiCheck == Ok("401"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { apiCheckStatus = 502 }).inbound.apiCheck == Ok("502"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { apiCheckStatus = 500 }).inbound.apiCheck == Failed("http_500"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { webhookDeliveryStatus = null }).inbound.webhook == Failed("no_answer"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { webhookDeliveryStatus = 200 }).inbound.webhook == Ok("200"))
  }

  @Test
  fun `a server that never binds its socket leaves the inbound part skipped and the outbound part done`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService()
    val report = warmUp(loopback = loopback, serverBound = CompletableDeferred(), budget = 200.milliseconds)
    assert(report.outbound.monolith == Ok())
    assert(report.inbound.apiCheck == Skipped("server_not_bound"))
    assert(report.inbound.webhook == Skipped("server_not_bound"))
    assert(loopback.apiCheckCalls.isEmpty())
    assert(loopback.webhookDeliveryCalls.isEmpty())
  }

  @Test
  fun `a step the budget never reaches is skipped for that reason`() = runBlocking {
    val monolith = FakeMonolithService()
    val report = warmUp(monolith = monolith, budget = Duration.ZERO)
    assert(monolith.getStoreCalls.isEmpty())
    assert(report.outbound.monolith == Skipped("budget"))
    assert(report.inbound.apiCheck == Skipped("budget"))
    assert(report.inbound.webhook == Skipped("budget"))
  }

  @Test
  fun `the two log lines name every step and, for a skip or a failure, why`() = runBlocking {
    val report = warmUp(
      monolith = FakeMonolithService().apply { getStoreTransportFailure = true },
      loopback = FakeWarmUpLoopbackService().apply { apiCheckStatus = 401 },
    )
    assert(report.outbound.logLine().startsWith("Warm-up outbound done took_ms="))
    assert(report.outbound.logLine().endsWith(" monolith=failed error=monolith_transport"))
    assert(report.inbound.logLine().startsWith("Warm-up inbound done took_ms="))
    assert(report.inbound.logLine().endsWith(" api_check=401 webhook=200"))
    assert(report.anyFailed)
  }

  /**
   * The id the requests to ourselves carry is the id on every line the warm-up logs, including a line logged after a
   * suspension and a line logged by the monolith client's failure path: what ties a start's lines together in Logflare.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every line of the warm-up carries its trace id, also after a suspension`() {
    val loopback = FakeWarmUpLoopbackService().apply { answerDelay = 20.milliseconds }
    val lines = capturingLogs {
      runBlocking { warmUp(monolith = FakeMonolithService().apply { getStoreTransportFailure = true }, loopback = loopback) }
    }
    val traceId = loopback.apiCheckCalls.single().traceId
    assert(lines.single { "Warm-up outbound done" in it }.endsWith("{trace_id=$traceId}"))
    assert(lines.single { "Warm-up inbound done" in it }.endsWith("{trace_id=$traceId}"))
    assert(lines.single { "Monolith getStore failed" in it }.endsWith("{trace_id=$traceId}"))
  }

  // ---------- helpers ----------

  private suspend fun warmUp(
    monolith: FakeMonolithService = FakeMonolithService(),
    loopback: FakeWarmUpLoopbackService = FakeWarmUpLoopbackService(),
    serverBound: CompletableDeferred<Unit> = this.serverBound,
    budget: Duration = WARM_UP_BUDGET,
  ) = warmUpBeforeTakingTraffic(monolith = monolith, loopback = loopback, serverBound = serverBound, budget = budget)
}
