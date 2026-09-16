package dropnext.dss.mapper

import dropnext.dss.contract.ProductStatus as DtoProductStatus
import dropnext.dss.contract.ProductVariantItem
import dropnext.dss.contract.SelectedOption as DtoSelectedOption
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.sampleProductVariant
import dropnext.graphql.generated.enums.ProductStatus as GraphqlProductStatus
import dropnext.graphql.generated.getproductbyid.ExternalVideo
import dropnext.graphql.generated.getproductbyid.Image
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.SelectedOption
import org.junit.jupiter.api.Test


class ToProductVariantItemsTest {

  /**
   * Every field, each with a value no other field has, so a swapped pair (the description and its HTML, the created and
   * updated stamps, the product and the variant title) fails here.
   */
  @Test
  fun `maps a product and its variant to the item the monolith expects`() {
    val product = sampleProduct(
      legacyResourceId = "101",
      title = "T-Shirt",
      description = "Soft cotton tee",
      descriptionHtml = "<p>Soft cotton tee</p>",
      vendor = "Acme",
      productType = "Apparel",
      tags = listOf("summer", "sale"),
      handle = "t-shirt",
      status = GraphqlProductStatus.DRAFT,
      publishedAt = "2026-04-02T00:00:00Z",
      createdAt = "2026-03-01T00:00:00Z",
      updatedAt = "2026-04-15T00:00:00Z",
      media = listOf(MediaImage(image = Image(url = "https://cdn.example/cover.jpg"))),
      variants = listOf(
        sampleProductVariant(
          variantId = "201",
          title = "Small",
          price = "19.95",
          sku = "TS-S",
          barcode = "4006381333931",
          selectedOptions = listOf(SelectedOption("Size", "S")),
          media = listOf(MediaImage(image = Image(url = "https://cdn.example/small.jpg"))),
        ),
      ),
    )

    val expected = ProductVariantItem(
      productVariantId = 201L,
      productId = 101L,
      productTitle = "T-Shirt",
      productDescription = "Soft cotton tee",
      productDescriptionHtml = "<p>Soft cotton tee</p>",
      productVendor = "Acme",
      productType = "Apparel",
      productTags = listOf("summer", "sale"),
      productHandle = "t-shirt",
      productStatus = DtoProductStatus.DRAFT,
      productImages = listOf("https://cdn.example/cover.jpg"),
      productPublishedAt = "2026-04-02T00:00:00Z",
      productCreatedAt = "2026-03-01T00:00:00Z",
      productUpdatedAt = "2026-04-15T00:00:00Z",
      title = "Small",
      sku = "TS-S",
      barcode = "4006381333931",
      priceAsString = "19.95",
      priceCurrency = "USD",
      selectedOptions = listOf(DtoSelectedOption(name = "Size", value = "S")),
      imageUrl = "https://cdn.example/small.jpg",
    )
    assert(product.toProductVariantItems(currencyCode = "USD") == listOf(expected))
  }

  @Test
  fun `maps two variants into two ProductVariantItems sharing product fields`() {
    val product = sampleProduct(
      legacyResourceId = "101",
      variants = listOf(
        sampleProductVariant("201", title = "Small", price = "19.95"),
        sampleProductVariant("202", title = "Large", price = "22.50"),
      ),
    )
    val items = product.toProductVariantItems(currencyCode = "USD")
    assert(items.map { it.productId } == listOf(101L, 101L))
    assert(items.map { it.productVariantId } == listOf(201L, 202L))
    assert(items.map { it.priceAsString } == listOf("19.95", "22.50"))
  }

  @Test
  fun `returns empty list when product legacyResourceId is non-numeric`() {
    val product = sampleProduct(legacyResourceId = "not-a-number", variantId = "201")
    val items = product.toProductVariantItems("USD")
    assert(items.isEmpty())
  }

