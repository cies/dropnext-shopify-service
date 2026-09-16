package dropnext.dss.testutil.fixture

import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.dssDependencies
import dropnext.dss.handler.MAX_CONCURRENT_MIRRORS
import dropnext.dss.handler.WEBHOOK_MIRROR_BUDGET
import dropnext.dss.handler.WEBHOOK_WRITE_GRACE
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fake.FakeShopifyOAuthService
import dropnext.dss.testutil.helper.testHttpClient
import io.ktor.client.HttpClient
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Semaphore


/**
 * The production dependency graph with the outside world faked out: `dssDependencies` as a test wants it.
 *
 * Seven test classes had each written their own `deps(...)`, of the same shape but with different parameter names and
 * different opinions about what "no service" means; a reader had to open each one before reading a single test. This
 * is that helper, once, and the defaults say what a test starts from: a monolith that accepts everything, an empty
 * token store, and a shop whose Admin token does not resolve.
 *
 * [shopify] is the service every shop gets; `null` — the default — is a shop without a resolvable Admin token, which
 * handlers answer as `DssError.MissingShopifyAdminToken`. [honorTokenStore] makes the factory read [shopTokens]
 * instead, as the production one does, so a test can prove a token was remembered before the service was asked for.
 */
internal fun testDependencies(
  config: Config = testConfig(),
  httpClient: HttpClient = testHttpClient(),
  monolith: MonolithService = FakeMonolithService(),
  shopify: ShopifyGraphqlService? = null,
  shopTokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
  honorTokenStore: Boolean = false,
  tokenSourceUnavailable: Boolean = false,
  shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(
    service = shopify,
    tokens = shopTokens.takeIf { honorTokenStore },
    tokenSourceUnavailable = tokenSourceUnavailable,
  ),
  oauthClient: ShopifyOAuthService = FakeShopifyOAuthService(),
  webhookMirrorBudget: Duration = WEBHOOK_MIRROR_BUDGET,
  webhookWriteGrace: Duration = WEBHOOK_WRITE_GRACE,
  webhookMirrorSlots: Semaphore = Semaphore(MAX_CONCURRENT_MIRRORS),
  webhookClock: Clock = Clock.systemUTC(),
  webhookTimeSource: TimeSource = TimeSource.Monotonic,
): DssDependencies = dssDependencies(
  config = config,
  httpClient = httpClient,
  monolithService = monolith,
  shopTokens = shopTokens,
  shopifyGraphqlServiceFactory = shopifyGraphqlServiceFactory,
  oauthClient = oauthClient,
  webhookMirrorBudget = webhookMirrorBudget,
  webhookWriteGrace = webhookWriteGrace,
  webhookMirrorSlots = webhookMirrorSlots,
  webhookClock = webhookClock,
  webhookTimeSource = webhookTimeSource,
)
