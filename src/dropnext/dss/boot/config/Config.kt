package dropnext.dss.boot.config

import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.path.Paths
import java.net.URI


/**
 * Every environment variable the service reads, read once in [from]. The README's variable table
 * documents this class; a variable not read here is not a variable.
 */
data class Config(
  val appClientId: String,
  val appClientSecret: ShopifyAppSecret,
  val dssBaseUrl: String,
  val oauthRedirectPath: String,
  val serverPort: Int,
  val monolithBaseUrl: String,
  val monolithApiPrefix: String?,
  val dssToMonolithApiKey: DssToMonolithApiKey?,
  val allowInsecureMonolithUrl: Boolean,
  val monolithToDssApiKey: MonolithToDssApiKey,
  val logflareSourceName: String?,
  val logflareApiKey: LogflareApiKey?,
  val logflareEndpoint: String?,
  val mode: DssMode,
  /** What `/health` and the startup log report: the image tag in a container, a marker outside one. */
  val versionTag: String,
) {
  /** Log shipping needs both halves; with either missing the service logs to stdout only. */
  val logflareEnabled: Boolean
    get() = !logflareSourceName.isNullOrBlank() && logflareApiKey != null

  val redirectUrl: String
    get() = dssBaseUrl.trimEnd('/') + oauthRedirectPath

  companion object {
    /** The process environment, with a local `.env` file layered over it when present. */
    fun fromEnv(dotEnv: Map<String, String> = emptyMap()): Config = from(System.getenv() + dotEnv)

    /**
     * Builds the configuration from [env] and fails loud on anything missing or still a placeholder:
     * a service that starts on a half-filled template is worse than one that does not.
     */
    fun from(env: Map<String, String>): Config {
      fun value(name: String): String? = normalizeQuoted(env[name])
      fun required(name: String): String = value(name) ?: error("Set env var $name.")

      val appClientId = required("SHOPIFY_APP_CLIENT_ID")
      val appClientSecret = required("SHOPIFY_APP_CLIENT_SECRET")
      val dssBaseUrl = required("DSS_BASE_URL")
      val monolithBaseUrl = required("MONOLITH_BASE_URL")
      val monolithToDssApiKey = required("MONOLITH_TO_DSS_API_KEY")

      if (isPlaceholder(appClientId)) {
        error("SHOPIFY_APP_CLIENT_ID is placeholder.")
      }
      if (isPlaceholder(appClientSecret)) {
        error("SHOPIFY_APP_CLIENT_SECRET is placeholder.")
      }
      if (isPlaceholder(dssBaseUrl) || dssBaseUrl.contains("example.com", ignoreCase = true)) {
        error("DSS_BASE_URL is placeholder.")
      }
      if (absoluteHttpUrlOrNull(dssBaseUrl)?.scheme?.equals("https", ignoreCase = true) != true) {
        error("DSS_BASE_URL must be an absolute https:// URL, was '$dssBaseUrl'.")
      }
      requireUsableBearerSecret("MONOLITH_TO_DSS_API_KEY", monolithToDssApiKey)
      // The length rule guards our own door; the monolith's is the monolith's to guard.
      if (monolithToDssApiKey.length < 32) {
        error("MONOLITH_TO_DSS_API_KEY must be at least 32 characters.")
      }
      val dssToMonolithApiKey = value("DSS_TO_MONOLITH_API_KEY")
      // Optional, but once set it is sent on every monolith call: a value the monolith cannot parse fails all of them.
      if (dssToMonolithApiKey != null) requireUsableBearerSecret("DSS_TO_MONOLITH_API_KEY", dssToMonolithApiKey)

      val mode = DssMode.parse(env["DSS_MODE"])
      val allowInsecureMonolithUrl = parseBool(env["DSS_ALLOW_INSECURE_MONOLITH"])
      // The flag is a local-development escape hatch; `PROD` is the default mode, so a deployment that
      // sets the flag by mistake fails to boot instead of talking to the monolith in the clear.
      if (allowInsecureMonolithUrl && mode.isProd) {
        error("DSS_ALLOW_INSECURE_MONOLITH=true is for local development only: set DSS_MODE=DEV or drop the flag.")
      }
      // A value without a scheme used to pass here and then fail on every monolith call instead.
      val monolithUrl = absoluteHttpUrlOrNull(monolithBaseUrl)
        ?: error("MONOLITH_BASE_URL must be an absolute URL such as https://monolith.example.org, was '$monolithBaseUrl'.")
      if (monolithUrl.scheme.equals("http", ignoreCase = true) && !allowInsecureMonolithUrl) {
        error("MONOLITH_BASE_URL must use https:// (or set DSS_ALLOW_INSECURE_MONOLITH=true with DSS_MODE=DEV for local dev).")
      }

      val oauthRedirectPath = value("OAUTH_REDIRECT_PATH") ?: Paths.defaultOAuthCallback
      // Appended to DSS_BASE_URL for the redirect URL Shopify must match exactly, and mounted as a route: anything but a
      // plain absolute path breaks one of the two, and neither says so at startup.
      if (!oauthRedirectPath.isPlainAbsolutePath()) {
        error("OAUTH_REDIRECT_PATH must be an absolute path such as /oauth/callback, was '$oauthRedirectPath'.")
      }

      val logflareEndpoint = value("LOGFLARE_ENDPOINT")
      // The log shipper builds its URLs from this on its own thread, where a malformed value stopped the shipping without a word.
      if (logflareEndpoint != null && absoluteHttpUrlOrNull(logflareEndpoint) == null) {
        error("LOGFLARE_ENDPOINT must be an absolute URL such as https://api.logflare.app, was '$logflareEndpoint'.")
      }

      return Config(
        appClientId = appClientId,
        appClientSecret = ShopifyAppSecret(appClientSecret),
        dssBaseUrl = dssBaseUrl.trimEnd('/'),
        oauthRedirectPath = oauthRedirectPath,
        serverPort = resolveServerPort(env["PORT"]),
        monolithBaseUrl = monolithBaseUrl,
        monolithApiPrefix = value("MONOLITH_API_PREFIX")?.trim { it == '/' }?.takeIf { it.isNotEmpty() },
        dssToMonolithApiKey = dssToMonolithApiKey?.let(::DssToMonolithApiKey),
        allowInsecureMonolithUrl = allowInsecureMonolithUrl,
        monolithToDssApiKey = MonolithToDssApiKey(monolithToDssApiKey),
        logflareSourceName = value("LOGFLARE_SOURCE_NAME"),
        logflareApiKey = value("LOGFLARE_API_KEY")?.let(::LogflareApiKey),
        logflareEndpoint = logflareEndpoint,
        mode = mode,
        // The Dockerfile bakes it in from the build argument `dnc` passes, so a container reports the tag ECR
        // knows its image by. Nothing sets it for a local run, and the fallback says so.
        versionTag = value("VERSION_TAG") ?: "local-dev",
      )
    }

    /**
     * The Admin API version the Graphql client is generated for. Not a setting: it must agree with the introspection
     * endpoint in `build.gradle.kts`, and the bump procedure is in `.claude/rules/graphql.md`.
     */
    const val SHOPIFY_API_VERSION = "2026-04"

    /**
     * PaaS UIs sometimes define `PORT=` (empty string), wiping Docker `ENV PORT=9999`;
     * that makes Ktor bind 8080 while Traefik/nginx still proxies 9999 -> 502 Bad Gateway.
     * Empty -> use prod image default 9999;
     * unset PORT -> 8080 for local `./gradlew run` without env.
     */
    private fun resolveServerPort(raw: String?): Int {
      val parsed = normalizeQuoted(raw)?.toIntOrNull()?.takeIf { it in 1..65535 }
      return when {
        parsed != null -> parsed
        raw == null -> 8080
        raw.isBlank() -> 9999
        else -> 8080
      }
    }
  }
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)