  @Test
  fun `drops variants with non-numeric legacyResourceId`() {
    val product = sampleProduct(
      variants = listOf(
        sampleProductVariant("abc", title = "Bad"),
        sampleProductVariant("203", title = "Good"),
      ),
    )
    val items = product.toProductVariantItems("USD")
    assert(items.single().productVariantId == 203L)
  }

  @Test
  fun `passes through Shopify price string unchanged`() {
    val product = sampleProduct(variants = listOf(sampleProductVariant("201", price = "19.999")))
    val items = product.toProductVariantItems("USD")
    assert(items.single().priceAsString == "19.999")
  }

  @Test
  fun `blank price defaults to zero decimal`() {
    val product = sampleProduct(variants = listOf(sampleProductVariant("201", price = "")))
    val items = product.toProductVariantItems("USD")
    assert(items.single().priceAsString == "0.00")
  }

  @Test
  fun `passes through invalid price string unchanged`() {
    val product = sampleProduct(variants = listOf(sampleProductVariant("201", price = "n/a")))
    val items = product.toProductVariantItems("USD")
    assert(items.single().priceAsString == "n/a")
  }

  /** `ExternalVideo` exists only because the query selects it so that such a product decodes at all; the mapper skips it. */
  @Test
  fun `collects image urls from MediaImage and skips non-image media`() {
    val media = listOf(
      MediaImage(image = Image(url = "https://cdn.example/cover.jpg")),
      ExternalVideo(id = "gid://shopify/ExternalVideo/9"),
      MediaImage(image = Image(url = "https://cdn.example/back.jpg")),
      MediaImage(image = null),
    )
    val product = sampleProduct(variantId = "201", media = media)
    val items = product.toProductVariantItems("USD")
    assert(items.single().productImages == listOf("https://cdn.example/cover.jpg", "https://cdn.example/back.jpg"))
  }

  @Test
  fun `maps Graphql ProductStatus to DTO`() {
    assert(statusOf(GraphqlProductStatus.ACTIVE) == DtoProductStatus.ACTIVE)
    assert(statusOf(GraphqlProductStatus.ARCHIVED) == DtoProductStatus.ARCHIVED)
    assert(statusOf(GraphqlProductStatus.DRAFT) == DtoProductStatus.DRAFT)
  }

  @Test
  fun `maps unknown ProductStatus to ACTIVE`() {
    assert(statusOf(GraphqlProductStatus.__UNKNOWN_VALUE) == DtoProductStatus.ACTIVE)
  }

  @Test
  fun `propagates currencyCode through to each variant`() {
    val items = sampleProduct(variantId = "201").toProductVariantItems(currencyCode = "EUR")
    assert(items.single().priceCurrency == "EUR")
  }

  @Test
  fun `picks variant image from first variant-media when present`() {
    val variantWithImage = sampleProductVariant("201", media = listOf(MediaImage(image = Image(url = "https://cdn.example/v.jpg"))))
    val items = sampleProduct(variants = listOf(variantWithImage)).toProductVariantItems("USD")
    assert(items.single().imageUrl == "https://cdn.example/v.jpg")
  }

  @Test
  fun `variant imageUrl is null when no media on variant`() {
    val items = sampleProduct(variants = listOf(sampleProductVariant("201", media = emptyList()))).toProductVariantItems("USD")
    assert(items.single().imageUrl == null)
  }

  @Test
  fun `passes selectedOptions through`() {
    val variant = sampleProductVariant(
      "201",
      selectedOptions = listOf(SelectedOption("Color", "Blue"), SelectedOption("Size", "M")),
    )
    val items = sampleProduct(variants = listOf(variant)).toProductVariantItems("USD")
    assert(items.single().selectedOptions == listOf(DtoSelectedOption("Color", "Blue"), DtoSelectedOption("Size", "M")))
  }

  private fun statusOf(status: GraphqlProductStatus): DtoProductStatus =
    sampleProduct(variantId = "201", status = status).toProductVariantItems("USD").single().productStatus
}
