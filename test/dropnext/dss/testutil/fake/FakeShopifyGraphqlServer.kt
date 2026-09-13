package dropnext.dss.testutil.fake

import dropnext.dss.boot.config.Config
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Embedded CIO server that fakes a Shopify Admin Graphql endpoint.
 * Records every POST'd request by `operationName` and serves a stubbed response payload.
 */
class FakeShopifyGraphqlServer : RecordingFake {

  data class RecordedCall(
    val operationName: String,
    val variables: JsonElement,
    val authorization: String?,
    val rawBody: String,
    /** The path the caller built, which is where the API version becomes observable. */
    val path: String,
  )

  /** What one operation answers: the body, and the status Shopify would put in front of it. */
  private data class CannedResponse(
    val body: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val headers: Map<String, String> = emptyMap(),
  )

  // Written by the server's own request thread, read by the test thread.
  val calls: MutableList<RecordedCall> = CopyOnWriteArrayList()

  private val responses: MutableMap<String, CannedResponse> = mutableMapOf()
  private val responseQueues: MutableMap<String, ArrayDeque<CannedResponse>> = mutableMapOf()

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /** OAuth token-exchange response served on `POST /admin/oauth/access_token`. */
  @Volatile
  var oauthAccessTokenResponse: String =
    """{"access_token":"shpat_fake_admin_token","scope":"read_orders"}"""

  /** When non-null, the OAuth endpoint returns this status with [oauthAccessTokenResponse] as the body. */
  @Volatile
  var oauthStatus: HttpStatusCode = HttpStatusCode.OK

  /** Recorded OAuth token-exchange POSTs (raw JSON bodies). */
  val oauthCalls: MutableList<String> = CopyOnWriteArrayList()

  private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = 0) {
      routing {
        post("/admin/api/{version}/graphql.json") {
          val raw = call.receiveText()
          val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
          val op = parsed?.get("operationName")?.jsonPrimitive?.contentOrNull.orEmpty()
          val vars = parsed?.get("variables") ?: JsonNull
          val token = call.request.headers["X-Shopify-Access-Token"]
          calls.add(RecordedCall(op, vars, token, raw, call.request.local.uri.substringBefore('?')))
          val response =
            dequeueResponse(op)
              ?: responses[op]
              ?: CannedResponse("""{"data":null,"errors":[{"message":"no stub for $op"}]}""")
          response.headers.forEach { (name, value) -> call.response.header(name, value) }
          call.respondText(response.body, ContentType.Application.Json, response.status)

        }
        post("/admin/oauth/access_token") {
          oauthCalls.add(call.receiveText())
          call.respondText(oauthAccessTokenResponse, ContentType.Application.Json, oauthStatus)
        }
      }
    }

  fun start(): Int {
    server.start(wait = false)
    return runBlocking { server.engine.resolvedConnectors().first().port }
  }

  fun stop() {
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  override fun clear() {
    calls.clear()
    responses.clear()
    responseQueues.clear()
    oauthCalls.clear()
    oauthAccessTokenResponse = """{"access_token":"shpat_fake_admin_token","scope":"read_orders"}"""
    oauthStatus = HttpStatusCode.OK
  }

  /**
   * Stub a raw response body for [operationName]; [status] is what Shopify answers with instead of `200` when throttled
   * or refusing the token, and [headers] what it adds, such as its deprecation notice.
   */
  fun stubRaw(
    operationName: String,
    responseJson: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Map<String, String> = emptyMap(),
  ) {
    responses[operationName] = CannedResponse(responseJson, status, headers)
  }

  /** Stub a sequence of raw responses for [operationName]; each call dequeues the next entry. */
  fun stubSequence(operationName: String, vararg responseJson: String) {
    responseQueues[operationName] = ArrayDeque(responseJson.map { CannedResponse(it) })
  }

  /** Enqueue a response served before any static [stubRaw] / [stubData] stub for [operationName]. */
  fun enqueueResponse(operationName: String, responseJson: String) {
    responseQueues.getOrPut(operationName) { ArrayDeque() }.addLast(CannedResponse(responseJson))
  }

  private fun dequeueResponse(operationName: String): CannedResponse? =
    responseQueues[operationName]?.removeFirstOrNull()

  /** Stub `{"data": <serialized payload>}` for [operationName] from a typed payload. */
  fun <T : Any> stubData(operationName: String, payload: T, serializer: KSerializer<T>) {
    val dataJson = json.encodeToJsonElement(serializer, payload)
    val response = buildJsonObject {
      put("data", dataJson)
    }
    responses[operationName] = CannedResponse(response.toString())
  }

  /** The typed twin of [stubData] for a multi-call flow: each entry answers one call, in order. */
  fun <T : Any> stubDataSequence(operationName: String, serializer: KSerializer<T>, vararg payloads: T) {
    responseQueues[operationName] = ArrayDeque(
      payloads.map { payload ->
        CannedResponse(buildJsonObject { put("data", json.encodeToJsonElement(serializer, payload)) }.toString())
      },
    )
  }


  fun shopUrl(version: String = Config.SHOPIFY_API_VERSION): String =
    "http://localhost:${runBlocking { server.engine.resolvedConnectors().first().port }}/admin/api/$version/graphql.json"
}
