package dropnext.dss.boot.warmup

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.drift.compareContractDocuments
import dropnext.dss.lib.monolith.drift.readCheckedInContractCopy
import dropnext.dss.lib.monolith.errorLabel
import dropnext.dss.lib.slf4j.TRACE_ID_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject


private val log = KotlinLogging.logger {}

/**
 * How many contract differences one start writes a line for. Enough to read a normal drift in full; few enough that a
 * monolith serving a wholly different document cannot flood the log. The summary line still counts them all.
 */
const val MAX_LOGGED_CONTRACT_DIFFERENCES = 20

/**
 * The monolith's document against the copy the DSS was built from, written to the log and nothing else: right after a
 * monolith deploy the two differ by design, so drift is a `warn` to fix in the next work session, never a reason to
 * stop. A document that cannot be fetched says nothing about the contract and is one `warn` line. Nothing here is
 * written at `error`, which is why a failed fetch does not go through `logMonolithFailure`.
 *
 * It runs after the readiness gate has opened ([WarmUp.afterReady]), because nothing waits on it: bounded by the
 * monolith client's timeout, not by the warm-up's budget, which it would otherwise take from the checks.
 */
suspend fun reportContractDrift(
  monolith: MonolithService,
  traceId: String,
  contractCopy: JsonObject = readCheckedInContractCopy(),
): WarmUpStepOutcome = withMdcEntries(TRACE_ID_MDC_KEY to traceId) {
  when (val result = monolith.getOpenApiDocument()) {
    is Failure -> {
      val label = result.reason.errorLabel
      log.warn { "Contract drift not checked reason=$label" }
      WarmUpStepOutcome.Failed(label)
    }
    is Success -> {
      val comparison = compareContractDocuments(monolith = result.value, copy = contractCopy)
      if (comparison.isIdentical) {
        log.info { comparison.summaryLine(shown = 0) }
        WarmUpStepOutcome.Ok("identical")
      } else {
        val shown = comparison.differences.take(MAX_LOGGED_CONTRACT_DIFFERENCES)
        shown.forEach { difference -> log.warn { difference.line } }
        log.warn { comparison.summaryLine(shown = shown.size) }
        WarmUpStepOutcome.Failed("contract_drift")
      }
    }
  }
}
