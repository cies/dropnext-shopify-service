package dropnext.dss.boot.warmup


/**
 * What the warm-up came to, one outcome per step, in the two parts it runs in: the outbound calls that pay the first
 * HTTPS call's cost, and the requests the service sent itself so the inbound pipeline ran once. Nothing here is acted
 * on; the parts exist for the two log lines the warm-up leaves, one per part, and for the tests.
 */
data class WarmUpReport(val outbound: WarmUpOutboundReport, val inbound: WarmUpInboundReport) {
  val anyFailed: Boolean get() = outbound.anyFailed || inbound.anyFailed
}

data class WarmUpOutboundReport(val monolith: WarmUpStepOutcome, val tookMillis: Long) {
  /** A step that failed asks for a look; a skipped one is the normal shape of things (no time left). */
  val anyFailed: Boolean get() = monolith is WarmUpStepOutcome.Failed
}

data class WarmUpInboundReport(val apiCheck: WarmUpStepOutcome, val webhook: WarmUpStepOutcome, val tookMillis: Long) {
  val anyFailed: Boolean get() = listOf(apiCheck, webhook).any { it is WarmUpStepOutcome.Failed }
}

sealed interface WarmUpStepOutcome {
  /** [detail] is what the log line shows after the `=`: `ok` for an outbound call, the HTTP status for a request to ourselves. */
  data class Ok(val detail: String = "ok") : WarmUpStepOutcome

  /** [label] is short and countable (`monolith_transport`, `no_answer`, `budget`), never an upstream's message. */
  data class Failed(val label: String) : WarmUpStepOutcome

  data class Skipped(val reason: String) : WarmUpStepOutcome
}
