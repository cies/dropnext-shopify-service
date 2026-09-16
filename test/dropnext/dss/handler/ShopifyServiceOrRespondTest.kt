package dropnext.dss.handler

import dropnext.dss.contract.ApiError
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.ACME_SHOP
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test


/**
 * How a handler turns a [dropnext.dss.lib.shopify.token.ShopLookup] into an answer. The two refusals
 * must stay apart: a shop with no token is a `401` nobody should retry, while a token store that
 * could not be asked is a `502` the monolith is meant to come back to.
 */
class ShopifyServiceOrRespondTest {

  /** The service the route obtained, read after the request; JUnit builds a fresh instance per method. */
  private var resolved: ShopifyGraphqlService? = null

  /**
   * One route around the helper under test. The `204` is the caller's own answer: writing it at all
   * proves the helper wrote nothing, because a second response is an error in Ktor.
   */
  private fun resolving(factory: ShopifyGraphqlServiceFactory, block: suspend (HttpResponse) -> Unit) = testApplication {
    application {
      installJsonContentNegotiation()
      routing {
        get("/shop") {
          val service = call.shopifyServiceOrRespond(factory, ACME_SHOP)
          resolved = service
          if (service != null) call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    val client = createClient { install(ClientContentNegotiation) { json(AppJson) } }
    block(client.get("/shop"))
  }

  @Test
  fun `a shop whose token resolves hands the service back and answers nothing`() {
    val shopify = FakeShopifyGraphqlService()
    resolving(FakeShopifyGraphqlServiceFactory(shopify)) { response ->
      assert(resolved === shopify)
      assert(response.status == HttpStatusCode.NoContent)
    }
  }

  @Test
  fun `a shop without an Admin token is a 401 telling the caller to install the app`() {
    resolving(FakeShopifyGraphqlServiceFactory(service = null)) { response ->
      assert(resolved == null)
      assert(response.status == HttpStatusCode.Unauthorized)
      assert(response.body<ApiError>().error == DssError.MissingShopifyAdminToken.message)
    }
  }

  /** A `401` here would tell the monolith to stop asking for a shop that may well have a token. */
  @Test
  fun `a token lookup the monolith did not answer is a 502`() {
    resolving(FakeShopifyGraphqlServiceFactory(tokenSourceUnavailable = true)) { response ->
      assert(resolved == null)
      assert(response.status == HttpStatusCode.BadGateway)
      assert(response.body<ApiError>().error == DssError.ShopifyAdminTokenUnavailable.message)
    }
  }
}
