package dropnext.dss.boot.warmup

import dropnext.dss.boot.warmup.WarmUpStepOutcome.Failed
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Ok
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Rejected
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
 * The warm-up pays the JVM's one-time costs before the task takes traffic, and two of its steps check on the way that
 * the monolith accepts our key and that our bearer provider accepts the key it was configured with. What matters is not that the fakes were called but what it does with what it finds: which shop
 * it names, which answer refuses traffic and which only fails a step, and what it skips when the budget or the server
 * is not there, because every outcome ends in the lines an operator reads after a deploy.
 */
class WarmUpBeforeTakingTrafficTest {

  private val serverBound = CompletableDeferred(Unit)

  // ---------- the monolith accepts our key ----------

  /** The monolith cannot know the placeholder shop; its `404` proves the key all the same. */
  @Test
  fun `the placeholder shop is asked of the monolith by subdomain, and a 404 proves the key`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    val report = warmUp(monolith = monolith)
    assert(monolith.getStoreCalls == listOf(WARM_UP_PLACEHOLDER_SHOP.subdomainOnly))
    assert(report.outbound.monolithAcceptsOurKey == Ok())
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  @Test
  fun `a 401 from the monolith rejects our key and refuses traffic`() = runBlocking {
    val report = warmUp(monolith = FakeMonolithService().apply { getStoreRejectionStatus = 401 })
    assert(report.outbound.monolithAcceptsOurKey == Rejected("monolith_401"))
    val verdict = report.verdict
    assert(verdict is WarmUpVerdict.RefuseTraffic)
    assert("DSS_TO_MONOLITH_API_KEY" in (verdict as WarmUpVerdict.RefuseTraffic).reason)
  }

  /** Something in front of the monolith may answer `403` for a reason of its own; that must not stop every task. */
  @Test
  fun `a 403 from the monolith fails the check and the task still takes traffic`() = runBlocking {
    val report = warmUp(monolith = FakeMonolithService().apply { getStoreRejectionStatus = 403 })
    assert(report.outbound.monolithAcceptsOurKey == Failed("monolith_403"))
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  @Test
  fun `a 500 from the monolith fails the check and the task still takes traffic`() = runBlocking {
    val report = warmUp(monolith = FakeMonolithService().apply { getStoreRejectionStatus = 500 })
    assert(report.outbound.monolithAcceptsOurKey == Failed("monolith_500"))
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  /** A monolith mid-deploy proves nothing about the key; the task serves the shops whose tokens it holds. */
  @Test
  fun `a monolith that does not answer fails its check, the rest still runs, and the task takes traffic`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
    val loopback = FakeWarmUpLoopbackService()
    val report = warmUp(monolith = monolith, loopback = loopback)
    assert(report.outbound.monolithAcceptsOurKey == Failed("monolith_transport"))
    assert(loopback.shopifyStoreStatusCalls.size == 1)
    assert(loopback.webhookDeliveryCalls.size == 1)
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  // ---------- our bearer provider's wiring ----------

  @Test
  fun `both requests to ourselves name the placeholder shop and carry the same trace id`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService()
    warmUp(loopback = loopback)
    val check = loopback.shopifyStoreStatusCalls.single()
    val delivery = loopback.webhookDeliveryCalls.single()
    assert(check.shop == WARM_UP_PLACEHOLDER_SHOP)
    assert(delivery.shop == WARM_UP_PLACEHOLDER_SHOP)
    assert(check.traceId == delivery.traceId)
    assert(check.traceId.isNotBlank())
  }

  @Test
  fun `our own bearer challenge to the configured key refuses traffic`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService().apply {
      shopifyStoreStatusCode = 401
      shopifyStoreStatusBearerRejected = true
    }
    val report = warmUp(loopback = loopback)
    assert(report.inbound.bearerProviderWiring == Rejected("bearer_rejected"))
    val verdict = report.verdict
    assert(verdict is WarmUpVerdict.RefuseTraffic)
    assert("MONOLITH_TO_DSS_API_KEY" in (verdict as WarmUpVerdict.RefuseTraffic).reason)
  }

