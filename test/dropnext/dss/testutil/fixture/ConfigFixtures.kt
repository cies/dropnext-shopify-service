package dropnext.dss.testutil.fixture

import dropnext.dss.boot.config.Config
import dropnext.dss.boot.config.DssMode
import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopifyAppSecret


fun testConfig(
  appClientSecret: String = "test-secret",
  monolithToDssApiKey: String = "x".repeat(32),
  monolithBaseUrl: String = "https://monolith.test",
  dssToMonolithApiKey: String? = null,
  mode: DssMode = DssMode.PROD,
): Config =
  Config(
    appClientId = "client-id-test",
    appClientSecret = ShopifyAppSecret(appClientSecret),
    dssBaseUrl = "https://dss.test",
    oauthRedirectPath = "/oauth/callback",
    serverPort = 8080,
    monolithBaseUrl = monolithBaseUrl,
    monolithApiPrefix = null,
    dssToMonolithApiKey = dssToMonolithApiKey?.let(::DssToMonolithApiKey),
    allowInsecureMonolithUrl = true,
    monolithToDssApiKey = MonolithToDssApiKey(monolithToDssApiKey),
    // Log shipping stays off in tests: an appender would try to reach Logflare from the flush thread.
    logflareSourceName = null,
    logflareApiKey = null,
    logflareEndpoint = null,
    mode = mode,
    versionTag = "test-version",
  )
