package dropnext.dss.testutil.helper

import dropnext.dss.DssDependencies
import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.dssModule
import dropnext.dss.lib.json.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication


/**
 * Runs [block] against the production application module in Ktor's in-memory test engine: the same
 * plugins, the same auth guard, the same error shaping and trace ids `main` installs. A handler test
 * that spins up its own server proves only that its own wiring works.
 *
 * The client never follows redirects, because the OAuth install answers with one and a test that
 * silently followed it would assert against Shopify's page instead of ours.
 *
 * [authenticateAsMonolith] adds the `Authorization: Bearer` header the monolith-facing routes
 * require. The Shopify webhook and OAuth routes sit outside that guard and leave it off.
 *
 * [warmUp] is none unless a test says otherwise: the production warm-up calls the monolith and Shopify on its own,
 * and a fake's recorded calls or a fake server's answer queue would count them.
 *
 * Whatever [block] answers is handed back, for the values that outlive the application — a minted trace id, a response
 * a test reads after the clients are closed. `testApplication` itself answers nothing, so a caller that needed one
 * used to hoist a `var` out of the block and fill it in from inside.
 */
fun <T> withDssApp(
  deps: DssDependencies,
  authenticateAsMonolith: Boolean = false,
  warmUp: WarmUp = WarmUp.NONE,
  block: suspend ApplicationTestBuilder.(HttpClient) -> T,
): T {
  // `testApplication` runs the block to completion before it returns, so a plain capture is safely published here.
  var answer: Answer<T>? = null
  testApplication {
    application { dssModule(deps, warmUp) }
    val client = createClient {
      followRedirects = false
      install(ClientContentNegotiation) { json(AppJson) }
      if (authenticateAsMonolith) {
        defaultRequest { header("Authorization", "Bearer ${deps.config.monolithToDssApiKey.value}") }
      }
    }
    answer = Answer(block(client))
  }
  return checkNotNull(answer) { "the test application returned before the block ran" }.value
}

/** A box, so a block answering `null` is told apart from one that never ran. */
private class Answer<out T>(val value: T)
