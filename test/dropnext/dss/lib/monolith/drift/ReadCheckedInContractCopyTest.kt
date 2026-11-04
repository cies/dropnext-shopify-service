package dropnext.dss.lib.monolith.drift

import dropnext.dss.lib.json.MonolithJson
import dropnext.dss.testutil.helper.projectRoot
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test


class ReadCheckedInContractCopyTest {

  /** What the running service compares against has to be the file the contract DTOs were generated from. */
  @Test
  fun `the copy on the classpath is the checked-in file`() {
    val checkedIn = File(projectRoot, "src/resources/$CONTRACT_COPY_RESOURCE").readText()
    assert(readCheckedInContractCopy() == MonolithJson.parseToJsonElement(checkedIn).jsonObject)
  }
}
