package dropnext.dss.boot.config

import java.io.File
import org.junit.jupiter.api.Test


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
        SHOPIFY_APP_CLIENT_ID=client-id

        export PORT=9999
        MONOLITH_BASE_URL="https://monolith.example"
        not a pair
        """.trimIndent(),
      )
    }
    val env = readDotEnvFile(file)
    assert(env == mapOf(
      "SHOPIFY_APP_CLIENT_ID" to "client-id",
      "PORT" to "9999",
      "MONOLITH_BASE_URL" to "\"https://monolith.example\"",
    ))
  }
}
