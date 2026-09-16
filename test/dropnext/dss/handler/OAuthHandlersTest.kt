package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.oauth.HttpShopifyOAuthService
import dropnext.dss.lib.shopify.oauth.OAuthError
import dropnext.dss.lib.shopify.oauth.ShopifyAccessGrant
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyOAuthService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.CANONICAL_ACME_SHOP
import dropnext.dss.testutil.fixture.OTHER_SHOP
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.hexHmacSha256
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.withDssApp
import dropnext.dss.testutil.helper.withFakeShopifyServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The install and callback routes through the production module. The callback's query-string HMAC is
 * signed here with the app secret, as Shopify would. The OAuth service is the in-memory
 * [FakeShopifyOAuthService] except where the case is about the code exchange itself, which runs over
 * real HTTP against [FakeShopifyGraphqlServer] because `HttpShopifyOAuthService` speaks to Shopify
 * directly rather than through `ShopifyGraphqlService`.
 */
class OAuthHandlersTest {

  @Test
  fun `install returns 400 when shop query param is missing`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.install)
    assert(r.status == HttpStatusCode.BadRequest)
    // A merchant's browser reads this, so it is plain text and not the JSON error of the API routes.
    assert(r.contentType()?.withoutParameters() == ContentType.Text.Plain)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `install returns 400 when shop is malformed`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.install}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  /** The real service builds the URL: the redirect target is what Shopify has to accept. */
  @Test
  fun `install redirects to Shopify authorize url for valid shop`() {
    val config = testConfig()
    val httpClient = testHttpClient()
    withDssApp(deps(config, oauth = httpOAuthService(httpClient, config), httpClient = httpClient)) { client ->
      val r = client.get("${Paths.install}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.Found)
      val location = r.headers["Location"]
      assert(location != null && location.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
      assert("client_id=client-id-test" in location!!)
    }
  }

  /** The callback is a merchant's browser page too; only its path in `plainTextErrorPaths` keeps this out of the JSON shape. */
  @Test
  fun `oauth callback returns 400 when hmac is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.contentType()?.withoutParameters() == ContentType.Text.Plain)
    assert(r.bodyAsText() == "Missing hmac")
  }

  @Test
  fun `oauth callback returns 400 when shop is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is invalid`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=!bad!&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when state is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&code=c")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing state" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when code is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing code" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 403 when hmac is wrong`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=0000&shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.Forbidden)
    assert("Invalid HMAC" in r.bodyAsText())
  }

  /**
   * Over real HTTP: the one case that proves the exchange request Shopify receives. What the install then sends the
   * monolith is `InstallShopTest`'s.
   */
  @Test
  fun `oauth callback happy path exchanges the code, caches the token and renders the install page`() {
    val tokens = InMemoryShopTokenStore()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = ACME_SHOP))
    }
    withFakeShopifyServer { oauthServer, rewritingClient ->
      val config = testConfig()
      val oauth = httpOAuthService(rewritingClient, config)
      withDssApp(deps(config, oauth, rewritingClient, tokens = tokens, shopify = shopify)) { client ->
        val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP)))
        assert(r.status == HttpStatusCode.OK)
        val page = r.bodyAsText()
        assert("App installed" in page)
        // Shopify answers the granted scopes with the token; the fake grants what the install asks for.
        assert("All ${ShopifyAccessScope.entries.size} required access scopes granted." in page)
        assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_fake_admin_token"))
        assert("\"code\":\"abc-code\"" in oauthServer.oauthCalls.single())
        assert(shopify.shopIdentityCalls.size == 1)
        // Each known topic was registered once via the workflow.
        assert(shopify.registerWebhookCalls.size == ShopifyWebhookTopic.known.size)
      }
    }
  }

  /**
   * A merchant can approve less than the install asked for: the install still completes, and the report reaches the
   * page. Which scope the report names, and how the page lists it, are `InstallShopTest`'s and
   * `RenderOAuthInstallPageTest`'s.
   */
  @Test
  fun `oauth callback with a partial grant still caches the token and reports the gap on the install page`() {
    val tokens = InMemoryShopTokenStore()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP)
    val oauth = FakeShopifyOAuthService().apply {
      val granted = ShopifyAccessScope.entries.map { it.handle } - "write_fulfillments"
      exchangeCodeResult = Success(ShopifyAccessGrant(ShopifyAdminToken("shpat_fake_admin_token"), granted))
    }
    withDssApp(deps(oauth = oauth, tokens = tokens, shopify = shopify)) { client ->
      val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP)))
      assert(r.status == HttpStatusCode.OK)
      val page = r.bodyAsText()
      assert("This shop did not grant every access scope the service needs" in page)
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_fake_admin_token"))
    }
  }

  // ---------- the signed state: what stands between the callback and a CSRF install ----------

  @Test
  fun `oauth callback returns 403 when the state was signed for another shop`() {
    val monolith = FakeMonolithService()
    val oauth = FakeShopifyOAuthService()
    val foreignState = oauth.signedState(OTHER_SHOP)
    withDssApp(deps(oauth = oauth, monolith = monolith)) { client ->
      val r = client.get(signedCallbackUrl(foreignState))
      assert(r.status == HttpStatusCode.Forbidden)
      assert(r.bodyAsText() == "Invalid or expired state")
      assert(oauth.exchangeCodeCalls.isEmpty())
      assert(monolith.putStoreApiKeyCalls.isEmpty())
    }
  }

  /** The HMAC covers the query, so a tampered state needs a matching HMAC to get this far; the state's own signature is the last line. */
  @Test
  fun `oauth callback returns 403 when the state does not verify`() {
    val oauth = FakeShopifyOAuthService()
    withDssApp(deps(oauth = oauth)) { client ->
      val r = client.get(signedCallbackUrl("not-the-signed-state"))
      assert(r.status == HttpStatusCode.Forbidden)
      assert(r.bodyAsText() == "Invalid or expired state")
      assert(oauth.exchangeCodeCalls.isEmpty())
    }
  }

  // ---------- after the checks: the exchange and the service ----------

  @Test
  fun `oauth callback returns 502 and caches nothing when Shopify does not answer the code exchange`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val oauth = FakeShopifyOAuthService().apply { exchangeCodeResult = Failure(OAuthError.Transport("connection reset")) }
    withDssApp(deps(oauth = oauth, monolith = monolith, tokens = tokens)) { client ->
      val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP), code = "abc-code"))
      assert(r.status == HttpStatusCode.BadGateway)
      assert(r.bodyAsText() == "OAuth failed: could not exchange authorization code")
      assert(oauth.exchangeCodeCalls.single().code == "abc-code")
      assert(tokens.cached(ACME_SHOP) == null)
      assert(monolith.putStoreApiKeyCalls.isEmpty())
    }
  }

  /** Over real HTTP: a used code is Shopify's `400`, and the merchant is told to start over rather than that something is down. */
  @Test
  fun `oauth callback returns 400 telling the merchant to restart when Shopify refuses the code`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    withFakeShopifyServer { oauthServer, rewritingClient ->
      oauthServer.oauthStatus = HttpStatusCode.BadRequest
      oauthServer.oauthAccessTokenResponse =
        """{"error":"invalid_request","error_description":"The authorization code was not found or was already used"}"""
      val config = testConfig()
      val oauth = httpOAuthService(rewritingClient, config)
      withDssApp(deps(config, oauth, rewritingClient, monolith, tokens)) { client ->
        val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP), code = "used-code"))
        assert(r.status == HttpStatusCode.BadRequest)
        assert(r.bodyAsText() == "OAuth failed: Shopify refused the authorization code (HTTP 400); start the install again")
        assert(oauthServer.oauthCalls.size == 1)
        assert(tokens.cached(ACME_SHOP) == null)
        assert(monolith.putStoreApiKeyCalls.isEmpty())
      }
    }
  }

  /** The default graph resolves no service for any shop, which after a successful exchange is the one thing left to fail. */
  @Test
  fun `oauth callback returns 502 when no Shopify service can be built for the shop`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val oauth = FakeShopifyOAuthService()
    withDssApp(deps(oauth = oauth, monolith = monolith, tokens = tokens)) { client ->
      val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP)))
      assert(r.status == HttpStatusCode.BadGateway)
      assert("could not build a Shopify service" in r.bodyAsText())
      // The exchange did succeed, so the token is kept: a retry of the callback would not get a second one.
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_fake_admin_token"))
      assert(monolith.putStoreApiKeyCalls.isEmpty())
    }
  }

  /** Shopify may redirect for one host while the shop's canonical `myshopify.com` host is another; a webhook arrives under the latter. */
  @Test
  fun `oauth callback remembers the token under the callback shop and under the canonical domain`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = CANONICAL_ACME_SHOP))
    }
    val oauth = FakeShopifyOAuthService()
    withDssApp(deps(oauth = oauth, monolith = monolith, tokens = tokens, shopify = shopify)) { client ->
      val r = client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP)))
      assert(r.status == HttpStatusCode.OK)
      assert("Shop: ${CANONICAL_ACME_SHOP.normalizedShopifyHost}" in r.bodyAsText())
      assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_fake_admin_token"))
      assert(tokens.cached(CANONICAL_ACME_SHOP) == ShopifyAdminToken("shpat_fake_admin_token"))
      assert(monolith.putStoreApiKeyCalls.single().shopifySubdomain == "acme-canonical")
    }
  }

  // ---------- what the callback must not log ----------

  /** The callback handles a code, an HMAC, a state and a fresh token in one request: the path most worth checking for a leak. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the oauth callback logs neither the code, the hmac, the state nor the token`() {
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = ACME_SHOP))
    }
    val oauth = FakeShopifyOAuthService()
    val state = oauth.signedState(ACME_SHOP)
    val code = "code-that-must-not-be-logged"
    val lines = capturingLogs {
      withDssApp(deps(oauth = oauth, shopify = shopify)) { client ->
        assert(client.get(signedCallbackUrl(state, code)).status == HttpStatusCode.OK)
      }
    }
    val logged = lines.joinToString("\n")
    assert(lines.isNotEmpty())
    assert(code !in logged)
    assert(callbackHmac(code, state) !in logged)
    assert(state !in logged)
    assert("shpat_fake_admin_token" !in logged)
    assert(TEST_APP_SECRET !in logged)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a failed code exchange logs neither the code nor the app secret`() {
    val oauth = FakeShopifyOAuthService().apply { exchangeCodeResult = Failure(OAuthError.CodeRejected(400)) }
    val code = "code-that-must-not-be-logged"
    val lines = capturingLogs {
      withDssApp(deps(oauth = oauth)) { client ->
        assert(client.get(signedCallbackUrl(oauth.signedState(ACME_SHOP), code)).status == HttpStatusCode.BadRequest)
      }
    }
    val logged = lines.joinToString("\n")
    assert(lines.any { "OAuth code exchange failed" in it })
    assert(code !in logged)
    assert(TEST_APP_SECRET !in logged)
  }

  // ---------- helpers ----------

  /** Shopify's query-string HMAC: lowercase hex over the parameters minus the hmac itself, sorted by key. */
  private fun callbackHmac(code: String, state: String): String =
    hexHmacSha256(TEST_APP_SECRET, "code=$code&shop=${ACME_SHOP.normalizedShopifyHost}&state=$state")

  /** The callback URL Shopify would send for [state] and [code], correctly signed for `acme`. */
  private fun signedCallbackUrl(state: String, code: String = "abc-code"): String {
    val query = Parameters.build {
      append("shop", ACME_SHOP.normalizedShopifyHost)
      append("code", code)
      append("state", state)
      append("hmac", callbackHmac(code, state))
    }
    return "${Paths.defaultOAuthCallback}?${query.formUrlEncode()}"
  }

  /**
   * The graph with the fakes. The factory is given the token store, so a service exists only once the
   * handler has remembered the token: the callback's "remember, then resolve" order is what the happy
   * path proves.
   */
  private fun deps(
    config: Config = testConfig(),
    oauth: ShopifyOAuthService = FakeShopifyOAuthService(),
    httpClient: HttpClient = testHttpClient(),
    monolith: FakeMonolithService = FakeMonolithService(),
    tokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
    shopify: FakeShopifyGraphqlService? = null,
  ): DssDependencies = testDependencies(
    config = config,
    httpClient = httpClient,
    monolith = monolith,
    shopify = shopify,
    shopTokens = tokens,
    honorTokenStore = true,
    oauthClient = oauth,
  )

  /** The real service, for the cases about the redirect URL and the exchange over the wire. */
  private fun httpOAuthService(client: HttpClient, config: Config): HttpShopifyOAuthService =
    HttpShopifyOAuthService(
      httpClient = client,
      clientId = config.appClientId,
      clientSecret = config.appClientSecret,
      redirectUrl = config.redirectUrl,
    )
}
