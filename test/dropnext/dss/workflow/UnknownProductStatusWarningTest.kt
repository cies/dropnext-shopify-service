package dropnext.dss.workflow

import dropnext.dss.mapper.MonolithProductMapping
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val unknownStatus = MonolithProductMapping(productVariants = emptyList(), hasUnknownStatus = true)
private val knownStatus = MonolithProductMapping(productVariants = emptyList(), hasUnknownStatus = false)

class UnknownProductStatusWarningTest {

  /** The decoder keeps no raw value to name, so the line names what the schema bump starts from. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `an unknown status is warned about with the product and the API version`() {
    val lines = capturingLogs { UnknownProductStatusWarning("2026-04").warnIfUnknown("101", unknownStatus) }

    val line = lines.single { "Shopify product status unknown to this build" in it }
    assert(line.startsWith("WARN "))
    assert("product_id=101" in line)
    assert("api_version=2026-04" in line)
    assert("status=" !in line)
  }

  /** A new status reaches many products at once, and every webhook and fetch maps them one by one. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `an unknown status is warned about once, however many products carry it`() {
    val warning = UnknownProductStatusWarning("2026-04")

    val lines = capturingLogs {
      warning.warnIfUnknown("101", unknownStatus)
      warning.warnIfUnknown("102", unknownStatus)
    }

    assert(lines.count { "Shopify product status unknown to this build" in it } == 1)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a known status logs nothing and leaves the warning for an unknown one`() {
    val warning = UnknownProductStatusWarning("2026-04")

    val lines = capturingLogs {
      warning.warnIfUnknown("101", knownStatus)
      warning.warnIfUnknown("102", unknownStatus)
    }

    assert(lines.single { "Shopify product status" in it }.contains("product_id=102"))
  }
}
