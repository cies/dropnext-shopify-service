package dropnext.dss.lib.monolith.drift

import dropnext.dss.lib.json.MonolithJson
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject


/** Where the monolith's `renderDssOpenApi` task writes its document, relative to the monolith checkout. */
const val RENDERED_MONOLITH_DOCUMENT = "build/dss-openapi/openapi.json"

enum class ContractCopyVerdict { SKIPPED, IDENTICAL, ADDITIVE_DRIFT, BREAKING_DRIFT }

/**
 * The build's half of the drift check: the document the sibling monolith checkout last rendered against the
 * checked-in [copyFile], with every difference printed. Run by the `checkMonolithContractCopy` Gradle task, which
 * fails the build on [ContractCopyVerdict.BREAKING_DRIFT] only, so the additive monolith change that deploys first
 * keeps the DSS building in between.
 *
 * Reads two files and writes none: it never renders the monolith's document (that is the monolith's build, and the
 * human's call), never reaches the network, and never refreshes the copy. A missing checkout or an unrendered
 * document is a skip with the command that renders it, because a DSS checkout on its own, CI or the Docker build, has
 * no sibling to compare with.
 */
fun checkContractCopy(
  copyFile: File,
  monolithCheckout: File,
  print: (String) -> Unit,
  describeRevision: (File) -> String = ::gitRevisionOf,
): ContractCopyVerdict {
  val renderCommand = "(cd ${monolithCheckout.path} && ./gradlew renderDssOpenApi)"
  if (!monolithCheckout.isDirectory) {
    print("Contract copy check skipped: no monolith checkout at ${monolithCheckout.path}.")
    return ContractCopyVerdict.SKIPPED
  }
  val rendered = File(monolithCheckout, RENDERED_MONOLITH_DOCUMENT)
  if (!rendered.isFile) {
    print("Contract copy check skipped: ${rendered.path} has not been rendered. Render it with: $renderCommand")
    return ContractCopyVerdict.SKIPPED
  }
  val monolith = jsonObjectOrNull(rendered.readText())
  if (monolith == null) {
    print("Contract copy check skipped: ${rendered.path} is not a JSON object. Render it again with: $renderCommand")
    return ContractCopyVerdict.SKIPPED
  }
  val copy = jsonObjectOrNull(copyFile.readText()) ?: error("${copyFile.path} is not a JSON object")

  val renderedAt = Instant.ofEpochMilli(rendered.lastModified())
  print(
    "Contract copy check read ${rendered.path} (rendered $renderedAt) " +
      "from the monolith checkout ${monolithCheckout.path}, ${describeRevision(monolithCheckout)}.",
  )
  val comparison = compareContractDocuments(monolith = monolith, copy = copy)
  comparison.differences.forEach { print(it.line) }
  print(comparison.summaryLine(shown = comparison.differences.size))

  val refresh = "cp ${rendered.path} ${copyFile.path}"
  return when {
    comparison.isIdentical -> {
      print("Contract copy check passed: the copy matches the monolith's document.")
      ContractCopyVerdict.IDENTICAL
    }
    comparison.breakingCount == 0 -> {
      print("Contract copy check passed: the differences are additive. Refresh the copy when the DSS should use them: $refresh")
      ContractCopyVerdict.ADDITIVE_DRIFT
    }
    else -> {
      print(
        "Contract copy check failed: ${comparison.breakingCount} breaking difference(s). " +
          "Refresh the copy and fix the DSS in the same work session: $refresh",
      )
      ContractCopyVerdict.BREAKING_DRIFT
    }
  }
}

/** `checkContractCopy <checked-in copy> <monolith checkout>`; the exit status is what fails the Gradle task. */
fun main(args: Array<String>) {
  require(args.size == 2) { "Expected two arguments: the checked-in copy and the monolith checkout, got ${args.size}." }
  val verdict = checkContractCopy(File(args[0]), File(args[1]), print = ::println)
  if (verdict == ContractCopyVerdict.BREAKING_DRIFT) exitProcess(1)
}

private fun jsonObjectOrNull(text: String): JsonObject? =
  runCatching { MonolithJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

/**
 * Which branch and revision the checkout is on, so a failure against someone's feature branch reads as such at a
 * glance. Local git plumbing only, no fetch. A checkout git cannot read is reported as unknown, not as a failure.
 */
private fun gitRevisionOf(checkout: File): String {
  val branch = gitOutput(checkout, "rev-parse", "--abbrev-ref", "HEAD") ?: "unknown"
  val revision = gitOutput(checkout, "rev-parse", "--short=12", "HEAD") ?: "unknown"
  val changes = gitOutput(checkout, "status", "--porcelain", "--untracked-files=no")
  val dirty = if (changes.isNullOrEmpty()) "" else " with uncommitted changes"
  return "branch=$branch revision=$revision$dirty"
}

private fun gitOutput(checkout: File, vararg arguments: String): String? = runCatching {
  val process = ProcessBuilder(listOf("git", "-C", checkout.path) + arguments)
    .redirectErrorStream(true)
    .start()
  val output = process.inputStream.bufferedReader().readText().trim()
  val exited = process.waitFor(5, TimeUnit.SECONDS)
  output.takeIf { exited && process.exitValue() == 0 }
}.getOrNull()
