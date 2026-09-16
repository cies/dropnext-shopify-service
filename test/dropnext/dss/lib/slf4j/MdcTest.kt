package dropnext.dss.lib.slf4j

import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.mdcOf
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.MDC


private val log = KotlinLogging.logger {}

/**
 * A handler learns the shop from the request and wants it on every line it causes. The MDC is thread-local and a
 * suspended block resumes on whatever thread is free, so what these pin above all is the line logged after a suspension.
 */
class MdcTest {

  @Test
  fun `puts every entry for the length of the block`() = runBlocking {
    withMdcEntries(SHOP_MDC_KEY to "acme.myshopify.com", TOPIC_MDC_KEY to "orders/create") {
      assert(MDC.get(SHOP_MDC_KEY) == "acme.myshopify.com")
      assert(MDC.get(TOPIC_MDC_KEY) == "orders/create")
    }
  }

  /** So a caller can pass an optional value without branching around the block. */
  @Test
  fun `skips an entry whose value is null`() = runBlocking {
    withMdcEntries(SHOP_MDC_KEY to null, TOPIC_MDC_KEY to "orders/create") {
      assert(SHOP_MDC_KEY !in MDC.getCopyOfContextMap().orEmpty())
      assert(MDC.get(TOPIC_MDC_KEY) == "orders/create")
    }
  }

  @Test
  fun `removes its keys when the block returns, also after it resumed on another thread`() = runBlocking(Dispatchers.IO) {
    withMdcEntries(SHOP_MDC_KEY to "acme.myshopify.com") { }
    assert(MDC.get(SHOP_MDC_KEY) == null)
    withMdcEntries(SHOP_MDC_KEY to "acme.myshopify.com") { delay(5) }
    assert(MDC.get(SHOP_MDC_KEY) == null)
  }

  @Test
  fun `removes its keys when the block throws`() = runBlocking {
    val thrown = runCatching { withMdcEntries(SHOP_MDC_KEY to "acme.myshopify.com") { error("boom") } }.exceptionOrNull()
    assert(thrown is IllegalStateException)
    assert(MDC.get(SHOP_MDC_KEY) == null)
  }

  /** Restoring what was there rather than removing the key, so an inner block narrows a value for its own lines only. */
  @Test
  fun `a nested block restores the outer value when it returns, also after suspending`() = runBlocking(Dispatchers.IO) {
    withMdcEntries(SHOP_MDC_KEY to "outer.myshopify.com") {
      withMdcEntries(SHOP_MDC_KEY to "inner.myshopify.com") {
        delay(5)
        assert(MDC.get(SHOP_MDC_KEY) == "inner.myshopify.com")
      }
      assert(MDC.get(SHOP_MDC_KEY) == "outer.myshopify.com")
      withMdcEntries(SHOP_MDC_KEY to "inner.myshopify.com") { }
      assert(MDC.get(SHOP_MDC_KEY) == "outer.myshopify.com")
    }
  }

  /** In a request the trace id is already in the MDC, put there by Ktor's own `MDCContext`; adding the shop must keep it. */
  @Test
  fun `keeps what an enclosing MDCContext holds, such as the trace id`() = runBlocking(Dispatchers.IO) {
    withContext(MDCContext(mapOf(TRACE_ID_MDC_KEY to "trace-outer"))) {
      withMdcEntries(SHOP_MDC_KEY to "acme.myshopify.com") {
        delay(5)
        assert(currentTraceId() == "trace-outer")
      }
      assert(currentTraceId() == "trace-outer")
      assert(MDC.get(SHOP_MDC_KEY) == null)
    }
  }

  /**
   * The fact the helper exists for. Concurrent on purpose, as in `InstallCallIdTest`: one caller at a time mostly
   * resumes on the thread it left, which lets a plain `MDC.put` pass.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every concurrent caller logs its own value after a suspension`() {
    val lines = capturingLogs {
      runBlocking {
        (1..32)
          .map { index ->
            async(Dispatchers.IO) {
              withMdcEntries(SHOP_MDC_KEY to "shop-%03d".format(index)) {
                delay(5)
                log.info { "resumed caller=shop-%03d".format(index) }
              }
            }
          }
          .awaitAll()
      }
    }

    val resumed = lines.filter { "resumed caller=" in it }
    val crossed = resumed.filter { line -> mdcOf(line)[SHOP_MDC_KEY] != line.substringAfter("caller=").substringBefore(" ") }
    assert(resumed.size == 32)
    assert(crossed.isEmpty())
  }
}
