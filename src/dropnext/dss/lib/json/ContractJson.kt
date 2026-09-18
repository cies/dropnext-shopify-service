package dropnext.dss.lib.json

import kotlinx.serialization.json.Json


/**
 * JSON for the bodies this service answers the monolith with (generated OpenAPI DTOs).
 *
 * What separates it from [AppJson], the config the application's `ContentNegotiation` installs, is `explicitNulls`.
 * A field the contract marks required *and* nullable — `barcode`, `image_url`, `code` — is generated without a
 * default, so the monolith's decoder wants the key there and reads an omitted one as a missing field rather than as
 * `null`. [AppJson] leaves a `null` out, which is right for what this service says to Shopify and to itself, and a
 * decode failure on the other side of the contract.
 *
 * Defaults stay unencoded, which is the same rule read the other way round: a field the contract marks optional is
 * generated *with* a default, and leaving that one out is what optional means.
 */
val ContractJson = Json {
  explicitNulls = true
  encodeDefaults = false
}
