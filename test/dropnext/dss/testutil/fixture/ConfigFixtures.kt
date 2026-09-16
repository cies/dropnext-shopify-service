package dropnext.dss.testutil.fixture

import dropnext.dss.boot.config.Config
import dropnext.dss.boot.config.DssMode
import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopifyAppSecret


/**
 * The configuration every test starts from; override only the parameter the case is about.
 *
 * The two secrets default to the suite's shared ones ([TEST_APP_SECRET], [TEST_MONOLITH_TO_DSS_API_KEY]), so a test
 * that signs a webhook or sends a bearer does not have to configure the same value twice and cannot configure two
 * different ones by accident.
 */
fun testConfig(
  appClientSecret: String = TEST_APP_SECRET,
  monolithToDssApiKey: String = TEST_MONOLITH_TO_DSS_API_KEY,
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
