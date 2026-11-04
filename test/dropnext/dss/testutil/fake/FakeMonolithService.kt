package dropnext.dss.testutil.fake

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.ShopifyAccessProblemRequest
import dropnext.dss.contract.ShopifyAccessProblemResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.MonolithStore
import dropnext.dss.lib.monolith.UpsertedProductVariants
import dropnext.dss.lib.monolith.drift.readCheckedInContractCopy
import dropnext.dss.lib.monolith.monolithError
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject


/** What one [FakeMonolithService.deleteProductVariants] call was asked. */
data class DeleteProductVariantsCall(val shopifySubdomain: String, val productId: ShopifyProductId)

/** In-memory [MonolithService] for business-logic tests (no HTTP, no mock frameworks). */
class FakeMonolithService : MonolithService, RecordingFake {
  val createOrderCalls: MutableList<CreateShopifyOrderRequest> = mutableListOf()
  val putStoreApiKeyCalls: MutableList<UpdateStoreApiKeyRequest> = mutableListOf()
  val getStoreCalls: MutableList<String> = mutableListOf()
  val upsertProductVariantsCalls: MutableList<UpsertProductVariantsRequest> = mutableListOf()
  val deleteProductVariantsCalls: MutableList<DeleteProductVariantsCall> = mutableListOf()
  val postShopifyAccessProblemCalls: MutableList<ShopifyAccessProblemRequest> = mutableListOf()

  /** The call takes no argument, so each entry only says it happened. */
  val getOpenApiDocumentCalls: MutableList<Unit> = mutableListOf()

  /** `200` created, `409` already existed, anything else is answered as a rejection with [createOrderErrorBody]. */
  var createOrderStatus: Int = 200
  var createOrderErrorBody: String? = null

  /** `200` saves, `404` is the monolith's own "store not found"; anything else a rejection with [putStoreApiKeyErrorBody]. */
  var putStoreApiKeyStatus: Int = 200
  var putStoreApiKeyStoreId: Long = 1L

  /** The body a rejection of [putStoreApiKey] carries in place of the contract's envelope, such as a router's page. */
  var putStoreApiKeyErrorBody: String? = null

  /** When true, [putStoreApiKey] fails to reach the monolith at all: the install reports that with no status. */
  var putStoreApiKeyTransportFailure: Boolean = false

  /** When true, [getStore] answers "no such store" — for testing missing-token paths. */
  var getStoreReturnsNotFound: Boolean = false

  /** When true, [getStore] fails to reach the monolith at all, which resolves differently from "no store". */
  var getStoreTransportFailure: Boolean = false
  var getStoreToken: ShopifyAdminToken? = ShopifyAdminToken("shpat_fake")

  /** When set, [getStore] is rejected with this status, as the monolith answers a key it does not accept. */
  var getStoreRejectionStatus: Int? = null

  /** What [getOpenApiDocument] answers: by default the checked-in copy, so a monolith in step with the DSS. */
  var openApiDocumentResult: MonolithResult<JsonObject> = Success(readCheckedInContractCopy())

  /** `200` answers the request's own count; anything else is a rejection with that status. */
  var upsertProductVariantsStatus: Int = 200
  var deleteProductVariantsStatus: Int = 200

  /** What a `200` on [deleteProductVariants] reports as `deleted`: the request no longer says how many variants there are. */
  var deleteProductVariantsDeleted: Int = 1

  /** `200` answers a first report for store `1`; `404` is a store the monolith does not know; anything else a rejection. */
  var postShopifyAccessProblemStatus: Int = 200

  /** When true, [postShopifyAccessProblem] fails to reach the monolith at all. */
  var postShopifyAccessProblemTransportFailure: Boolean = false

