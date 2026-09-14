package dropnext.dss.testutil.helper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.parallel.Resources
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/**
 * The lock a test declares when it reads the root logger. It is JUnit's global lock, the one `@Isolated` takes: every
 * test writes to that logger, so a test asserting on its lines can only be right while nothing else runs. A named lock
 * would keep the readers apart and let the writers carry on. On a method it stops the rest of the suite for that method
 * only; on a class, for the class.
 */
const val GLOBAL_LOG_REGISTRY = Resources.GLOBAL

/**
 * Logback's context, once SLF4J has finished binding to it. Test classes run concurrently, and SLF4J hands the threads
 * that ask for a logger while another thread is still binding a substitute, which delegates fine but is not Logback's
 * type: a test that attaches an appender or reads a level has to wait the binding out. Bounded, so a broken binding
 * fails the test instead of hanging the suite.
 */
fun logbackContext(): LoggerContext {
  val deadline = System.nanoTime() + 10_000_000_000L
  while (true) {
    val factory = LoggerFactory.getILoggerFactory()
    if (factory is LoggerContext) return factory
    check(System.nanoTime() < deadline) { "SLF4J did not finish binding to Logback: got ${factory::class.qualifiedName}" }
    Thread.sleep(5)
  }
}

/**
 * Every line logged anywhere while [block] runs, as `LEVEL message` plus the MDC. Attaches a real
 * Logback appender to the root logger rather than reading a captured stream, so it sees exactly what
 * an appender shipping to Logflare would see.
 *
 * The root logger is global and every test writes to it: a test using this declares `@ResourceLock(GLOBAL_LOG_REGISTRY)`
 * so that it runs alone.
 */
fun capturingLogs(block: () -> Unit): List<String> {
  val recorded = CopyOnWriteArrayList<String>()
  val appender = object : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
      val mdc = event.mdcPropertyMap.entries.joinToString(",") { "${it.key}=${it.value}" }
      recorded += "${event.level} ${event.formattedMessage} {$mdc}"
      event.throwableProxy?.let { recorded += "${event.level} THROWN ${it.className}: ${it.message}" }
    }
  }
  val root = logbackContext().getLogger(Logger.ROOT_LOGGER_NAME)
  val originalLevel = root.level
  appender.context = root.loggerContext
  appender.start()
  root.addAppender(appender)
  root.level = Level.TRACE
  try {
    block()
  } finally {
    root.level = originalLevel
    root.detachAppender(appender)
    appender.stop()
  }
  return recorded.toList()
}

/**
 * The MDC part of a [capturingLogs] line. A message can carry the same `key=value` text as a field (the summary line's
 * `topic=`), so a test asserting on the field reads it here rather than finding the text anywhere in the line.
 */
fun mdcOf(line: String): Map<String, String> =
  line.substringAfterLast(" {", missingDelimiterValue = "").removeSuffix("}")
    .split(",")
    .filter { it.isNotEmpty() }
    .associate { entry -> entry.substringBefore("=") to entry.substringAfter("=") }
