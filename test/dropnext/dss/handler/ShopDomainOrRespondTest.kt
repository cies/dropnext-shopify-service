package dropnext.dss.handler

import dropnext.dss.contract.ApiError
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.testutil.fixture.ACME_SHOP
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test


/**
 * The two ways a route turns anonymous `shop=` input into a [ShopDomain]. They differ only in what
 * they write when the value is not one: the JSON contract routes answer an `ApiError`, the OAuth
 * routes answer text, because a merchant's browser is reading it.
 *
 * Both are exercised through a route, because what they do on the refusal is write a response; a
 * direct call could only observe the `null`.
 */
class ShopDomainOrRespondTest {

  /** What the route under test parsed, read after the request; JUnit builds a fresh instance per method. */
  private var parsed: ShopDomain? = null

  /**
   * One route around the helper under test. The `204` is the caller's own answer: writing it at all
   * proves the helper wrote nothing, because a second response is an error in Ktor.
   */
  private fun parsing(raw: String, plainText: Boolean, block: suspend (HttpResponse) -> Unit) = testApplication {
    application {
      installJsonContentNegotiation()
      routing {
        get("/shop") {
          val shop = if (plainText) call.shopDomainOrRespondText(raw, "shop") else call.shopDomainOrRespond(raw, "shop")
          parsed = shop
          if (shop != null) call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    val client = createClient { install(ClientContentNegotiation) { json(AppJson) } }
    block(client.get("/shop"))
  }

  @Test
  fun `an accepted shop is normalized and left for the caller to answer`() = parsing("Acme.MyShopify.com", plainText = false) { response ->
    assert(parsed == ACME_SHOP)
    assert(response.status == HttpStatusCode.NoContent)
  }

  @Test
  fun `a value that is not a Shopify domain is a 400 naming the parameter`() = parsing("evil.com/acme.myshopify.com", plainText = false) { response ->
    assert(parsed == null)
    assert(response.status == HttpStatusCode.BadRequest)
    assert(response.body<ApiError>().error == "Invalid shop: not a valid Shopify domain")
  }

  @Test
  fun `the plain-text twin also leaves an accepted shop for the caller to answer`() = parsing("Acme.MyShopify.com", plainText = true) { response ->
    assert(parsed == ACME_SHOP)
    assert(response.status == HttpStatusCode.NoContent)
  }

  /**
   * The same refusal as its JSON twin — the message comes from the one `DssError.InvalidParameter`,
   * which the twin's test pins — but as text, so the OAuth routes never hand a browser a JSON body.
   */
  @Test
  fun `the plain-text twin refuses in text rather than in JSON`() = parsing("evil.com/acme.myshopify.com", plainText = true) { response ->
    assert(parsed == null)
    assert(response.status == HttpStatusCode.BadRequest)
    assert(response.contentType()?.withoutParameters() == ContentType.Text.Plain)
  }
}
