package dropnext.dss.testutil.fixture

import dropnext.graphql.generated.enums.ProductStatus
import dropnext.graphql.generated.getproductbyid.MediaConnection
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant
import dropnext.graphql.generated.getproductbyid.ProductVariantConnection
import dropnext.graphql.generated.getproductbyid.ProductVariantEdge


/**
 * The `GetProductById` payload. Generated types carry many required fields, so a literal written
 * per test rots on the next schema bump — this is the one place that has to be updated then.
 *
 * [variantId] null builds a product with no variants, which is what the "nothing to upsert" paths
 * need.
 */
internal fun sampleProduct(
  legacyResourceId: String = "501",
  variantId: String? = null,
  title: String = "Sample",
  publishedAt: String? = "2026-04-01T00:00:00Z",
): Product = Product(
  legacyResourceId = legacyResourceId,
  title = title,
  description = "",
  descriptionHtml = "",
  vendor = "",
  productType = "",
  tags = emptyList(),
  handle = "sample",
  status = ProductStatus.ACTIVE,
  publishedAt = publishedAt,
  createdAt = "2026-04-01T00:00:00Z",
  updatedAt = "2026-04-01T00:00:00Z",
  media = MediaConnection(edges = emptyList()),
  variants = ProductVariantConnection(
    edges = listOfNotNull(variantId?.let { ProductVariantEdge(node = sampleProductVariant(it)) }),
  ),
)

private fun sampleProductVariant(variantId: String): ProductVariant = ProductVariant(
  legacyResourceId = variantId,
  title = "Default",
  sku = "SKU-$variantId",
  barcode = null,
  price = "10.00",
  selectedOptions = emptyList(),
  media = MediaConnection(edges = emptyList()),
)
