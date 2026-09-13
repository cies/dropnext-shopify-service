package dropnext.dss.boot.config

import java.io.File
import kotlin.test.Test


class ReadDotEnvFileTest {

  @Test
  fun `a missing file is an empty map`() {
    assert(readDotEnvFile(File("does-not-exist.env")).isEmpty())
  }

  @Test
  fun `reads key value pairs and skips comments, blanks and the export prefix`() {
    val file = File.createTempFile("dss", ".env").apply {
      deleteOnExit()
      writeText(
        """
        # a comment
        SHOPIFY_SCOPES=read_orders,write_fulfillments

        export PORT=9999
        MONOLITH_BASE_URL="https://monolith.example"
        not a pair
        """.trimIndent(),
      )
    }
    val env = readDotEnvFile(file)
    assert(env == mapOf(
      "SHOPIFY_SCOPES" to "read_orders,write_fulfillments",
      "PORT" to "9999",
      "MONOLITH_BASE_URL" to "\"https://monolith.example\"",
    ))
  }
}