/** [raw] as an `http` or `https` URL with a host, or `null` for anything less: a bare host, a path, a typo in the scheme. */
private fun absoluteHttpUrlOrNull(raw: String): URI? {
  val uri = runCatching { URI(raw) }.getOrNull() ?: return null
  val scheme = uri.scheme ?: return null
  if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
  return uri.takeUnless { it.host.isNullOrBlank() }
}

/**
 * The two bearer secrets are read under the same rules on both sides (see `domain/Secrets.kt`): not a value the
 * template left behind, and parseable on the wire. A server parses the `Authorization` header before any provider
 * compares the token, so a character outside RFC 6750's alphabet is a `400` on every call, and nothing at startup
 * would have said so.
 */
private fun requireUsableBearerSecret(name: String, value: String) {
  if (isPlaceholder(value) || value.contains("change_this", ignoreCase = true)) {
    error("$name is still a placeholder value.")
  }
  if (!value.isBearerToken()) {
    error("$name may only contain letters, digits and -._~+/ (with trailing = allowed).")
  }
}

/** RFC 6750's `b64token`: what a `Bearer` header may carry, and all a Ktor server will parse. */
private fun String.isBearerToken(): Boolean = Regex("[A-Za-z0-9\\-._~+/]+=*").matches(this)

/** `/` alone is refused too: it is the index route. */
private fun String.isPlainAbsolutePath(): Boolean =
  length > 1 && startsWith('/') && none { it.isWhitespace() || it == '?' || it == '#' }

/**
 * An env value as a dotenv file or a PaaS dashboard may have left it:
 * trimmed and stripped of one or more layers of wrapping ASCII quotes.
 *
 * `null` when nothing is left.
 */
internal fun normalizeQuoted(raw: String?): String? {
  if (raw == null) return null
  var s = raw.trim()
  while (s.length >= 2) {
    val inner =
      when {
        s.startsWith('"') && s.endsWith('"') -> s.substring(1, s.length - 1).trim()
        s.startsWith('\'') && s.endsWith('\'') -> s.substring(1, s.length - 1).trim()
        else -> break
      }
    if (inner.isEmpty()) return null
    s = inner
  }
  return s.takeUnless { it.isEmpty() }
}

/** `true` only when the value resolves (after quote-stripping) to `true`, case-insensitive. */
internal fun parseBool(raw: String?): Boolean =
  normalizeQuoted(raw)?.equals("true", ignoreCase = true) == true
