package dropnext.dss.testutil.helper

import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The suite's wall-clock is the moment its last class finishes, so the order classes are handed to the worker pool
 * matters: a slow class handed out last runs alone at the end, handed out first it overlaps with everything else.
 * JUnit's default is discovery order. The list is a hint, not a contract: a name that no longer exists is simply never
 * matched. Mirrors the monolith's orderer.
 */
class SlowestClassesFirstOrderer : ClassOrderer {

  override fun orderClasses(context: ClassOrdererContext) {
    context.classDescriptors.sortWith(compareBy({ it.runsAlone() }, { it.slownessRank() }))
  }

  private fun ClassDescriptor.slownessRank(): Int =
    slowestFirst.indexOf(testClass.name).let { if (it < 0) slowestFirst.size else it }

  /**
   * A class that reads the log holds the global lock (`GLOBAL_LOG_REGISTRY`) and runs alone whenever it is picked up,
   * stalling the pool; handed out last, the rest of the suite has overlapped by then and only these drain single-file.
   * A method-level lock is invisible here and stalls the pool for that method wherever it lands.
   */
  private fun ClassDescriptor.runsAlone(): Boolean = isAnnotated(ResourceLock::class.java)

  private companion object {
    /**
     * Measured, longest first: every class that cost about a second or more in the run this list was taken from.
     * Regenerate it with `./gradlew test slowestTestClasses` and paste the output here when the timings move.
     */
    val slowestFirst = listOf(
      "dropnext.dss.ArchitectureTest", // 9.90s
      "dropnext.dss.DependenciesTest", // 6.16s, mostly the warm-up of the Ktor and OkHttp stack its first test pays
      "dropnext.dss.lib.logflare.LogflareBatchSenderTest", // 2.92s
      "dropnext.dss.lib.logflare.LogflareAppenderTest", // 1.57s
    )
  }
}
