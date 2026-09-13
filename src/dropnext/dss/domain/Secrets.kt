package dropnext.dss.domain


// The secrets, one type per kind. Two rules set these apart from every other value class here:
//
// * `toString()` is `"***"`, never the value. Ids reach log lines by interpolation; for a secret that
//   interpolation *is* the incident.
// * None of them is `@Serializable`, so none can end up in a payload by accident. The contract DTOs
//   that do carry a token on the wire keep a `String` and the handler wraps or unwraps at the boundary.
//
// Every type repeats `override fun toString() = "***"` because a value class cannot inherit it.
// `ArchitectureTest` pins both properties.

/** A shop's Shopify Admin API access token (`shpat_…`): issued by OAuth, cached in memory, stored by the monolith. */
@JvmInline
value class ShopifyAdminToken(val value: String) {
  override fun toString() = "***"
}

/** The app's client secret: signs the OAuth `state`, verifies the callback HMAC and every webhook body. */
@JvmInline
value class ShopifyAppSecret(val value: String) {
  override fun toString() = "***"
}

/**
 * The bearer token we present to the monolith. Its counterpart [MonolithToDssApiKey] is a different type on
 * purpose: the two travel in opposite directions and are rotated independently. The monolith reads the same
 * two variable names, so the same name holds the same value on both sides.
 */
@JvmInline
value class DssToMonolithApiKey(val value: String) {
  override fun toString() = "***"
}

/** The bearer token the monolith presents to us; see [DssToMonolithApiKey] for why it is its own type. */
@JvmInline
value class MonolithToDssApiKey(val value: String) {
  override fun toString() = "***"
}

/** The Logflare account key the log shipper authenticates with. */
@JvmInline
value class LogflareApiKey(val value: String) {
  override fun toString() = "***"
}
