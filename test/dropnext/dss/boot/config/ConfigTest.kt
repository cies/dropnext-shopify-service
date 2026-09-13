package dropnext.dss.boot.config

import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import kotlin.test.Test


/** Every required variable with a value that passes the placeholder checks; tests override one key at a time. */
private fun requiredEnv(): Map<String, String> = mapOf(
  "SHOPIFY_APP_CLIENT_ID" to "good-id",
  "SHOPIFY_APP_CLIENT_SECRET" to "good-secret",
  "SHOPIFY_SCOPES" to "read_orders",
  "DSS_BASE_URL" to "https://dss.example.org/",
  "MONOLITH_BASE_URL" to "https://monolith.example.org",
  "MONOLITH_TO_DSS_API_KEY" to "x".repeat(32),
)

class ConfigTest {

  @Test
  fun `reads the required variables and applies the defaults`() {
    val config = Config.from(requiredEnv())
    assert(config.appClientId == "good-id")
    assert(config.scopes == "read_orders")
    assert(config.oauthRedirectPath == "/oauth/callback")
    assert(config.serverPort == 8080)
    assert(config.monolithApiPrefix == null)
    assert(config.dssToMonolithApiKey == null)
    assert(!config.allowInsecureMonolithUrl)
    assert(config.shopAccessTokens.isEmpty())
    assert(!config.logflareEnabled)
    assert(config.mode == DssMode.PROD)
  }

  @Test
  fun `log shipping needs both the source name and the key`() {
    val neither = Config.from(requiredEnv())
    val nameOnly = Config.from(requiredEnv() + ("LOGFLARE_SOURCE_NAME" to "dropnext.dss"))
    val keyOnly = Config.from(requiredEnv() + ("LOGFLARE_API_KEY" to "logflare-secret"))
    val both = Config.from(
      requiredEnv() + mapOf("LOGFLARE_SOURCE_NAME" to "dropnext.dss", "LOGFLARE_API_KEY" to "logflare-secret"),
    )

    // Half-configured shipping would attach an appender that can never resolve a source token.
    assert(!neither.logflareEnabled)
    assert(!nameOnly.logflareEnabled)
    assert(!keyOnly.logflareEnabled)
    assert(both.logflareEnabled)
  }

  @Test
  fun `the logflare key is wrapped and never prints`() {
    val config = Config.from(requiredEnv() + ("LOGFLARE_API_KEY" to "logflare-secret"))
    assert(config.logflareApiKey == LogflareApiKey("logflare-secret"))
    assert("logflare-secret" !in config.toString())
  }

  @Test
  fun `the logflare endpoint is optional, for the local stub`() {
    assert(Config.from(requiredEnv()).logflareEndpoint == null)
    val overridden = Config.from(requiredEnv() + ("LOGFLARE_ENDPOINT" to "http://127.0.0.1:54327"))
    assert(overridden.logflareEndpoint == "http://127.0.0.1:54327")
  }

