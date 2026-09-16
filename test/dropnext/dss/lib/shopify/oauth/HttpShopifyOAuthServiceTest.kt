package dropnext.dss.lib.shopify.oauth

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.json.AppJson
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.testutil.helper.successValue
import dropnext.dss.testutil.helper.throwingHttpClient
import dropnext.dss.testutil.helper.withFakeShopifyServer
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.Test


private val shop = ShopDomain.parse("acme.myshopify.com")!!
private val now = Instant.parse("2026-05-22T12:00:00Z")

class HttpShopifyOAuthServiceTest {

  // The signing and URL cases never reach the network; a client that fails every request says so if one ever does.
  @AutoClose
  private val httpClient = throwingHttpClient(IllegalStateException("the signing and URL cases send no request"))
  private val client = oauthService(secret = "client-secret-xyz")

  private fun oauthService(secret: String) = HttpShopifyOAuthService(
    httpClient = httpClient,
    clientId = "client-id-123",
    clientSecret = ShopifyAppSecret(secret),
    redirectUrl = "https://dss.example.com/oauth/callback",
  )

  // ---------- authorizeUrl ----------

  @Test
  fun `authorizeUrl includes all required parameters`() {
    val url = client.authorizeUrl(shop, "state-xyz")
    assert(url.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-123" in url)
    assert("scope=read_products%2Cread_orders%2Cwrite_merchant_managed_fulfillment_orders%2Cwrite_third_party_fulfillment_orders%2Cwrite_fulfillments&" in url)
    assert("redirect_uri=https%3A%2F%2Fdss.example.com%2Foauth%2Fcallback" in url)
    assert("state=state-xyz" in url)
  }

  @Test
  fun `authorizeUrl encodes special characters in state`() {
    val url = client.authorizeUrl(shop, "a b|c")
    assert("state=a+b%7Cc" in url)
  }

  // ---------- signed state ----------

  @Test
  fun `isSignedStateValid accepts a state signed for the same shop`() {
    val state = client.signedState(shop, now)
    assert(client.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `isSignedStateValid accepts a state a few seconds after signing`() {
    val state = client.signedState(shop, now)
    assert(client.isSignedStateValid(state, shop, now.plusSeconds(30)))
  }

  @Test
  fun `isSignedStateValid refuses a state after 5 minutes`() {
    val state = client.signedState(shop, now)
    assert(!client.isSignedStateValid(state, shop, now.plusSeconds(301)))
  }

  @Test
  fun `isSignedStateValid rejects mismatched shop`() {
    val state = client.signedState(shop, now)
    val other = ShopDomain.parse("other.myshopify.com")!!
    assert(!client.isSignedStateValid(state, other, now))
  }

  @Test
  fun `isSignedStateValid rejects wrong secret`() {
    val state = client.signedState(shop, now)
    val otherClient = oauthService(secret = "different-secret")
    assert(!otherClient.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `isSignedStateValid rejects tampered shop portion`() {
    val state = client.signedState(shop, now)
    val parts = state.split('|')
    val tampered = listOf("evil.myshopify.com", parts[1], parts[2], parts[3]).joinToString("|")
    val evil = ShopDomain.parse("evil.myshopify.com")!!
    assert(!client.isSignedStateValid(tampered, evil, now))
  }

  /**
   * The expiry is what keeps a leaked redirect from being replayed, so it has to be under the signature: extended, with
   * the shop and the original signature kept, only the signature can refuse it.
   */
  @Test
  fun `isSignedStateValid rejects a state whose expiry was extended after signing`() {
    val parts = client.signedState(shop, now).split('|')
    val later = now.plusSeconds(3_600)
    val extended = listOf(parts[0], later.epochSecond.toString(), parts[2], parts[3]).joinToString("|")
    // Past the original expiry, well before the extended one: the clock alone would accept it.
    assert(!client.isSignedStateValid(extended, shop, now.plusSeconds(600)))
  }

  @Test
  fun `isSignedStateValid rejects a state whose nonce was replaced after signing`() {
    val parts = client.signedState(shop, now).split('|')
    val replaced = listOf(parts[0], parts[1], "another-nonce", parts[3]).joinToString("|")
    assert(!client.isSignedStateValid(replaced, shop, now))
  }

  @Test
  fun `isSignedStateValid rejects malformed state with three segments`() {
    assert(!client.isSignedStateValid("only|three|parts", shop, now))
  }

  @Test
  fun `isSignedStateValid rejects malformed state with five segments`() {
    assert(!client.isSignedStateValid("a|b|c|d|e", shop, now))
  }

  @Test
  fun `isSignedStateValid rejects empty state`() {
    assert(!client.isSignedStateValid("", shop, now))
  }

  @Test
  fun `isSignedStateValid rejects non-numeric expiry`() {
    val state = "${shop.normalizedShopifyHost}|notANumber|nonce|signature"
    assert(!client.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `signedState two consecutive signs produce different nonces`() {
    val a = client.signedState(shop, now)
    val b = client.signedState(shop, now)
    assert(a != b)
  }

  // ---------- exchangeCode, against a fake Shopify ----------

  @Test
  fun `exchangeCode posts the app credentials with the code and answers the token`() = withFakeShopify { server, service ->
    val result = service.exchangeCode(shop, "abc-code")

    assert(result.successValue().token == ShopifyAdminToken("shpat_fake_admin_token"))
    val sent = AppJson.parseToJsonElement(server.oauthCalls.single()).jsonObject
    assert(sent["client_id"]?.jsonPrimitive?.content == "client-id-123")
    assert(sent["client_secret"]?.jsonPrimitive?.content == "client-secret-xyz")
    assert(sent["code"]?.jsonPrimitive?.content == "abc-code")
  }

  /** Shopify answers the granted scopes comma-separated, and a merchant can grant fewer than were asked for. */
  @Test
  fun `exchangeCode answers the granted scope handles beside the token`() = withFakeShopify { server, service ->
    server.oauthAccessTokenResponse = """{"access_token":"shpat_fake_admin_token","scope":"read_products, write_orders,,write_fulfillments"}"""

    val result = service.exchangeCode(shop, "abc-code")

    val expected = ShopifyAccessGrant(ShopifyAdminToken("shpat_fake_admin_token"), listOf("read_products", "write_orders", "write_fulfillments"))
    assert(result == Success(expected))
  }

  @Test
  fun `exchangeCode answers CodeRejected with the status when Shopify refuses the code`() = withFakeShopify { server, service ->
    server.oauthStatus = HttpStatusCode.BadRequest
    server.oauthAccessTokenResponse = """{"error":"invalid_request","error_description":"code was already used"}"""

    val result = service.exchangeCode(shop, "used-code")

    // Decoding the error body as a token used to make this a network failure, which reads as a blip worth retrying.
    assert(result == Failure(OAuthError.CodeRejected(400)))
  }

  @Test
  fun `exchangeCode answers Transport when Shopify answers a server error`() = withFakeShopify { server, service ->
    server.oauthStatus = HttpStatusCode.ServiceUnavailable
    server.oauthAccessTokenResponse = "<html>maintenance</html>"

    val result = service.exchangeCode(shop, "abc-code")

    assert(result == Failure(OAuthError.Transport("Shopify answered HTTP 503")))
  }

  @Test
  fun `exchangeCode answers Transport when the token response is not JSON`() = withFakeShopify { server, service ->
    server.oauthAccessTokenResponse = "<html>maintenance</html>"

    val result = service.exchangeCode(shop, "abc-code")

    assert(result.failureReason() is OAuthError.Transport)
    assert("client-secret-xyz" !in result.failureReason().message)
  }

  /** A connection Shopify drops is worth sending the merchant back for: the next attempt may well get through. */
  @Test
  fun `exchangeCode answers Transport when the connection is dropped`() = runBlocking {
    FakeFlakyServer().use { flaky ->
      shopifyRewritingHttpClient(URI(flaky.baseUrl).port).use { shopifyClient ->
        val service = HttpShopifyOAuthService(shopifyClient, "client-id-123", ShopifyAppSecret("s"), "https://dss.example.com/oauth/callback")
        val result = service.exchangeCode(shop, "abc-code")
        assert(result.failureReason() is OAuthError.Transport)
      }
    }
  }

  /** Sending the merchant back to Shopify for a bug on our side would fail the same way again. */
  @Test
  fun `exchangeCode lets a failure that is not a transport failure propagate`() = runBlocking {
    throwingHttpClient(IllegalStateException("client misconfigured")).use { broken ->
      val service = HttpShopifyOAuthService(broken, "client-id-123", ShopifyAppSecret("s"), "https://dss.example.com/oauth/callback")
      val thrown = runCatching { service.exchangeCode(shop, "abc-code") }.exceptionOrNull()
      assert(thrown is IllegalStateException)
    }
  }

  /** The decoder quotes the body it could not read, and a token response carries the token. */
  @Test
  fun `exchangeCode keeps a token response it cannot read out of the error message`() = withFakeShopify { server, service ->
    server.oauthAccessTokenResponse = """{"access_token":"shpat_must_not_leak","scope":"""

    val result = service.exchangeCode(shop, "abc-code")

    assert(result.failureReason() is OAuthError.Transport)
    assert("shpat_must_not_leak" !in result.failureReason().message)
  }

  /** A fake Shopify serving the token exchange, reached through the shared helper so the service keeps its real URL. */
  private fun withFakeShopify(block: suspend (FakeShopifyGraphqlServer, ShopifyOAuthService) -> Unit) =
    withFakeShopifyServer { server, shopifyClient ->
      val service = HttpShopifyOAuthService(
        httpClient = shopifyClient,
        clientId = "client-id-123",
        clientSecret = ShopifyAppSecret("client-secret-xyz"),
        redirectUrl = "https://dss.example.com/oauth/callback",
      )
      runBlocking { block(server, service) }
    }
}

