package dropnext.dss.lib.monolith

import dev.forkhandles.result4k.Result
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId


/** Outbound calls to the main backend (monolith). Path constants in [OutBoundMonolithPaths]. */
interface MonolithService {
  /**
   * `POST` to [OutBoundMonolithPaths.orders] — only from Shopify order webhooks. Body is exactly
   * [CreateShopifyOrderRequest] (those top-level JSON keys — no extras). A `409` means the monolith
   * already has the order and is a success ([CreateOrderOutcome.AlreadyExisted]).
   */
  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome>

  /** `PUT` to [OutBoundMonolithPaths.storesApiKey] — persist the Shopify access token after OAuth installation. */
  suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): MonolithResult<StoreId>

  /** `GET` to [OutBoundMonolithPaths.stores] with `?shopify_subdomain=…`; a successful `null` means the monolith knows no such store. */
  suspend fun getStore(shopifySubdomain: String): MonolithResult<MonolithStore?>

  /**
   * `POST` to [OutBoundMonolithPaths.productVariants] — upsert product variants from a Shopify products/create or
   * products/update webhook. Answers what the monolith wrote, the variants a complete request replaced included.
   */
  suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<UpsertedProductVariants>

  /** `DELETE` to [OutBoundMonolithPaths.productVariants] — soft-delete variants that no longer exist in Shopify. Answers the deleted count. */
  suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): MonolithResult<Int>
}

/** What every [MonolithService] call returns. */
typealias MonolithResult<T> = Result<T, MonolithError>

/**
 * Why a monolith call produced no answer, in the distinctions a caller can act on: did the monolith
 * answer at all, and could the answer be read? A [Transport] failure is worth retrying; a [Rejected]
 * request gets the same answer next time; an [Undecodable] success is a contract drift or a proxy
 * page, and a human has to look.
 */
sealed interface MonolithError {
  val message: String

  /** No answer: connection refused, timeout, … (after the client's own retries). */
  data class Transport(override val message: String) : MonolithError

  /** The monolith answered with a non-success status; [body] carries its parsed error envelope, including its own trace id. */
  data class Rejected(val status: Int, override val message: String, val body: MonolithErrorBody) : MonolithError

  /** A success status whose body is not the DTO the contract promises; [detail] is the decoder's complaint, never the body. */
  data class Undecodable(val status: Int, val detail: String) : MonolithError {
    override val message: String get() = "unreadable $status body: $detail"
  }
}

/** A short, countable name for the failure, for a `key=value` log field; the counterpart of `ShopifyError.errorLabel`. */
val MonolithError.errorLabel: String
  get() = when (this) {
    is MonolithError.Transport -> "monolith_transport"
    is MonolithError.Rejected -> "monolith_$status"
    is MonolithError.Undecodable -> "monolith_undecodable"
  }

enum class CreateOrderOutcome { Created, AlreadyExisted }

/** The rows an upsert inserted or updated, and the variants a complete one soft-deleted (`0` for any other). */
data class UpsertedProductVariants(val upserted: Int, val deleted: Int)

data class MonolithStore(
  val storeId: StoreId,
  val shopifyShopId: ShopifyShopId,
  val apiKey: ShopifyAdminToken?,
)
