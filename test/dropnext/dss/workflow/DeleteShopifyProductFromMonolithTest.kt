package dropnext.dss.workflow

import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val deletedProduct = ShopifyProductId(503L)


class DeleteShopifyProductFromMonolithTest {

  @Test
  fun `asks the monolith to soft-delete the product's variants under the shop's subdomain`() = runBlocking {
    val monolith = FakeMonolithService()

    val outcome = deleteShopifyProductFromMonolith(monolith, ACME_SHOP, deletedProduct)

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    val request = monolith.deleteProductVariantsCalls.single()
    assert(request.shopifySubdomain == "acme")
    assert(request.productId == 503L)
  }

  /** The request names only the product, so the monolith's answer is the one place the variant count shows up. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `an accepted delete logs how many variants the monolith deleted`() {
    val monolith = FakeMonolithService().apply { deleteProductVariantsDeleted = 7 }

    val lines = capturingLogs {
      runBlocking { deleteShopifyProductFromMonolith(monolith, ACME_SHOP, deletedProduct) }
    }

    assert(lines.single { "Monolith delete variants ok" in it }.startsWith("INFO Monolith delete variants ok: 7 deleted productId=503 {"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith 404 on the delete is a permanent failure logged at warn with its trace id`() {
    val monolith = FakeMonolithService().apply { deleteProductVariantsStatus = 404 }

    val lines = capturingLogs {
      runBlocking { deleteShopifyProductFromMonolith(monolith, ACME_SHOP, deletedProduct) }
    }

    assert(monolith.deleteProductVariantsCalls.size == 1)
    val rejection = MonolithError.Rejected(404, "forced fail", MonolithErrorBody("forced fail", "VariantError", "fake-delete"))
    assert(lines.value == WebhookMirrorOutcome.MonolithFailed(rejection))
    // The monolith refused what we sent; a redelivery would be refused the same way.
    assert(!lines.value.isTransient)
    val line = lines.single { "Monolith deleteProductVariants failed" in it }
    // A 4xx is what we sent being refused: a warning, where a 5xx would be an error.
    assert(line.startsWith("WARN"))
    assert("status=404" in line)
    assert("productId=503" in line)
    assert("monolith_trace_id=fake-delete" in line)
  }
}
