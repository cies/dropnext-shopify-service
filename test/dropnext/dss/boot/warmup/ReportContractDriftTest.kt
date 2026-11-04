package dropnext.dss.boot.warmup

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Failed
import dropnext.dss.boot.warmup.WarmUpStepOutcome.Ok
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.drift.compareContractDocuments
import dropnext.dss.lib.monolith.drift.readCheckedInContractCopy
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.contractDocument
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The startup drift check runs after the gate has opened and decides nothing, so its whole observable behavior is what
 * it writes: every difference at `warn`, a summary, and nothing at `error`, because nothing it can find is worth waking
 * someone for.
 */
class ReportContractDriftTest {

  private val traceId = "warmup-0123456789abcdef"

  @Test
  fun `a monolith serving the checked-in document is identical`() = runBlocking {
    val monolith = FakeMonolithService()
    val outcome = reportContractDrift(monolith, traceId)
    assert(monolith.getOpenApiDocumentCalls.size == 1)
    assert(outcome == Ok("identical"))
  }

  @Test
  fun `a drifted contract is a failed step`() = runBlocking {
    assert(reportContractDrift(monolithServing(withAddedPath(readCheckedInContractCopy())), traceId) == Failed("contract_drift"))
  }

  @Test
  fun `a document the monolith does not serve is not fetchable`() = runBlocking {
    val notFound = FakeMonolithService().apply {
      openApiDocumentResult = Failure(MonolithError.Undecodable(200, "not a JSON object"))
    }
    assert(reportContractDrift(notFound, traceId) == Failed("monolith_undecodable"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a drifted contract writes each difference and the summary at warn and nothing at error`() {
    val drifted = withAddedPath(readCheckedInContractCopy())
    val lines = capturingLogs { runBlocking { reportContractDrift(monolithServing(drifted), traceId) } }
    val driftLine = lines.single { "Contract drift kind=" in it }
    assert(driftLine.startsWith("WARN Contract drift kind=path location=/api/shopify-service/v1/added side=monolith_only"))
    val summary = lines.single { "Contract drift summary" in it }
    assert(summary.startsWith("WARN Contract drift summary differences=1 shown=1 breaking=0"))
    assert(lines.none { it.startsWith("ERROR") })
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `an identical contract writes one info summary and no difference line`() {
    val lines = capturingLogs { runBlocking { reportContractDrift(FakeMonolithService(), traceId) } }
    assert(lines.single { "Contract drift summary" in it }.startsWith("INFO Contract drift summary differences=0 shown=0"))
    assert(lines.none { "Contract drift kind=" in it })
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a document that cannot be fetched is one warn line and nothing at error`() {
    val unreachable = FakeMonolithService().apply {
      openApiDocumentResult = Failure(MonolithError.Transport("connection refused"))
    }
    val lines = capturingLogs { runBlocking { reportContractDrift(unreachable, traceId) } }
    assert(lines.single { "Contract drift" in it }.startsWith("WARN Contract drift not checked reason=monolith_transport"))
    assert(lines.none { it.startsWith("ERROR") })
  }

  /** A document from another service, or an error page that happened to parse, must not flood one start. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a document that differs in more ways than the cap writes the cap and a summary naming the true total`() {
    val manyPaths = (1..MAX_LOGGED_CONTRACT_DIFFERENCES + 5).joinToString(",") { """"/api/v9/other-$it":{}""" }
    val unrelated = contractDocument(paths = manyPaths)
    val total = compareContractDocuments(monolith = unrelated, copy = readCheckedInContractCopy()).differences.size
    val lines = capturingLogs { runBlocking { reportContractDrift(monolithServing(unrelated), traceId) } }
    assert(total > MAX_LOGGED_CONTRACT_DIFFERENCES)
    assert(lines.count { "Contract drift kind=" in it } == MAX_LOGGED_CONTRACT_DIFFERENCES)
    val summary = lines.single { "Contract drift summary" in it }
    assert(" differences=$total shown=$MAX_LOGGED_CONTRACT_DIFFERENCES " in summary)
  }

  /** It runs on a coroutine of its own, after the warm-up's lines; the trace id is what still ties them together. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every line it writes carries the warm-up's trace id`() {
    val drifted = withAddedPath(readCheckedInContractCopy())
    val lines = capturingLogs { runBlocking { reportContractDrift(monolithServing(drifted), traceId) } }
    val driftLines = lines.filter { "Contract drift" in it }
    assert(driftLines.size == 2)
    assert(driftLines.all { it.endsWith("{trace_id=$traceId}") })
  }

  private fun monolithServing(document: JsonObject) = FakeMonolithService().apply { openApiDocumentResult = Success(document) }

  /** The checked-in copy plus one path, which is the additive monolith change that deploys before the DSS. */
  private fun withAddedPath(document: JsonObject): JsonObject {
    val paths = document["paths"] as JsonObject
    return JsonObject(document + ("paths" to JsonObject(paths + ("/api/shopify-service/v1/added" to JsonObject(emptyMap())))))
  }
}
