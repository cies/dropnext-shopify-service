package dropnext.dss

import dropnext.dss.config.Config
import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.handler.MAX_CONCURRENT_MIRRORS
import dropnext.dss.handler.MonolithWebhookHandlers
import dropnext.dss.handler.OAuthHandlers
import dropnext.dss.handler.ShopifyWebhookHandlers
import dropnext.dss.handler.WEBHOOK_MIRROR_BUDGET
import dropnext.dss.handler.WEBHOOK_MONOLITH_MAX_RETRIES
import dropnext.dss.handler.WEBHOOK_WRITE_GRACE
import dropnext.dss.lib.ktor.createMonolithHttpClient
import dropnext.dss.lib.ktor.createSharedHttpClient
import dropnext.dss.lib.monolith.HttpMonolithService
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.oauth.HttpShopifyOAuthService
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.workflow.resolveShopTokenFromMonolith
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlinx.coroutines.sync.Semaphore


private val log = KotlinLogging.logger {}

/**
 * Every collaborator the running app needs, wired once.
 *
 * [dssModule] installs the handlers into a Ktor application and closes the graph when the application stops;
 * [main] and the tests both go through it, so the routing under test is the routing in production.
 * Tests assemble the same shape with fakes substituted via the optional [dssDependencies] parameters.
 */
class DssDependencies(
  val config: Config,
  private val httpClient: HttpClient,
  private val monolithHttpClient: HttpClient,
  private val webhookMonolithHttpClient: HttpClient,
  val diagnosticsHandlers: DiagnosticsHandlers,
  val oauthHandlers: OAuthHandlers,
  val shopifyWebhookHandlers: ShopifyWebhookHandlers,
  val monolithWebhookHandlers: MonolithWebhookHandlers,
) : AutoCloseable {
  /** Closes every HTTP client with [runCatching] so a single failure doesn't skip the others. */
  override fun close() {
    runCatching { httpClient.close() }
      .onFailure { log.warn(it) { "[shutdown] httpClient.close threw" } }
    runCatching { monolithHttpClient.close() }
      .onFailure { log.warn(it) { "[shutdown] monolithHttpClient.close threw" } }
    runCatching { webhookMonolithHttpClient.close() }
      .onFailure { log.warn(it) { "[shutdown] webhookMonolithHttpClient.close threw" } }
  }
}

/**
 * Builds the [DssDependencies] graph from [config]. Every collaborator has a sensible
 * production default; tests swap in fakes by overriding the corresponding parameter. Defaults
 * are evaluated lazily and can reference earlier parameters, so overriding [httpClient] (for
 * example, the rewriting client that pins Shopify calls to a fake server) flows through to the
 * [shopifyGraphqlServiceFactory] and [oauthClient] defaults automatically.
 *
 * Example test setup:
 * ```
 * val deps = dssDependencies(
 *   testConfig(),
 *   httpClient = rewritingClient,
 *   monolithService = FakeMonolithService(),
 *   shopTokens = InMemoryShopTokenStore(mapOf(shop to ShopifyAdminToken("shpat_test"))),
 * )
 * val handler = deps.shopifyWebhookHandlers
 * ```
 */
fun dssDependencies(
  config: Config,
  httpClient: HttpClient = createSharedHttpClient(),
  monolithHttpClient: HttpClient = createMonolithHttpClient(httpClient),
  monolithService: MonolithService = HttpMonolithService(
    httpClient = monolithHttpClient,
    baseUrl = config.monolithBaseUrl,
    apiPathPrefix = config.monolithApiPrefix,
    apiKey = config.dssToMonolithApiKey,
  ),
  /** The webhook handlers' way to the monolith: the same engine, the retries that fit a webhook's budget. */
  webhookMonolithHttpClient: HttpClient = createMonolithHttpClient(httpClient, maxRetries = WEBHOOK_MONOLITH_MAX_RETRIES),
  /**
   * The monolith as the webhook handlers see it. A fake substituted for [monolithService] serves the webhooks too, so
   * what a test records as sent does not depend on which route sent it; the production service gets a sibling over
   * [webhookMonolithHttpClient].
   */
  webhookMonolithService: MonolithService =
    if (monolithService is HttpMonolithService) {
      HttpMonolithService(
        httpClient = webhookMonolithHttpClient,
        baseUrl = config.monolithBaseUrl,
        apiPathPrefix = config.monolithApiPrefix,
        apiKey = config.dssToMonolithApiKey,
      )
    } else {
      monolithService
    },
  shopTokens: ShopTokenStore = InMemoryShopTokenStore(config.shopAccessTokens) { shop ->
    resolveShopTokenFromMonolith(monolithService, shop)
  },
  shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory = HttpShopifyGraphqlServiceFactory(
    httpClient = httpClient,
    tokens = shopTokens,
    apiVersion = Config.SHOPIFY_API_VERSION,
  ),
  oauthClient: ShopifyOAuthService = HttpShopifyOAuthService(
    httpClient = httpClient,
    clientId = config.appClientId,
    clientSecret = config.appClientSecret,
    scopes = config.scopes,
    redirectUrl = config.redirectUrl,
  ),
  shopifyHmacVerifierService: ShopifyHmacVerifierService = ShopifyHmacVerifierService(config.appClientSecret),
  webhookMirrorBudget: Duration = WEBHOOK_MIRROR_BUDGET,
  webhookWriteGrace: Duration = WEBHOOK_WRITE_GRACE,
  webhookMirrorSlots: Semaphore = Semaphore(MAX_CONCURRENT_MIRRORS),
): DssDependencies = DssDependencies(
  config = config,
  httpClient = httpClient,
  monolithHttpClient = monolithHttpClient,
  webhookMonolithHttpClient = webhookMonolithHttpClient,
  diagnosticsHandlers = DiagnosticsHandlers(config, shopifyGraphqlServiceFactory),
  oauthHandlers = OAuthHandlers(
    config.dssBaseUrl,
    oauthClient,
    shopifyGraphqlServiceFactory,
    monolithService,
    shopTokens,
    shopifyHmacVerifierService,
  ),
  shopifyWebhookHandlers = ShopifyWebhookHandlers(
    shopifyGraphqlServiceFactory,
    webhookMonolithService,
    shopifyHmacVerifierService,
    webhookMirrorBudget,
    webhookWriteGrace,
    webhookMirrorSlots,
  ),
  monolithWebhookHandlers = MonolithWebhookHandlers(
    shopifyGraphqlServiceFactory,
    monolithService,
    shopTokens,
  ),
)
