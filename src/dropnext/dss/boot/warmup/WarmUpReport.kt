package dropnext.dss.boot.warmup


/**
 * What the warm-up came to, one outcome per step, in the two parts it runs in: the outbound calls, and the requests
 * the service sent itself so the inbound pipeline ran once. Two of the steps are checks: that the monolith accepts
 * `DSS_TO_MONOLITH_API_KEY`, and that our bearer provider accepts the `MONOLITH_TO_DSS_API_KEY` it was configured with.
 * What they find decides whether the task takes traffic ([verdict]). The second proves our own wiring only: the DSS
 * cannot see the value the monolith holds, so a monolith with another key is not caught at startup. The rest only
 * explains the two log lines the warm-up leaves, one per part.
 */
data class WarmUpReport(val outbound: WarmUpOutboundReport, val inbound: WarmUpInboundReport) {
  val anyFailed: Boolean get() = outbound.anyFailed || inbound.anyFailed

  /**
   * Only a secret the other side proved wrong refuses traffic. A check that could not decide (no answer, a `5xx`, a
   * `403` from something in front of the monolith) is a failed step and the task serves anyway: a monolith that is
   * down must not stop the service from serving the shops whose tokens it already holds.
   */
  val verdict: WarmUpVerdict
    get() {
      val rejectedKeys = buildList {
        if (outbound.monolithAcceptsOurKey is WarmUpStepOutcome.Rejected) add("DSS_TO_MONOLITH_API_KEY (the monolith answered 401)")
        if (inbound.bearerProviderWiring is WarmUpStepOutcome.Rejected) {
          add("MONOLITH_TO_DSS_API_KEY (our own bearer provider refused the value it was configured with)")
        }
      }
      if (rejectedKeys.isEmpty()) return WarmUpVerdict.TakeTraffic
      return WarmUpVerdict.RefuseTraffic("Shared secret rejected: ${rejectedKeys.joinToString(" and ")}.")
    }
}

data class WarmUpOutboundReport(
  val monolithAcceptsOurKey: WarmUpStepOutcome,
  val tookMillis: Long,
) {
  /** A step that failed asks for a look; a skipped one is the normal shape of things (no time left). */
  val anyFailed: Boolean get() = monolithAcceptsOurKey.isFailure
}

data class WarmUpInboundReport(
  val bearerProviderWiring: WarmUpStepOutcome,
  val webhook: WarmUpStepOutcome,
  val tookMillis: Long,
) {
  val anyFailed: Boolean get() = listOf(bearerProviderWiring, webhook).any { it.isFailure }
}

sealed interface WarmUpStepOutcome {
  /** [detail] is what the log line shows after the `=`: `ok` for an outbound call, the HTTP status for a request to ourselves. */
  data class Ok(val detail: String = "ok") : WarmUpStepOutcome

  /** [label] is short and countable (`monolith_transport`, `no_answer`, `budget`), never an upstream's message. */
  data class Failed(val label: String) : WarmUpStepOutcome

  /** A check's answer that proves a shared secret wrong; the task does not take traffic. [label] as for [Failed]. */
  data class Rejected(val label: String) : WarmUpStepOutcome

  data class Skipped(val reason: String) : WarmUpStepOutcome
}

val WarmUpStepOutcome.isFailure: Boolean
  get() = this is WarmUpStepOutcome.Failed || this is WarmUpStepOutcome.Rejected
