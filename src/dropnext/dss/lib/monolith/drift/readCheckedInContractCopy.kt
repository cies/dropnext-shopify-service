package dropnext.dss.lib.monolith.drift

import dropnext.dss.lib.json.MonolithJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject


/** Where the build puts `src/resources/monolith-dss-openapi.json` on the classpath. */
const val CONTRACT_COPY_RESOURCE = "monolith-dss-openapi.json"

/**
 * The checked-in copy of the monolith's document, as the running service carries it in its jar. The build fails
 * without it (the contract DTOs are generated from it), so a missing resource is a packaging bug and throws.
 */
fun readCheckedInContractCopy(): JsonObject {
  val loader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
  val text = loader.getResourceAsStream(CONTRACT_COPY_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
    ?: error("$CONTRACT_COPY_RESOURCE is not on the classpath")
  return MonolithJson.parseToJsonElement(text).jsonObject
}
