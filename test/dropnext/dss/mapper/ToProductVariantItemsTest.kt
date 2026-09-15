package dropnext.dss.mapper

import dropnext.dss.contract.ProductStatus as DtoProductStatus
import dropnext.graphql.generated.enums.ProductStatus as GraphqlProductStatus
import dropnext.graphql.generated.getproductbyid.ExternalVideo
import dropnext.graphql.generated.getproductbyid.Image
import dropnext.graphql.generated.getproductbyid.Media
import dropnext.graphql.generated.getproductbyid.MediaConnection
import dropnext.graphql.generated.getproductbyid.MediaEdge
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant
import dropnext.graphql.generated.getproductbyid.ProductVariantConnection
import dropnext.graphql.generated.getproductbyid.ProductVariantEdge
import dropnext.graphql.generated.getproductbyid.SelectedOption
import kotlin.test.Test

class ToProductVariantItemsTest {

  @Test
  fun `maps two variants into two ProductVariantItems sharing product fields`() {
    val product = sampleProduct(
      variants = listOf(
        sampleVariant(id = "201", title = "Small", price = "19.95"),
        sampleVariant(id = "202", title = "Large", price = "22.50"),
      ),
    )
    val items = product.toProductVariantItems(currencyCode = "USD")
    assert(items.size == 2)
    assert(items[0].productId == 101L)
    assert(items[0].productTitle == "T-Shirt")
    assert(items[0].productVariantId == 201L)
    assert(items[0].priceAsString == "19.95")
    assert(items[1].productVariantId == 202L)
    assert(items[1].priceAsString == "22.50")
  }

  @Test
  fun `returns empty list when product legacyResourceId is non-numeric`() {
    val product = sampleProduct(legacyResourceId = "not-a-number")
    val items = product.toProductVariantItems("USD")
    assert(items.isEmpty())
  }

  @Test
  fun `drops variants with non-numeric legacyResourceId`() {
    val product = sampleProduct(
      variants = listOf(
        sampleVariant(id = "abc", title = "Bad"),
        sampleVariant(id = "203", title = "Good"),
      ),
    )
    val items = product.toProductVariantItems("USD")
    assert(items.size == 1)
    assert(items.single().productVariantId == 203L)
  }

  @Test
  fun `passes through Shopify price string unchanged`() {
    val product = sampleProduct(variants = listOf(sampleVariant(price = "19.999")))
    val items = product.toProductVariantItems("USD")
    assert(items.single().priceAsString == "19.999")
  }

  @Test
  fun `blank price defaults to zero decimal`() {
    val product = sampleProduct(variants = listOf(sampleVariant(price = "")))
    val items = product.toProductVariantItems("USD")
    assert(items.single().priceAsString == "0.00")
  }

  @Test
  fun `passes through invalid price string unchanged`() {
    val product = sampleProduct(variants = listOf(sampleVariant(price = "n/a")))
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
    val product = sampleProduct(media = media)
    val items = product.toProductVariantItems("USD")
    assert(items.single().productImages == listOf("https://cdn.example/cover.jpg", "https://cdn.example/back.jpg"))
  }

  @Test
  fun `maps Graphql ProductStatus to DTO`() {
    assert(sampleProduct(status = GraphqlProductStatus.ACTIVE).toProductVariantItems("USD").single().productStatus == DtoProductStatus.ACTIVE)
    assert(sampleProduct(status = GraphqlProductStatus.ARCHIVED).toProductVariantItems("USD").single().productStatus == DtoProductStatus.ARCHIVED)
    assert(sampleProduct(status = GraphqlProductStatus.DRAFT).toProductVariantItems("USD").single().productStatus == DtoProductStatus.DRAFT)
  }

  @Test
  fun `maps unknown ProductStatus to ACTIVE`() {
    val items = sampleProduct(status = GraphqlProductStatus.__UNKNOWN_VALUE).toProductVariantItems("USD")
    assert(items.single().productStatus == DtoProductStatus.ACTIVE)
  }

  @Test
  fun `propagates currencyCode through to each variant`() {
    val items = sampleProduct().toProductVariantItems(currencyCode = "EUR")
    assert(items.single().priceCurrency == "EUR")
  }

  @Test
  fun `picks variant image from first variant-media when present`() {
    val variantWithImage = sampleVariant(media = listOf(MediaImage(image = Image(url = "https://cdn.example/v.jpg"))))
    val items = sampleProduct(variants = listOf(variantWithImage)).toProductVariantItems("USD")
    assert(items.single().imageUrl == "https://cdn.example/v.jpg")
  }

  @Test
  fun `variant imageUrl is null when no media on variant`() {
    val items = sampleProduct(variants = listOf(sampleVariant(media = emptyList()))).toProductVariantItems("USD")
    assert(items.single().imageUrl == null)
  }

  @Test
  fun `passes selectedOptions through`() {
    val variant = sampleVariant(
      selectedOptions = listOf(SelectedOption("Color", "Blue"), SelectedOption("Size", "M")),
    )
    val items = sampleProduct(variants = listOf(variant)).toProductVariantItems("USD")
    val opts = items.single().selectedOptions
    assert(opts.size == 2)
    assert(opts[0].name == "Color" && opts[0].value == "Blue")
    assert(opts[1].name == "Size" && opts[1].value == "M")
  }

  private fun sampleVariant(
    id: String = "201",
    title: String = "Default",
    price: String = "10.00",
    media: List<Media> = emptyList(),
    selectedOptions: List<SelectedOption> = emptyList(),
  ): ProductVariant =
    ProductVariant(
      legacyResourceId = id,
      title = title,
      sku = "SKU-$id",
      barcode = null,
      price = price,
      selectedOptions = selectedOptions,
      media = MediaConnection(edges = media.map { MediaEdge(node = it) }),
    )

  private fun sampleProduct(
    legacyResourceId: String = "101",
    status: GraphqlProductStatus = GraphqlProductStatus.ACTIVE,
    variants: List<ProductVariant> = listOf(sampleVariant()),
    media: List<Media> = emptyList(),
  ): Product =
    Product(
      legacyResourceId = legacyResourceId,
      title = "T-Shirt",
      description = "Soft cotton tee",
      descriptionHtml = "<p>Soft cotton tee</p>",
      vendor = "Acme",
      productType = "Apparel",
      tags = listOf("summer", "sale"),
      handle = "t-shirt",
      status = status,
      publishedAt = "2026-04-01T00:00:00Z",
      createdAt = "2026-03-01T00:00:00Z",
      updatedAt = "2026-04-15T00:00:00Z",
      media = MediaConnection(edges = media.map { MediaEdge(node = it) }),
      variants = ProductVariantConnection(edges = variants.map { ProductVariantEdge(node = it) }),
    )
}
