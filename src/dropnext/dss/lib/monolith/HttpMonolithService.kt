package dropnext.dss.lib.monolith

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.DeleteProductVariantsResponse
import dropnext.dss.contract.StoreResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.contract.UpsertProductVariantsResponse
import dropnext.dss.domain.DssToMonolithApiKey
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.lib.json.MonolithJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException


/**
 * Production [MonolithService]: real HTTP calls to the DropNext monolith over Ktor + OkHttp.
 *
 * URLs are built from `{baseUrl}/{apiPathPrefix}{path}`; both edges are trimmed of slashes
 * (see [prefixedBase]). Every method answers a [MonolithResult]: a network failure is
 * [MonolithError.Transport], a non-success status [MonolithError.Rejected] with the parsed body.
 *
 * The HTTP client it is given forwards the request's trace id as `X-Trace-Id`, so the monolith's log
 * lines for the call can be found from ours (and ours from theirs, through the trace id in its error body).
 */
class HttpMonolithService(
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiPathPrefix: String?,
  private val apiKey: DssToMonolithApiKey?,
) : MonolithService {

  private val prefixedBase: String = run {
    val b = baseUrl.trimEnd('/')
    val p = apiPathPrefix?.trim()?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
    if (p == null) b else "$b/$p"
  }

  private fun url(path: String): String = "$prefixedBase/${path.trimStart('/')}"

  private fun HttpRequestBuilder.applyDefaults() {
    accept(ContentType.Application.Json)
    apiKey?.takeIf { it.value.isNotBlank() }?.let { header("Authorization", "Bearer ${it.value}") }
  }

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome> =
    monolithCall({
      httpClient.post(url(OutBoundMonolithPaths.orders)) {
        contentType(ContentType.Application.Json)
        applyDefaults()
        setBody(MonolithJson.encodeToString(CreateShopifyOrderRequest.serializer(), request))
      }
    }) { response, body ->
      when (response.status) {
        HttpStatusCode.OK -> Success(CreateOrderOutcome.Created)
        HttpStatusCode.Conflict -> Success(CreateOrderOutcome.AlreadyExisted)
        else -> Failure(rejected(response.status, body))
      }
    }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): MonolithResult<StoreId> =
    monolithCall({
      httpClient.put(url(OutBoundMonolithPaths.storesApiKey)) {
        contentType(ContentType.Application.Json)
        applyDefaults()
        setBody(MonolithJson.encodeToString(UpdateStoreApiKeyRequest.serializer(), request))
      }
    }) { response, body ->
      response.decodeOk(body, UpdateStoreApiKeyResponse.serializer()) { StoreId(it.storeId) }
    }

  override suspend fun getStore(shopifySubdomain: String): MonolithResult<MonolithStore?> =
    monolithCall({
      httpClient.get(url(OutBoundMonolithPaths.stores)) {
        applyDefaults()
        parameter("shopify_subdomain", shopifySubdomain)
      }
    }) { response, body ->
      if (response.status == HttpStatusCode.NotFound) Success(null)
      else response.decodeOk(body, StoreResponse.serializer()) { parsed ->
        MonolithStore(
          storeId = StoreId(parsed.storeId),
          shopifyShopId = ShopifyShopId(parsed.shopifyShopId),
          apiKey = parsed.apiKey?.let(::ShopifyAdminToken),
        )
      }
    }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<Int> =
    monolithCall({
      httpClient.post(url(OutBoundMonolithPaths.productVariants)) {
        contentType(ContentType.Application.Json)
        applyDefaults()
        setBody(MonolithJson.encodeToString(UpsertProductVariantsRequest.serializer(), request))
      }
    }) { response, body ->
      response.decodeOk(body, UpsertProductVariantsResponse.serializer()) { it.upserted }
    }

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): MonolithResult<Int> =
    monolithCall({
      httpClient.delete(url(OutBoundMonolithPaths.productVariants)) {
        contentType(ContentType.Application.Json)
        applyDefaults()
        setBody(MonolithJson.encodeToString(DeleteProductVariantsRequest.serializer(), request))
      }
    }) { response, body ->
      response.decodeOk(body, DeleteProductVariantsResponse.serializer()) { it.deleted }
    }
}

/** Runs [request] and hands the response plus its body to [onResponse]; an [IOException] becomes [MonolithError.Transport]. */
private suspend inline fun <T> monolithCall(
  request: () -> HttpResponse,
  onResponse: (HttpResponse, String) -> MonolithResult<T>,
): MonolithResult<T> = try {
  val response = request()
  onResponse(response, response.bodyAsText())
} catch (e: IOException) {
  Failure(MonolithError.Transport(e.message ?: "network error"))
}

/**
 * Deserializes [body] as [T] on `200 OK` and maps it with [onOk]; any other status is
 * [MonolithError.Rejected]. A `200` that does not decode is [MonolithError.Undecodable] rather than an
 * exception: it is an expected failure (a proxy's HTML page, a contract that drifted), not a bug here.
 */
private inline fun <T : Any, R> HttpResponse.decodeOk(
  body: String,
  serializer: KSerializer<T>,
  onOk: (T) -> R,
): MonolithResult<R> {
  if (status != HttpStatusCode.OK) return Failure(rejected(status, body))
  val decoded = try {
    MonolithJson.decodeFromString(serializer, body)
  } catch (e: SerializationException) {
    // Only the first line: kotlinx appends the offending input after a newline, and the body must not reach the log.
    val complaint = e.message?.lineSequence()?.first()?.take(200) ?: "not the expected JSON"
    return Failure(MonolithError.Undecodable(status.value, complaint))
  }
  return Success(onOk(decoded))
}

private fun rejected(status: HttpStatusCode, body: String): MonolithError.Rejected {
  val (message, parsed) = monolithError(status.value, body)
  return MonolithError.Rejected(status.value, message, parsed)
}
