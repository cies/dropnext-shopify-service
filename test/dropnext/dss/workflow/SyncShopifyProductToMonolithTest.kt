package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private const val PRODUCT_GID = "gid://shopify/Product/501"


/**
 * The product mirror behind `products/create` and `products/update`. Nothing here may raise: every
 * failure ends in the log and in the outcome the handler answers Shopify from, nowhere else.
 */
class SyncShopifyProductToMonolithTest {

  @Test
  fun `loads the product by gid and upserts its variants under the shop's subdomain and currency`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }

    syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(shopify.productByIdCalls == listOf(PRODUCT_GID))
    val upsert = monolith.upsertProductVariantsCalls.single()
    assert(upsert.shopifySubdomain == "acme")
    assert(upsert.productVariants.single().productVariantId == 9001L)
    assert(upsert.productVariants.single().priceCurrency == "EUR")
  }

  @Test
  fun `a product Shopify no longer has is skipped without touching the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { productByIdResult = Success(null) }

    syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a failed product load is logged and skips the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { productByIdResult = Failure(ShopifyError.HttpError(429)) }

    lateinit var outcome: WebhookMirrorOutcome
    val lines = capturingLogs { outcome = runBlocking { syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID) } }

    assert(monolith.upsertProductVariantsCalls.isEmpty())
    // A throttled Shopify is worth a redelivery.
    assert(outcome == WebhookMirrorOutcome.ShopifyFailed(ShopifyError.HttpError(429)))
    assert(outcome.isTransient)
    val line = lines.single { "could not load productGid=$PRODUCT_GID" in it }

    assert(line.startsWith("WARN"))
    assert("Shopify answered HTTP 429" in line)
  }

  /** A product with no numeric variant id maps to nothing; sending an empty upsert would be a pointless round trip. */
  @Test
  fun `a product without a mappable variant is not sent`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = null), "EUR"))
    }

    syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(shopify.productByIdCalls.size == 1)
    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith rejection is logged with its trace id and does not raise`() {
    val monolith = FakeMonolithService().apply { upsertProductVariantsStatus = 500 }
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }

    val lines = capturingLogs { runBlocking { syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID) } }

    assert(monolith.upsertProductVariantsCalls.size == 1)
    val line = lines.single { "Monolith upsertProductVariants failed" in it }
    assert(line.startsWith("ERROR"))
    assert("status=500" in line)
    assert("monolith_trace_id=fake-upsert" in line)
  }
}

