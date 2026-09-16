package dropnext.dss.testutil.helper

import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener


/**
 * One loopback request and one in-memory request before any test runs, for the cost they take off the first test of
 * every class that makes one.
 *
 * Why this is needed: the suite runs its classes concurrently inside a single JVM, and the first use of the Ktor CIO
 * server, the Ktor client, OkHttp and the test engine loads and JIT-compiles several thousand classes. Paid inside a
 * test, that cost lands on whichever classes happen to be picked up first — several at once, while `ArchitectureTest`
 * has the remaining cores busy parsing `src/`. The wire-level tests then measure the JVM warming up rather than the
 * code under test: a class-level fake server has been measured at thirteen seconds for what costs milliseconds warm,
 * and under the JaCoCo agent two of those tests ran past their client's request timeout and failed. Paid here it is
 * paid once, single-threaded, before the worker pool starts, so every test finds the stack loaded.
 *
 * The platform opens exactly one session per JVM, which makes this the only hook that runs before the first test
 * class. It is registered through
 * `test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`.
 *
 * Nothing here may fail the run: a warm-up that cannot run leaves the suite exactly as slow as it was before, which is
 * a worse day and not a broken build.
 */
class TestSuiteWarmUp : LauncherSessionListener {

  override fun launcherSessionOpened(session: LauncherSession) {
    warmUp("the CIO server, OkHttp and the Ktor client", ::warmUpOverLoopback)
    warmUp("the Ktor test engine", ::warmUpTestEngine)
  }

  private fun warmUp(what: String, block: () -> Unit) {
    runCatching(block).onFailure { System.err.println("[test-warm-up] could not warm $what: $it") }
  }

  /** A real socket, which is what the wire-level tests and every fake server use. */
  private fun warmUpOverLoopback() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    val client = testHttpClient()
    try {
      runBlocking { client.get("http://localhost:$port/test-suite-warm-up") }
    } finally {
      client.close()
      server.stop()
    }
  }

  /** No socket, a different stack: what every `withDssApp` request → response test goes through. */
  private fun warmUpTestEngine() {
    testApplication {
      application { routing { get("/test-suite-warm-up") { call.respond(HttpStatusCode.OK) } } }
      client.get("/test-suite-warm-up")
    }
  }
}
