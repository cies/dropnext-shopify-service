package dropnext.dss.lib.monolith

import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The level split is the contract with whoever reads the logs: an error means the monolith is down or
 * broken, a warning means we sent something it refuses. And every line carries the monolith's own
 * trace id, which is what makes its log findable from ours.
 */
@ResourceLock(GLOBAL_LOG_REGISTRY)
class LogMonolithFailureTest {

  private fun rejected(status: Int, body: String): MonolithError.Rejected {
    val (message, parsed) = monolithError(status, body)
    return MonolithError.Rejected(status, message, parsed)
  }

  @Test
  fun `a 5xx is an error line carrying the monolith's trace id and the extra context`() {
    val error = rejected(500, """{"error":"db down","code":"InternalError","trace_id":"mt-1"}""")

    val lines = capturingLogs { logMonolithFailure("postCreateOrder", error, "shopifyOrderId=1001") }

    val line = lines.single()
    assert(line.startsWith("ERROR"))
    assert("Monolith postCreateOrder failed: status=500" in line)
    assert("monolith_trace_id=mt-1" in line)
    assert("code=InternalError" in line)
    assert("message=db down" in line)
    assert("shopifyOrderId=1001" in line)
  }

  @Test
  fun `a 4xx is a warning`() {
    val lines = capturingLogs { logMonolithFailure("putStoreApiKey", rejected(409, """{"error":"Store already exists."}""")) }

    val line = lines.single()
    assert(line.startsWith("WARN"))
    assert("status=409" in line)
    assert("message=Store already exists." in line)
  }

  @Test
  fun `a transport failure is an error naming what was attempted`() {
    val lines = capturingLogs { logMonolithFailure("getStore", MonolithError.Transport("connection refused"), "subdomain=acme") }

    val line = lines.single()
    assert(line.startsWith("ERROR"))
    assert("Monolith getStore failed: no response (connection refused)" in line)
    assert("subdomain=acme" in line)
  }

  @Test
  fun `an unreadable success is an error naming the operation and the decoder's complaint`() {
    val error = MonolithError.Undecodable(200, "Expected start of the object '{', but had '<' instead")

    val lines = capturingLogs { logMonolithFailure("putStoreApiKey", error, "shop=acme.myshopify.com") }

    val line = lines.single()
    assert(line.startsWith("ERROR"))
    assert("Monolith putStoreApiKey failed: status=200 undecodable body" in line)
    assert("Expected start of the object" in line)
    assert("shop=acme.myshopify.com" in line)
  }

  @Test
  fun `a body without any known field is reported as unknown rather than blank`() {
    val lines = capturingLogs { logMonolithFailure("getStore", rejected(502, "")) }

    assert("monolith_error=unknown" in lines.single())
  }
}
