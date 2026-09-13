package dropnext.dss.boot.config

import java.io.File


/**
 * The `KEY=VALUE` pairs of a dotenv file, or an empty map when there is none.
 *
 * Only present in local development; in a container the configuration flows in through real environment variables.
 * Values keep their quotes: [Config.from] strips them the same way for both sources.
 */
fun readDotEnvFile(file: File): Map<String, String> {
  if (!file.isFile) return emptyMap()
  return file.readLines().asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { it.removePrefix("export ") }
    .filter { '=' in it }
    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}