  /** A `401` is what the check answers for a shop without a token, the production case: the bearer passed. */
  @Test
  fun `an answer from ourselves is reported by its status, and only no answer or our own 500 is a failure`() = runBlocking {
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = 401 }).inbound.bearerProviderWiring == Ok("401"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = 502 }).inbound.bearerProviderWiring == Ok("502"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = 500 }).inbound.bearerProviderWiring == Failed("http_500"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = null }).inbound.bearerProviderWiring == Failed("no_answer"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { webhookDeliveryStatus = null }).inbound.webhook == Failed("no_answer"))
    assert(warmUp(loopback = FakeWarmUpLoopbackService().apply { webhookDeliveryStatus = 200 }).inbound.webhook == Ok("200"))
  }

  @Test
  fun `a 401 for a shop without a token takes traffic`() = runBlocking {
    val report = warmUp(loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = 401 })
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  // ---------- the contract copy ----------

  /** The document is fetched after the gate opens, so a slow fetch can never use up the checks' budget. */
  @Test
  fun `the warm-up does not fetch the monolith's document`() = runBlocking {
    val monolith = FakeMonolithService()
    warmUp(monolith = monolith)
    assert(monolith.getOpenApiDocumentCalls.isEmpty())
  }

  // ---------- the budget and the server ----------

  @Test
  fun `a server that never binds its socket leaves the inbound part skipped and the outbound part done`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService()
    val report = warmUp(loopback = loopback, serverBound = CompletableDeferred(), budget = 200.milliseconds)
    assert(report.outbound.monolithAcceptsOurKey == Ok())
    assert(report.inbound.bearerProviderWiring == Skipped("server_not_bound"))
    assert(report.inbound.webhook == Skipped("server_not_bound"))
    assert(loopback.shopifyStoreStatusCalls.isEmpty())
    assert(loopback.webhookDeliveryCalls.isEmpty())
  }

  @Test
  fun `a step the budget never reaches is skipped for that reason`() = runBlocking {
    val monolith = FakeMonolithService()
    val report = warmUp(monolith = monolith, budget = Duration.ZERO)
    assert(monolith.getStoreCalls.isEmpty())
    assert(report.outbound.monolithAcceptsOurKey == Skipped("budget"))
    assert(report.inbound.bearerProviderWiring == Skipped("budget"))
    assert(report.inbound.webhook == Skipped("budget"))
    assert(report.verdict == WarmUpVerdict.TakeTraffic)
  }

  /**
   * What the budget exists for: a request to ourselves that hangs. It is cut off when the budget ends and says so,
   * and the step after it is never started. The timeout rounds up to the millisecond, so the deadline has passed by
   * the time the next step looks at it.
   */
  @Test
  fun `a step that outlives the budget is cut short and the steps after it are skipped`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService().apply { answerDelay = 500.milliseconds }
    val report = warmUp(loopback = loopback, budget = 100.milliseconds)
    assert(report.outbound.monolithAcceptsOurKey == Ok())
    assert(report.inbound.bearerProviderWiring == Failed("budget"))
    assert(report.inbound.webhook == Skipped("budget"))
    assert(loopback.shopifyStoreStatusCalls.size == 1)
    assert(loopback.webhookDeliveryCalls.isEmpty())
  }

  // ---------- the log lines ----------

  @Test
  fun `the two log lines name every step and, for a skip or a failure, why`() = runBlocking {
    val report = warmUp(
      monolith = FakeMonolithService().apply { getStoreTransportFailure = true },
      loopback = FakeWarmUpLoopbackService().apply { shopifyStoreStatusCode = 401 },
    )
    assert(report.outbound.logLine().startsWith("Warm-up outbound done took_ms="))
    assert(report.outbound.logLine().endsWith(" check_monolith_accepts_our_key=failed error=monolith_transport"))
    assert(report.inbound.logLine().startsWith("Warm-up inbound done took_ms="))
    assert(report.inbound.logLine().endsWith(" check_bearer_provider_wiring=401 webhook=200"))
    assert(report.anyFailed)
  }

  /** A rejected secret is what an operator has to act on now, so its line reads as a check's rejection, at error. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a rejected key is an error line naming the check`() {
    val lines = capturingLogs { runBlocking { warmUp(monolith = FakeMonolithService().apply { getStoreRejectionStatus = 401 }) } }
    val outbound = lines.single { "Warm-up outbound done" in it }
    assert(outbound.startsWith("ERROR "))
    assert(outbound.contains(" check_monolith_accepts_our_key=rejected error=monolith_401"))
  }

  /** `startWarmUp` mints the id and hands it to the step it runs after the gate opens as well. */
  @Test
  fun `the trace id it is handed is the one both requests to ourselves carry`() = runBlocking {
    val loopback = FakeWarmUpLoopbackService()
    warmUp(loopback = loopback, traceId = "warmup-0123456789abcdef")
    assert(loopback.shopifyStoreStatusCalls.single().traceId == "warmup-0123456789abcdef")
    assert(loopback.webhookDeliveryCalls.single().traceId == "warmup-0123456789abcdef")
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
    val traceId = loopback.shopifyStoreStatusCalls.single().traceId
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
    traceId: String = newWarmUpTraceId(),
  ) = warmUpBeforeTakingTraffic(
    monolith = monolith,
    loopback = loopback,
    serverBound = serverBound,
    traceId = traceId,
    budget = budget,
  )
}
