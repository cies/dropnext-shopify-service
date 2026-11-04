package dropnext.dss.mapper

import dropnext.dss.contract.ProductStatus
import dropnext.dss.contract.ProductVariantItem
import dropnext.dss.contract.SelectedOption
import dropnext.graphql.generated.enums.ProductStatus as GraphqlProductStatus
import dropnext.graphql.generated.getproductbyid.Media
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant


/**
 * The variants plus what the caller reports about them: a status this build does not know, which they carry as a draft.
 * The mapper only answers it; the caller knows the API version a schema bump starts from, and how often to say so.
 */
data class MonolithProductMapping(
  val productVariants: List<ProductVariantItem>,
  val hasUnknownStatus: Boolean,
)

/**
 * Maps a Shopify Admin Graphql product (from [dropnext.graphql.generated.GetProductById])
 * to the [ProductVariantItem] DTOs sent to the monolith via `POST` to
 * [dropnext.dss.lib.monolith.OutBoundMonolithPaths.productVariants].
 * A variant without a numeric id is left out, and so is every variant of a product without one.
 *
 * @param currencyCode ISO 4217 currency code for the shop (e.g. "USD");
 *   obtain from the `shop { currencyCode }` field returned alongside the product query.
 */
fun mapProductForMonolith(product: Product, currencyCode: String): MonolithProductMapping {
  val hasUnknownStatus = product.status == GraphqlProductStatus.__UNKNOWN_VALUE
  val productLegacyId = product.legacyResourceId.toLongOrNull()
    ?: return MonolithProductMapping(emptyList(), hasUnknownStatus)
  val productImageUrls = product.catalogImageUrls()
  val mappedStatus = product.status.toContractStatus()
  val productVariants = product.variants.edges.mapNotNull { variantEdge ->
    val v = variantEdge.node
    val variantLegacyId = v.legacyResourceId.toLongOrNull() ?: return@mapNotNull null
    ProductVariantItem(
      productVariantId = variantLegacyId,
      productId = productLegacyId,
      productTitle = product.title,
      productDescription = product.description,
      productDescriptionHtml = product.descriptionHtml,
      productVendor = product.vendor,
      productType = product.productType,
      productTags = product.tags,
      productHandle = product.handle,
      productStatus = mappedStatus,
      productImages = productImageUrls,
      productPublishedAt = product.publishedAt,
      productCreatedAt = product.createdAt,
      productUpdatedAt = product.updatedAt,
      title = v.title,
      sku = v.sku,
      barcode = v.barcode,
      priceAsString = shopifyMoneyAmountForWire(v.price),
      priceCurrency = currencyCode,
      selectedOptions = v.selectedOptions.map { opt -> SelectedOption(opt.name, opt.value) },
      imageUrl = v.variantImageUrlOrNull(),
    )
  }
  return MonolithProductMapping(productVariants, hasUnknownStatus)
}

private fun Product.catalogImageUrls(): List<String> =
  media.edges.mapNotNull { edge -> edge.node.imageUrlOrNull() }

private fun ProductVariant.variantImageUrlOrNull(): String? =
  media.edges.firstOrNull()?.node?.imageUrlOrNull()

private fun Media.imageUrlOrNull(): String? = when (this) {
  is MediaImage -> image?.url
  else -> null
}

/**
 * A status this build does not know is recorded as a draft,
 * the contract's value that claims the least,
 * so an unknown status is never recorded as sellable.
 * The monolith then offers no quotations for the product, and shows suppliers that it is not for sale.
 * The decoder keeps only that the value was unknown, not what it was.
 */
private fun GraphqlProductStatus.toContractStatus(): ProductStatus =
  when (this) {
    GraphqlProductStatus.ACTIVE -> ProductStatus.ACTIVE
    GraphqlProductStatus.UNLISTED -> ProductStatus.UNLISTED
    GraphqlProductStatus.ARCHIVED -> ProductStatus.ARCHIVED
    GraphqlProductStatus.DRAFT -> ProductStatus.DRAFT
    GraphqlProductStatus.__UNKNOWN_VALUE -> ProductStatus.DRAFT
  }
