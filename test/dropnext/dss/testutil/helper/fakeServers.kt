package dropnext.dss.testutil.helper

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.boot.config.Config
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyQueryCostReporter
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_ADMIN_TOKEN
import io.ktor.client.HttpClient
import java.net.URI


/**
 * A [FakeShopifyGraphqlServer] and a client that rewrites every `*.myshopify.com` request onto it, both stopped when
 * [block] returns. Production code keeps its real Shopify URLs, so what a test exercises is the URL the service builds.
 *
 * One place rather than a start/stop pair per class: five classes had grown their own, two of them starting and
 * stopping a server for every test method, and each spelled the teardown slightly differently.
 */
internal fun <T> withFakeShopifyServer(block: (FakeShopifyGraphqlServer, HttpClient) -> T): T {
  val server = FakeShopifyGraphqlServer()
  val shopifyClient = shopifyRewritingHttpClient(server.start())
  try {
    return block(server, shopifyClient)
  } finally {
    shopifyClient.close()
    server.stop()
  }
}

/**
 * The production Graphql service pointed at whatever [shopifyClient] reaches. Built with the shop's real Admin URL,
 * as production builds it, so a test of the URL shape sees the URL production would have sent.
 */
internal fun shopifyServiceOn(
  shopifyClient: HttpClient,
  shop: ShopDomain = ACME_SHOP,
  token: ShopifyAdminToken = TEST_ADMIN_TOKEN,
  /** The endpoint to talk to: the shop's own Admin URL, unless the case is about another one, such as a host that is down. */
  url: String = shopifyAdminGraphqlUrl(shop),
  onTokenRejected: () -> Unit = {},
  costReporter: ShopifyQueryCostReporter = ShopifyQueryCostReporter(),
): ShopifyGraphqlService =
  HttpShopifyGraphqlService(shop, GraphQLKtorClient(URI(url).toURL(), shopifyClient), token, onTokenRejected, costReporter)

/**
 * The Admin Graphql endpoint of a real shop. Derived from the configured API version rather than spelled out, so
 * bumping the version does not break a test that has no opinion about it.
 */
internal fun shopifyAdminGraphqlUrl(shop: ShopDomain = ACME_SHOP, apiVersion: String = Config.SHOPIFY_API_VERSION): String =
  "https://${shop.normalizedShopifyHost}/admin/api/$apiVersion/graphql.json"

/**
 * A [FakeMonolithHttpServer] and the base URL it answers on, stopped when [block] returns. The twin of
 * [withFakeShopifyServer]: the monolith is reached by a configured base URL rather than by a rewritten host, so what a
 * caller needs back is the URL, not a client.
 */
internal fun <T> withFakeMonolithServer(block: (FakeMonolithHttpServer, String) -> T): T {
  val server = FakeMonolithHttpServer()
  val baseUrl = "http://localhost:${server.start()}"
  try {
    return block(server, baseUrl)
  } finally {
    server.stop()
  }
}
