package dropnext.dss.workflow

import dropnext.dss.contract.ShopifyAccessProblemKind
import dropnext.dss.contract.ShopifyAccessProblemRequest
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/** Only a token Shopify refuses and a scope it denies are news to the monolith; neither changes what the caller answers. */
class ReportShopifyAccessProblemTest {

  private val observedAt = Instant.parse("2026-11-04T12:00:00Z")

  private fun report(
    monolith: FakeMonolithService,
    error: ShopifyError,
    within: Duration = ACCESS_PROBLEM_REPORT_BUDGET,
  ) = runBlocking { reportShopifyAccessProblem(monolith, ACME_SHOP, error, "orders/create webhook", observedAt, within) }

  @Test
  fun `a rejected token is reported with no scopes`() {
    val monolith = FakeMonolithService()
    report(monolith, ShopifyError.TokenRejected(401))
    val expected = ShopifyAccessProblemRequest(
      shopifySubdomain = "acme",
      kind = ShopifyAccessProblemKind.TOKEN_REJECTED,
      missingAccessScopes = emptyList(),
      observedDuring = "orders/create webhook",
      observedAt = "2026-11-04T12:00:00Z",
    )
    assert(monolith.postShopifyAccessProblemCalls == listOf(expected))
  }

  /** The monolith drops a report about a token a reinstall has replaced, which it can only tell by the token's name. */
  @Test
  fun `a report names the token Shopify refused`() {
    val monolith = FakeMonolithService()
    report(monolith, ShopifyError.TokenRejected(401, tokenFingerprint = "0123456789abcdef"))
    report(monolith, ShopifyError.AccessDenied("Access denied.", listOf("read_orders"), tokenFingerprint = "fedcba9876543210"))
    assert(monolith.postShopifyAccessProblemCalls.map { it.tokenFingerprint } == listOf("0123456789abcdef", "fedcba9876543210"))
  }

  @Test
  fun `a denied scope is reported with the handles Shopify named`() {
    val monolith = FakeMonolithService()
    report(monolith, ShopifyError.AccessDenied("Access denied for fulfillmentOrders field.", listOf("read_orders", "write_fulfillments")))
    val reported = monolith.postShopifyAccessProblemCalls.single()
    assert(reported.kind == ShopifyAccessProblemKind.ACCESS_SCOPE_MISSING)
    assert(reported.missingAccessScopes == listOf("read_orders", "write_fulfillments"))
    assert(reported.observedDuring == "orders/create webhook")
  }

  /** No merchant can fix it, so the monolith is not asked to notify one; someone has to deploy, so it is an error. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a denied scope the install does not ask for is not reported, and is logged as an error`() {
    val monolith = FakeMonolithService()
    val lines = capturingLogs { report(monolith, ShopifyError.AccessDenied("Access denied for customer field.", listOf("read_customers"))) }
    assert(monolith.postShopifyAccessProblemCalls.isEmpty())
    val line = lines.single { "Shopify access problem not reported" in it }
    assert(line.startsWith("ERROR "))
    assert("reason=scope_not_requested required_scopes=read_customers" in line)
  }

  @Test
  fun `every other failure is not reported`() {
    val monolith = FakeMonolithService()
    listOf(
      ShopifyError.Network("connection reset"),
      ShopifyError.HttpError(503),
      ShopifyError.GraphqlError("Query cost is too high", listOf("MAX_COST_EXCEEDED")),
      ShopifyError.UserError(listOf("refused")),
      ShopifyError.NotFound("order"),
    ).forEach { error -> report(monolith, error) }
    assert(monolith.postShopifyAccessProblemCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a report with no budget left is skipped and logged`() {
    val monolith = FakeMonolithService()
    val lines = capturingLogs { report(monolith, ShopifyError.TokenRejected(401), within = Duration.ZERO) }
    assert(monolith.postShopifyAccessProblemCalls.isEmpty())
    val line = lines.single { "Shopify access problem not reported" in it }
    assert(line.startsWith("WARN "))
    assert("reason=budget_exhausted" in line)
    assert("kind=token_rejected" in line)
  }

  /** The monolith did not answer in what was left: the same line as a report never sent, since nothing is known of it. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a report the budget cuts short is logged as budget exhausted`() {
    val monolith = FakeMonolithService().apply { writeDelay = 5.seconds }
    val lines = capturingLogs { report(monolith, ShopifyError.TokenRejected(401), within = 50.milliseconds) }
    assert(monolith.postShopifyAccessProblemCalls.size == 1)
    assert(lines.single { "Shopify access problem not reported" in it }.contains("reason=budget_exhausted"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a report the monolith takes is one info line`() {
    val monolith = FakeMonolithService()
    val lines = capturingLogs { report(monolith, ShopifyError.AccessDenied("denied", listOf("read_orders"))) }
    val line = lines.single { "Shopify access problem reported" in it }
    assert(line.startsWith("INFO "))
    assert("kind=access_scope_missing" in line)
    assert("first_report=true" in line)
  }

  /** A store the monolith does not know is a refusal, a warning and nothing else. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a report for a store the monolith does not know is logged at warn`() {
    val monolith = FakeMonolithService().apply { postShopifyAccessProblemStatus = 404 }
    val lines = capturingLogs { report(monolith, ShopifyError.TokenRejected(401)) }
    val line = lines.single { "Monolith postShopifyAccessProblem failed" in it }
    assert(line.startsWith("WARN "))
    assert("status=404" in line)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a report the monolith never answered is logged at error`() {
    val monolith = FakeMonolithService().apply { postShopifyAccessProblemTransportFailure = true }
    val lines = capturingLogs { report(monolith, ShopifyError.TokenRejected(401)) }
    assert(lines.single { "Monolith postShopifyAccessProblem failed" in it }.startsWith("ERROR "))
  }
}
