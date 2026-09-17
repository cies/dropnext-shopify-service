package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.sampleProductPage
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

    val outcome = syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(outcome == WebhookMirrorOutcome.Mirrored)
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

    val outcome = syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(outcome == WebhookMirrorOutcome.Skipped(WebhookSkipReason.PRODUCT_GONE))
    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  /**
   * The mirror is all-or-nothing: half a product's variants upserted as if they were the whole product is what the
   * monolith would later prune the rest against, so the sync stops here and no redelivery is asked for.
   */
  @Test
  fun `a product Shopify has more variants of is not mirrored and is not worth a redelivery`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Failure(ShopifyError.Truncated("product.variants", 100))
    }

    val outcome = runBlocking { syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID) }

    assert(monolith.upsertProductVariantsCalls.isEmpty())
    assert(outcome == WebhookMirrorOutcome.ShopifyFailed(ShopifyError.Truncated("product.variants", 100)))
    assert(!outcome.isTransient)
  }

  @Test
  fun `a product with more variants than a page reaches the monolith whole, in one upsert`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage((1L..100L).toList(), nextCursor = "c1"))
      productByIdResultQueue += Success(sampleProductPage((101L..150L).toList()))
    }

    val outcome = syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(shopify.productByIdCursors == listOf(null, "c1"))
    val upsert = monolith.upsertProductVariantsCalls.single()
    assert(upsert.productVariants.map { it.productVariantId } == (1L..150L).toList())
  }

  /**
   * Every page shares the webhook's four seconds with the upsert. A product too large for that is refused on a page cap
   * rather than left to run out of time, which Shopify would redeliver without end.
   */
  @Test
  fun `a product with more pages than a webhook may load is not mirrored and is not worth a redelivery`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      (1..WEBHOOK_MAX_VARIANT_PAGES).forEach { page ->
        productByIdResultQueue += Success(sampleProductPage(listOf(page.toLong()), nextCursor = "c$page"))
      }
    }

    val outcome = syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    val truncated = ShopifyError.Truncated("product.variants", WEBHOOK_MAX_VARIANT_PAGES * 100)
    assert(outcome == WebhookMirrorOutcome.ShopifyFailed(truncated))
    assert(!outcome.isTransient)
    assert(shopify.productByIdCalls.size == WEBHOOK_MAX_VARIANT_PAGES)
    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a failed product load is logged and skips the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { productByIdResult = Failure(ShopifyError.HttpError(429)) }

    val lines = capturingLogs { runBlocking { syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID) } }

    assert(monolith.upsertProductVariantsCalls.isEmpty())
    // A throttled Shopify is worth a redelivery.
    assert(lines.value == WebhookMirrorOutcome.ShopifyFailed(ShopifyError.HttpError(429)))
    assert(lines.value.isTransient)
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

    val outcome = syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID)

    assert(outcome == WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES))
    assert(shopify.productByIdCalls.size == 1)
    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith 500 on the upsert is a transient failure logged with the monolith's trace id`() {
    val monolith = FakeMonolithService().apply { upsertProductVariantsStatus = 500 }
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }

    val lines = capturingLogs { runBlocking { syncShopifyProductToMonolith(shopify, monolith, PRODUCT_GID) } }

    assert(monolith.upsertProductVariantsCalls.size == 1)
    val rejection = MonolithError.Rejected(500, "forced fail", MonolithErrorBody("forced fail", "VariantError", "fake-upsert"))
    assert(lines.value == WebhookMirrorOutcome.MonolithFailed(rejection))
    // A monolith that answers a 5xx may take the redelivery.
    assert(lines.value.isTransient)
    val line = lines.single { "Monolith upsertProductVariants failed" in it }
    assert(line.startsWith("ERROR"))
    assert("status=500" in line)
    assert("monolith_trace_id=fake-upsert" in line)
  }
}

