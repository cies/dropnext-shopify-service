package dropnext.dss.lib.ktor

import dropnext.dss.dssDependencies
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test


/**
 * The body limit guards the one route that reads its body before authenticating anything: a webhook
 * is only trusted once the HMAC over the whole body checks out, so the body has to be read first, and
 * without a cap anyone could make the service buffer a body of any size.
 */
class InstallRequestBodyLimitTest {

  private fun receivingApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      installRequestBodyLimit()
      installStatusPages(plainTextErrorPaths = emptySet())
      installJsonContentNegotiation()
      routing { post("/sink") { call.receive<ByteArray>(); call.respond(HttpStatusCode.OK) } }
    }
    block()
  }

  @Test
  fun `a body at the limit is read`() = receivingApp {
    val response = client.post("/sink") { setBody(ByteArray(MAX_REQUEST_BODY_BYTES.toInt())) }
    assert(response.status == HttpStatusCode.OK)
  }

  @Test
  fun `a body over the limit is a 413`() = receivingApp {
    val response = client.post("/sink") { setBody(ByteArray(MAX_REQUEST_BODY_BYTES.toInt() + 1)) }
    assert(response.status == HttpStatusCode.PayloadTooLarge)
  }

  /** Through the production module: the oversized delivery is refused before the HMAC or the shop are looked at. */
  @Test
  fun `an oversized webhook is refused by the module and reaches no handler`() {
    val monolith = FakeMonolithService()
    val deps = dssDependencies(config = testConfig(), monolithService = monolith)
    withDssApp(deps) { client ->
      val response = client.post(Paths.webhooksShopify) { setBody(ByteArray(MAX_REQUEST_BODY_BYTES.toInt() + 1)) }
      assert(response.status == HttpStatusCode.PayloadTooLarge)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }
}
