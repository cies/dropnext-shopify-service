package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopifyQueryCost
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.OTHER_SHOP
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.MutableTimeSource
import dropnext.dss.testutil.helper.capturingLogs
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


@ResourceLock(GLOBAL_LOG_REGISTRY)
class ShopifyQueryCostReporterTest {

  private val budget = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 640.0, restoreRate = 50.0)

  private fun cost(requested: Int) = ShopifyQueryCost(requested = requested, actual = 104, budget = budget)

  @Test
  fun `a requested cost over the threshold is warned about once per operation`() {
    val reporter = ShopifyQueryCostReporter()

    val lines = capturingLogs {
      reporter.report(ACME_SHOP, "GetOrderForDss", cost(812))
      reporter.report(OTHER_SHOP, "GetOrderForDss", cost(812))
      reporter.report(ACME_SHOP, "GetProductById", cost(900))
    }

    val warnings = lines.filter { it.startsWith("WARN") }
    assert(warnings.size == 2)
    assert("operation=GetOrderForDss requested=812 actual=104 cap=1000" in warnings[0])
    assert("operation=GetProductById requested=900" in warnings[1])
  }

  /** The threshold is exclusive: a query at exactly two thirds of the cap is fine. */
  @Test
  fun `a requested cost at the threshold is not warned about`() {
    val lines = capturingLogs { ShopifyQueryCostReporter().report(ACME_SHOP, "GetOrderForDss", cost(QUERY_COST_WARNING_THRESHOLD)) }

    assert(lines.none { it.startsWith("WARN") })
  }

  @Test
  fun `the cost is sampled once per interval per shop and operation`() {
    val clock = MutableTimeSource()
    val reporter = ShopifyQueryCostReporter(clock)

    val lines = capturingLogs {
      reporter.report(ACME_SHOP, "GetProductById", cost(50))
      reporter.report(ACME_SHOP, "GetProductById", cost(50))
      reporter.report(OTHER_SHOP, "GetProductById", cost(50))
      reporter.report(ACME_SHOP, "ShopIdentity", cost(2))
      clock += QUERY_COST_SAMPLE_INTERVAL - 1.seconds
      reporter.report(ACME_SHOP, "GetProductById", cost(50))
      clock += 1.seconds
      reporter.report(ACME_SHOP, "GetProductById", cost(50))
    }

    val samples = lines.filter { it.startsWith("INFO Shopify query cost ") }
    assert(samples.size == 4)
    assert(samples.count { "operation=GetProductById" in it } == 3)
    assert("requested=50 actual=104 available=640.0 maximum=1000.0 restore_rate=50.0" in samples[0])
  }

  @Test
  fun `a cost without a budget is sampled without one`() {
    val lines = capturingLogs {
      ShopifyQueryCostReporter().report(ACME_SHOP, "GetProductById", ShopifyQueryCost(requested = null, actual = null, budget = null))
    }

    assert(lines.single().startsWith("INFO Shopify query cost operation=GetProductById requested=null actual=null available=null"))
  }
}
