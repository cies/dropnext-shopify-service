package dropnext.dss.lib.monolith.drift

import dropnext.dss.testutil.fixture.ORDERS_PATH
import dropnext.dss.testutil.fixture.ORDER_SCHEMAS
import dropnext.dss.testutil.fixture.STORES_PATH
import dropnext.dss.testutil.fixture.contractDocument
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir


/**
 * The build's half of the drift check, against a directory that stands in for the sibling monolith checkout. It
 * fails the build on a breaking difference only, skips with the command that renders the document when there is
 * nothing to compare with, and leaves the checked-in copy as it found it.
 */
class CheckContractCopyTest {

  private val copyDocument = contractDocument(paths = "$ORDERS_PATH,$STORES_PATH", schemas = ORDER_SCHEMAS)

  @Test
  fun `no sibling checkout is a skip with a reason`(@TempDir dir: File) {
    val printed = mutableListOf<String>()
    val verdict = checkContractCopy(copyFile(dir), File(dir, "dropnext-monolith"), printed::add, fixedRevision)
    assert(verdict == ContractCopyVerdict.SKIPPED)
    assert(printed.single().startsWith("Contract copy check skipped: no monolith checkout at "))
  }

  @Test
  fun `a checkout that has not rendered its document is a skip naming the command that renders it`(@TempDir dir: File) {
    val checkout = File(dir, "dropnext-monolith").apply { mkdirs() }
    val printed = mutableListOf<String>()
    val verdict = checkContractCopy(copyFile(dir), checkout, printed::add, fixedRevision)
    assert(verdict == ContractCopyVerdict.SKIPPED)
    assert("has not been rendered" in printed.single())
    assert(printed.single().endsWith("Render it with: (cd ${checkout.path} && ./gradlew renderDssOpenApi)"))
  }

  @Test
  fun `a rendered document that is not JSON is a skip, not a failure`(@TempDir dir: File) {
    val checkout = checkoutRendering(dir, "<html>an error page</html>")
    val verdict = checkContractCopy(copyFile(dir), checkout, {}, fixedRevision)
    assert(verdict == ContractCopyVerdict.SKIPPED)
  }

  @Test
  fun `an identical document passes and says which checkout, branch and revision it read`(@TempDir dir: File) {
    val checkout = checkoutRendering(dir, copyDocument.toString())
    val printed = mutableListOf<String>()
    val verdict = checkContractCopy(copyFile(dir), checkout, printed::add, fixedRevision)
    assert(verdict == ContractCopyVerdict.IDENTICAL)
    val read = printed.first()
    assert(read.startsWith("Contract copy check read ${File(checkout, RENDERED_MONOLITH_DOCUMENT).path} (rendered "))
    assert(read.endsWith("from the monolith checkout ${checkout.path}, branch=feature/x revision=abc123."))
    assert(printed.any { it.startsWith("Contract drift summary differences=0 ") })
  }

  /** The monolith deploys first, so an additive change keeps the DSS building until the copy is refreshed. */
  @Test
  fun `an additive difference is printed and passes`(@TempDir dir: File) {
    val added = """"/api/shopify-service/v1/added": {"get": {"responses": {"200": {"description": "OK"}}}}"""
    val monolith = contractDocument(paths = "$ORDERS_PATH,$STORES_PATH,$added", schemas = ORDER_SCHEMAS)
    val printed = mutableListOf<String>()
    val verdict = checkContractCopy(copyFile(dir), checkoutRendering(dir, monolith.toString()), printed::add, fixedRevision)
    assert(verdict == ContractCopyVerdict.ADDITIVE_DRIFT)
    assert("Contract drift kind=path location=/api/shopify-service/v1/added side=monolith_only breaking=false" in printed)
  }

  @Test
  fun `a breaking difference is printed and fails, and the copy is left as it was`(@TempDir dir: File) {
    val copy = copyFile(dir)
    val before = copy.readText()
    val monolith = contractDocument(paths = STORES_PATH, schemas = ORDER_SCHEMAS)
    val printed = mutableListOf<String>()
    val verdict = checkContractCopy(copy, checkoutRendering(dir, monolith.toString()), printed::add, fixedRevision)
    assert(verdict == ContractCopyVerdict.BREAKING_DRIFT)
    assert("Contract drift kind=path location=/api/shopify-service/v1/orders side=copy_only breaking=true" in printed)
    assert(printed.last().startsWith("Contract copy check failed: 1 breaking difference(s)."))
    assert(copy.readText() == before)
  }

  // ---------- helpers ----------

  private fun copyFile(dir: File): File = File(dir, "monolith-dss-openapi.json").apply { writeText(copyDocument.toString()) }

  private fun checkoutRendering(dir: File, document: String): File {
    val checkout = File(dir, "dropnext-monolith")
    File(checkout, RENDERED_MONOLITH_DOCUMENT).apply {
      parentFile.mkdirs()
      writeText(document)
    }
    return checkout
  }

  /** Stands in for git, which the suite does not run: the same answer for every checkout. */
  private val fixedRevision: (File) -> String = { "branch=feature/x revision=abc123" }
}
