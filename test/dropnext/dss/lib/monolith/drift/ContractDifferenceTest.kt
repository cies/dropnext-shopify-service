package dropnext.dss.lib.monolith.drift

import org.junit.jupiter.api.Test


/** The lines keep the `key=value` shape Logflare queries are written against; a location with a space is quoted whole. */
class ContractDifferenceTest {

  @Test
  fun `an element only one side has names its kind, location and side`() {
    val difference = ContractDifference(ContractElementKind.PATH, "/api/shopify-service/v1/orders", isBreaking = false, side = ContractSide.MONOLITH_ONLY)
    assert(difference.line == "Contract drift kind=path location=/api/shopify-service/v1/orders side=monolith_only breaking=false")
  }

  @Test
  fun `a location with a space is quoted`() {
    val difference = ContractDifference(ContractElementKind.OPERATION, "DELETE /product-variants", isBreaking = true, side = ContractSide.COPY_ONLY)
    assert(difference.line == "Contract drift kind=operation location=\"DELETE /product-variants\" side=copy_only breaking=true")
  }

  @Test
  fun `an attribute both sides have names both values`() {
    val difference = ContractDifference(ContractElementKind.TYPE, "OrderLineItem.quantity", isBreaking = true, monolithValue = "string", copyValue = "integer(int32)")
    assert(difference.line == "Contract drift kind=type location=OrderLineItem.quantity monolith=string copy=integer(int32) breaking=true")
  }

  @Test
  fun `the api root has no location`() {
    val difference = ContractDifference(ContractElementKind.API_ROOT, null, isBreaking = true, monolithValue = "/api/v2", copyValue = "/api/v1")
    assert(difference.line == "Contract drift kind=api_root monolith=/api/v2 copy=/api/v1 breaking=true")
  }

  @Test
  fun `the summary counts every kind and says how many lines were shown`() {
    val comparison = ContractComparison(
      listOf(
        ContractDifference(ContractElementKind.PATH, "/a", isBreaking = false, side = ContractSide.MONOLITH_ONLY),
        ContractDifference(ContractElementKind.PATH, "/b", isBreaking = true, side = ContractSide.COPY_ONLY),
        ContractDifference(ContractElementKind.TYPE, "A.b", isBreaking = true, monolithValue = "string", copyValue = "integer"),
      ),
    )
    assert(
      comparison.summaryLine(shown = 2) ==
        "Contract drift summary differences=3 shown=2 breaking=2 roots=0 paths=2 webhooks=0 operations=0 parameters=0 " +
        "request_bodies=0 responses=0 schemas=0 properties=0 enums=0 required=0 types=1",
    )
  }
}
