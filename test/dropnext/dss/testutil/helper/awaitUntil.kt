package dropnext.dss.testutil.helper

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay


/**
 * Polls [condition] until it holds or [timeout] passes, and answers which of the two happened, so the caller asserts
 * the fact rather than the waiting.
 *
 * Every wait in the suite is bounded. A loop without a deadline turns a broken condition into a run that never ends
 * and prints nothing, where a bounded one fails its assertion and names what it was waiting for.
 */
suspend fun awaitUntil(
  timeout: Duration = 5.seconds,
  poll: Duration = 10.milliseconds,
  condition: suspend () -> Boolean,
): Boolean {
  val deadline = System.nanoTime() + timeout.inWholeNanoseconds
  while (System.nanoTime() < deadline) {
    if (condition()) return true
    delay(poll)
  }
  return condition()
}

/**
 * [awaitUntil] for what happens on a thread rather than in a coroutine: the log shipper's flush thread, an appender's
 * report to standard error.
 */
fun awaitUntilBlocking(
  timeout: Duration = 5.seconds,
  poll: Duration = 20.milliseconds,
  condition: () -> Boolean,
): Boolean {
  val deadline = System.nanoTime() + timeout.inWholeNanoseconds
  while (System.nanoTime() < deadline) {
    if (condition()) return true
    Thread.sleep(poll.inWholeMilliseconds)
  }
  return condition()
}