  /**
   * How long each write (create order, upsert or delete variants, an access problem report) takes once recorded: a
   * monolith still busy when a budget ends.
   */
  var writeDelay: Duration = Duration.ZERO


  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome> {
    createOrderCalls.add(request)
    delay(writeDelay)
    return when (createOrderStatus) {
      200 -> Success(CreateOrderOutcome.Created)
      409 -> Success(CreateOrderOutcome.AlreadyExisted)
      else -> Failure(
        rejected(
          createOrderStatus,
          createOrderErrorBody ?: """{"error":"fail","code":"InternalError","trace_id":"fake123"}""",
        ),
      )
    }
  }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): MonolithResult<StoreId> {
    putStoreApiKeyCalls.add(request)
    if (putStoreApiKeyTransportFailure) return Failure(MonolithError.Transport("forced transport failure"))
    val errorBody = putStoreApiKeyErrorBody
    return when {
      putStoreApiKeyStatus == 200 -> Success(StoreId(putStoreApiKeyStoreId))
      errorBody != null -> Failure(rejected(putStoreApiKeyStatus, errorBody))
      putStoreApiKeyStatus == 404 ->
        Failure(rejected(404, """{"error":"Store not found.","code":"NotFound","trace_id":"fake-trace"}"""))
      else -> Failure(
        rejected(putStoreApiKeyStatus, """{"error":"forced fail","code":"StoreError","trace_id":"fake-trace"}"""),
      )
    }
  }

  override suspend fun getStore(shopifySubdomain: String): MonolithResult<MonolithStore?> {
    getStoreCalls.add(shopifySubdomain)
    return when {
      getStoreTransportFailure -> Failure(MonolithError.Transport("forced transport failure"))
      getStoreRejectionStatus != null ->
        Failure(rejected(getStoreRejectionStatus!!, """{"error":"forced fail","code":"Unauthorized","trace_id":"fake-store"}"""))
      getStoreReturnsNotFound -> Success(null)
      else -> Success(MonolithStore(storeId = StoreId(1L), shopifyShopId = ShopifyShopId(99L), apiKey = getStoreToken))
    }
  }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<UpsertedProductVariants> {
    upsertProductVariantsCalls.add(request)
    delay(writeDelay)
    return when (upsertProductVariantsStatus) {
      200 -> Success(UpsertedProductVariants(upserted = request.productVariants.size, deleted = 0))
      else -> Failure(rejected(upsertProductVariantsStatus, """{"error":"forced fail","code":"VariantError","trace_id":"fake-upsert"}"""))
    }
  }

  override suspend fun deleteProductVariants(shopifySubdomain: String, productId: ShopifyProductId): MonolithResult<Int> {
    deleteProductVariantsCalls.add(DeleteProductVariantsCall(shopifySubdomain, productId))
    delay(writeDelay)
    return when (deleteProductVariantsStatus) {
      200 -> Success(deleteProductVariantsDeleted)
      else -> Failure(rejected(deleteProductVariantsStatus, """{"error":"forced fail","code":"VariantError","trace_id":"fake-delete"}"""))
    }
  }

  override suspend fun postShopifyAccessProblem(request: ShopifyAccessProblemRequest): MonolithResult<ShopifyAccessProblemResponse> {
    postShopifyAccessProblemCalls.add(request)
    delay(writeDelay)
    if (postShopifyAccessProblemTransportFailure) return Failure(MonolithError.Transport("forced transport failure"))
    return when (postShopifyAccessProblemStatus) {
      200 -> Success(ShopifyAccessProblemResponse(storeId = 1L, firstReport = true))
      404 -> Failure(rejected(404, """{"error":"Store not found.","trace_id":"fake-problem"}"""))
      else -> Failure(rejected(postShopifyAccessProblemStatus, """{"error":"forced fail","trace_id":"fake-problem"}"""))
    }
  }

  override suspend fun getOpenApiDocument(): MonolithResult<JsonObject> {
    getOpenApiDocumentCalls.add(Unit)
    return openApiDocumentResult
  }


  private fun rejected(status: Int, rawBody: String): MonolithError.Rejected {
    val (message, parsed) = monolithError(status, rawBody)
    return MonolithError.Rejected(status, message, parsed)
  }

  override fun clear() {
    createOrderCalls.clear()
    putStoreApiKeyCalls.clear()
    getStoreCalls.clear()
    upsertProductVariantsCalls.clear()
    deleteProductVariantsCalls.clear()
    postShopifyAccessProblemCalls.clear()
    getOpenApiDocumentCalls.clear()
    createOrderStatus = 200
    createOrderErrorBody = null
    putStoreApiKeyStatus = 200
    putStoreApiKeyStoreId = 1L
    putStoreApiKeyErrorBody = null
    putStoreApiKeyTransportFailure = false
    getStoreReturnsNotFound = false
    getStoreTransportFailure = false
    getStoreToken = ShopifyAdminToken("shpat_fake")
    getStoreRejectionStatus = null
    openApiDocumentResult = Success(readCheckedInContractCopy())
    upsertProductVariantsStatus = 200
    deleteProductVariantsStatus = 200
    deleteProductVariantsDeleted = 1
    postShopifyAccessProblemStatus = 200
    postShopifyAccessProblemTransportFailure = false
    writeDelay = Duration.ZERO
  }

}
