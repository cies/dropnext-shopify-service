package dropnext.dss.testutil.fixture

import dropnext.graphql.generated.enums.ProductStatus
import dropnext.graphql.generated.getproductbyid.Media
import dropnext.graphql.generated.getproductbyid.MediaConnection
import dropnext.graphql.generated.getproductbyid.MediaEdge
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant
import dropnext.graphql.generated.getproductbyid.ProductVariantConnection
import dropnext.graphql.generated.getproductbyid.ProductVariantEdge
import dropnext.graphql.generated.getproductbyid.SelectedOption


/**
 * The `GetProductById` payload. Generated types carry many required fields, so a literal written
 * per test rots on the next schema bump — this is the one place that has to be updated then.
 *
 * [variantId] null builds a product with no variants, which is what the "nothing to upsert" paths
 * need. [variants] replaces it for a product with several variants or one the case shapes itself.
 */
internal fun sampleProduct(
  legacyResourceId: String = "501",
  variantId: String? = null,
  title: String = "Sample",
  publishedAt: String? = "2026-04-01T00:00:00Z",
  status: ProductStatus = ProductStatus.ACTIVE,
  description: String = "",
  descriptionHtml: String = "",
  vendor: String = "",
  productType: String = "",
  tags: List<String> = emptyList(),
  handle: String = "sample",
  createdAt: String = "2026-04-01T00:00:00Z",
  updatedAt: String = "2026-04-01T00:00:00Z",
  media: List<Media> = emptyList(),
  variants: List<ProductVariant> = listOfNotNull(variantId?.let { sampleProductVariant(it) }),
): Product = Product(
  legacyResourceId = legacyResourceId,
  title = title,
  description = description,
  descriptionHtml = descriptionHtml,
  vendor = vendor,
  productType = productType,
  tags = tags,
  handle = handle,
  status = status,
  publishedAt = publishedAt,
  createdAt = createdAt,
  updatedAt = updatedAt,
  media = MediaConnection(edges = media.map { MediaEdge(node = it) }),
  variants = ProductVariantConnection(edges = variants.map { ProductVariantEdge(node = it) }),
)

internal fun sampleProductVariant(
  variantId: String,
  title: String = "Default",
  price: String = "10.00",
  sku: String? = "SKU-$variantId",
  barcode: String? = null,
  selectedOptions: List<SelectedOption> = emptyList(),
  media: List<Media> = emptyList(),
): ProductVariant = ProductVariant(
  legacyResourceId = variantId,
  title = title,
  sku = sku,
  barcode = barcode,
  price = price,
  selectedOptions = selectedOptions,
  media = MediaConnection(edges = media.map { MediaEdge(node = it) }),
)