  /** The shipper builds its URLs from the endpoint on its own thread, where a malformed one used to stop the shipping silently. */
  @Test
  fun `a logflare endpoint that is not an absolute url is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("LOGFLARE_ENDPOINT" to "api.logflare.app")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
    assert("LOGFLARE_ENDPOINT" in failure!!.message.orEmpty())
  }

  /** `PROD` unless asked for, because `DEV` is the chattier mode; the spelling is forgiven, a typo is not. */
  @Test
  fun `the mode defaults to PROD and is parsed case-insensitively`() {
    assert(Config.from(requiredEnv()).mode == DssMode.PROD)
    assert(Config.from(requiredEnv() + ("DSS_MODE" to "dev")).mode == DssMode.DEV)
    assert(Config.from(requiredEnv() + ("DSS_MODE" to "\"PROD\"")).mode == DssMode.PROD)
    val typo = runCatching { Config.from(requiredEnv() + ("DSS_MODE" to "development")) }.exceptionOrNull()
    assert(typo is IllegalStateException)
    assert("DSS_MODE" in typo!!.message.orEmpty())
  }

  @Test
  fun `secrets are wrapped and never print`() {
    val config = Config.from(requiredEnv() + ("DSS_TO_MONOLITH_API_KEY" to "monolith-secret"))
    assert(config.appClientSecret.value == "good-secret")
    assert(config.monolithToDssApiKey == MonolithToDssApiKey("x".repeat(32)))
    assert(config.dssToMonolithApiKey == DssToMonolithApiKey("monolith-secret"))
    val printed = config.toString()
    assert("good-secret" !in printed)
    assert("monolith-secret" !in printed)
    assert("x".repeat(32) !in printed)
  }

  @Test
  fun `redirectUrl trims trailing slash from dssBaseUrl`() {
    val config = Config.from(requiredEnv())
    assert(config.redirectUrl == "https://dss.example.org/oauth/callback")
  }

  /** The legacy `SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET` names used to be accepted as fallbacks; they no longer are. */
  @Test
  fun `the app credentials are read under their current names only`() {
    val env = requiredEnv() - "SHOPIFY_APP_CLIENT_ID" - "SHOPIFY_APP_CLIENT_SECRET" +
      mapOf("SHOPIFY_API_KEY" to "legacy-id", "SHOPIFY_API_SECRET" to "legacy-secret")
    val failure = runCatching { Config.from(env) }.exceptionOrNull()
    assert(failure is IllegalStateException)
    assert("SHOPIFY_APP_CLIENT_ID" in failure!!.message.orEmpty())
  }

  @Test
  fun `quoted values are unwrapped`() {
    val config = Config.from(requiredEnv() + ("SHOPIFY_SCOPES" to "\"read_orders,write_fulfillments\""))
    assert(config.scopes == "read_orders,write_fulfillments")
  }

  // ---------- validation ----------

  @Test
  fun `a missing required variable fails loud`() {
    val failure = runCatching { Config.from(requiredEnv() - "MONOLITH_BASE_URL") }.exceptionOrNull()
    assert(failure is IllegalStateException)
    assert("MONOLITH_BASE_URL" in failure!!.message!!)
  }

  @Test
  fun `a placeholder client id is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("SHOPIFY_APP_CLIENT_ID" to "your_client_id")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
  }

  @Test
  fun `a placeholder MONOLITH_TO_DSS_API_KEY is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("MONOLITH_TO_DSS_API_KEY" to "change_this_to_a_long_random_secret")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
  }

  @Test
  fun `a short MONOLITH_TO_DSS_API_KEY is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("MONOLITH_TO_DSS_API_KEY" to "short")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
  }

  /** Ktor parses the header before the token is compared, so a value it cannot parse fails every call with a `400`. */
  @Test
  fun `a MONOLITH_TO_DSS_API_KEY outside the bearer token alphabet is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("MONOLITH_TO_DSS_API_KEY" to "x".repeat(30) + "%(")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
    assert("MONOLITH_TO_DSS_API_KEY" in failure!!.message.orEmpty())
    assert(Config.from(requiredEnv() + ("MONOLITH_TO_DSS_API_KEY" to "a-b.c_d~e+f/" + "0".repeat(20) + "==")).monolithToDssApiKey.value.endsWith("=="))
  }

  /** The same value is read by the monolith under the same name, so the same checks apply; it is optional, so only when set. */
  @Test
  fun `a DSS_TO_MONOLITH_API_KEY that is a placeholder or outside the bearer token alphabet is rejected`() {
    val placeholder = runCatching { Config.from(requiredEnv() + ("DSS_TO_MONOLITH_API_KEY" to "your_monolith_key")) }.exceptionOrNull()
    assert(placeholder is IllegalStateException)
    assert("DSS_TO_MONOLITH_API_KEY" in placeholder!!.message.orEmpty())
    val unparseable = runCatching { Config.from(requiredEnv() + ("DSS_TO_MONOLITH_API_KEY" to "key with spaces")) }.exceptionOrNull()
    assert(unparseable is IllegalStateException)
    assert("DSS_TO_MONOLITH_API_KEY" in unparseable!!.message.orEmpty())
    assert(Config.from(requiredEnv() + ("DSS_TO_MONOLITH_API_KEY" to "short-but-fine")).dssToMonolithApiKey == DssToMonolithApiKey("short-but-fine"))
  }

  @Test
  fun `a plain-http DSS_BASE_URL is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("DSS_BASE_URL" to "http://dss.example.org")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
  }

  @Test
  fun `a DSS_BASE_URL without a host is rejected`() {
    val failure = runCatching { Config.from(requiredEnv() + ("DSS_BASE_URL" to "https://")) }.exceptionOrNull()
    assert(failure is IllegalStateException)
  }

  /** Appended to the base URL for Shopify's redirect and mounted as a route: anything but a plain absolute path breaks one of them. */
  @Test
  fun `an oauth redirect path that is not a plain absolute path is rejected`() {
    listOf("oauth/callback", "/", "/oauth/callback?x=1", "/oauth callback").forEach { path ->
      val failure = runCatching { Config.from(requiredEnv() + ("OAUTH_REDIRECT_PATH" to path)) }.exceptionOrNull()
      assert(failure is IllegalStateException)
      assert("OAUTH_REDIRECT_PATH" in failure!!.message.orEmpty())
    }
    assert(Config.from(requiredEnv() + ("OAUTH_REDIRECT_PATH" to "/shopify/callback")).oauthRedirectPath == "/shopify/callback")
  }

  @Test
  fun `a plain-http monolith url is rejected unless explicitly allowed`() {
    val insecure = requiredEnv() + ("MONOLITH_BASE_URL" to "http://localhost:8080")
    assert(runCatching { Config.from(insecure) }.exceptionOrNull() is IllegalStateException)
    val allowed = Config.from(insecure + ("DSS_ALLOW_INSECURE_MONOLITH" to "true") + ("DSS_MODE" to "DEV"))
    assert(allowed.monolithBaseUrl == "http://localhost:8080")
    assert(allowed.allowInsecureMonolithUrl)
  }

  /** A value without a scheme used to pass and then fail on every monolith call. */
  @Test
  fun `a monolith url that is not an absolute http url is rejected`() {
    listOf("staging.dropnext.com/api/shopify-service/v1", "ftp://monolith.example.org", "https://").forEach { url ->
      val failure = runCatching { Config.from(requiredEnv() + ("MONOLITH_BASE_URL" to url)) }.exceptionOrNull()
      assert(failure is IllegalStateException)
      assert("MONOLITH_BASE_URL" in failure!!.message.orEmpty())
    }
  }

  /** The flag is a local-development escape hatch; `PROD` is the default, so a deployment that sets it by mistake does not boot. */
  @Test
  fun `the insecure-monolith flag is refused unless the mode is DEV`() {
    val flagged = requiredEnv() + ("DSS_ALLOW_INSECURE_MONOLITH" to "true")
    val failure = runCatching { Config.from(flagged) }.exceptionOrNull()
    assert(failure is IllegalStateException)
    assert("DSS_ALLOW_INSECURE_MONOLITH" in failure!!.message.orEmpty())
    assert(runCatching { Config.from(flagged + ("DSS_MODE" to "PROD")) }.exceptionOrNull() is IllegalStateException)
    assert(Config.from(flagged + ("DSS_MODE" to "DEV")).allowInsecureMonolithUrl)
  }

  // ---------- PORT ----------

  @Test
  fun `parsed valid port wins`() {
    assert(Config.from(requiredEnv() + ("PORT" to "9999")).serverPort == 9999)
  }

  @Test
  fun `PORT unset returns 8080`() {
    assert(Config.from(requiredEnv()).serverPort == 8080)
  }

  @Test
  fun `PORT set but blank returns 9999`() {
    assert(Config.from(requiredEnv() + ("PORT" to "")).serverPort == 9999)
    assert(Config.from(requiredEnv() + ("PORT" to "   ")).serverPort == 9999)
  }

  @Test
  fun `PORT set but unparseable returns 8080`() {
    assert(Config.from(requiredEnv() + ("PORT" to "not-a-number")).serverPort == 8080)
  }

  // ---------- monolith prefix and token map ----------

  @Test
  fun `monolith api prefix is trimmed of slashes and blank means none`() {
    assert(Config.from(requiredEnv() + ("MONOLITH_API_PREFIX" to "/api/v1/")).monolithApiPrefix == "api/v1")
    assert(Config.from(requiredEnv() + ("MONOLITH_API_PREFIX" to "/")).monolithApiPrefix == null)
  }

  @Test
  fun `shop access tokens are parsed as canonical domains, and a trailing comma is not an entry`() {
    val env = requiredEnv() + ("DSS_SHOP_ACCESS_TOKENS" to "Acme.myshopify.com|shpat_a, other|shpat_b,")
    val tokens = Config.from(env).shopAccessTokens
    assert(tokens.size == 2)
    assert(tokens[ShopDomain.parse("acme.myshopify.com")!!] == ShopifyAdminToken("shpat_a"))
    assert(tokens[ShopDomain.parse("other.myshopify.com")!!] == ShopifyAdminToken("shpat_b"))
  }

  /** A skipped entry left that shop's webhooks unanswered with no line saying why; the message names no half of it. */
  @Test
  fun `a shop access token entry that is not a shop and token pair refuses to boot without printing it`() {
    listOf("broken", "|shpat_secret", "acme.myshopify.com|", "shpat_secret|acme.myshopify.com").forEach { entry ->
      val failure = runCatching { Config.from(requiredEnv() + ("DSS_SHOP_ACCESS_TOKENS" to "good|shpat_a, $entry")) }.exceptionOrNull()
      assert(failure is IllegalStateException)
      assert("DSS_SHOP_ACCESS_TOKENS: entry 2" in failure!!.message.orEmpty())
      assert("shpat_secret" !in failure.message.orEmpty())
    }
  }

  // ---------- the value normalisation every variable goes through ----------

  @Test
  fun `null input returns null`() {
    assert(normalizeQuoted(null) == null)
  }

  @Test
  fun `empty and whitespace inputs return null`() {
    assert(normalizeQuoted("") == null)
    assert(normalizeQuoted("    ") == null)
  }

  @Test
  fun `plain value is trimmed`() {
    assert(normalizeQuoted("  hello  ") == "hello")
  }

  @Test
  fun `strips wrapping double quotes`() {
    assert(normalizeQuoted("\"hello\"") == "hello")
  }

  @Test
  fun `strips wrapping single quotes`() {
    assert(normalizeQuoted("'hello'") == "hello")
  }

  @Test
  fun `quotes containing only whitespace return null`() {
    assert(normalizeQuoted("\"   \"") == null)
    assert(normalizeQuoted("'  '") == null)
  }

  @Test
  fun `strips multiple levels of nested quotes`() {
    assert(normalizeQuoted("\"\"x\"\"") == "x")
    assert(normalizeQuoted("'\"x\"'") == "x")
  }

  @Test
  fun `preserves inner whitespace in unquoted value`() {
    assert(normalizeQuoted("hello world") == "hello world")
  }

  @Test
  fun `preserves inner quotes when only one side is quoted`() {
    assert(normalizeQuoted("\"hello") == "\"hello")
    assert(normalizeQuoted("hello\"") == "hello\"")
  }

  @Test
  fun `parseBool true is case-insensitive`() {
    assert(parseBool("true"))
    assert(parseBool("TRUE"))
    assert(parseBool("True"))
  }

  @Test
  fun `parseBool strips wrapping quotes and whitespace`() {
    assert(parseBool("  true  "))
    assert(parseBool("\"true\""))
    assert(parseBool("' true '"))
  }

  @Test
  fun `parseBool returns false for null empty and non-true values`() {
    assert(!parseBool(null))
    assert(!parseBool(""))
    assert(!parseBool("   "))
    assert(!parseBool("false"))
    assert(!parseBool("1"))
    assert(!parseBool("yes"))
  }
}
