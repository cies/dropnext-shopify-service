package dropnext.dss.lib.monolith

import dropnext.dss.lib.json.MonolithJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive


/**
 * Parsed monolith error payload. [monolithTraceId] is the backend's `trace_id` (not DSS request trace).
 * [isContractEnvelope] says the body was the contract's `ApiError`, so [message] is the monolith's own words.
 * Anything else came from something in front of it, such as a router's `404` page or a proxy's error page.
 */
data class MonolithErrorBody(
  val message: String?,
  val code: String?,
  val monolithTraceId: String?,
  val isContractEnvelope: Boolean = false,
) {
  fun formatForLog(): String {
    val parts = mutableListOf<String>()
    monolithTraceId?.let { parts += "monolith_trace_id=$it" }
    code?.let { parts += "code=$it" }
    message?.let { parts += "message=${it.take(200)}" }
    return if (parts.isEmpty()) "monolith_error=unknown" else parts.joinToString(" ")
  }
}

/** The message for [MonolithError.Rejected] plus the parsed fields for logging. */
fun monolithError(httpStatus: Int, rawBody: String): Pair<String, MonolithErrorBody> {
  val parsed = parseMonolithErrorBody(rawBody)
  val msg = parsed.message ?: rawBody.trim().take(512).ifEmpty { "HTTP $httpStatus" }
  return msg to parsed
}

fun parseMonolithErrorBody(rawBody: String): MonolithErrorBody {
  val trimmed = rawBody.trim()
  if (trimmed.isEmpty()) {
    return MonolithErrorBody(message = null, code = null, monolithTraceId = null)
  }
  return try {
    when (val root = MonolithJson.parseToJsonElement(trimmed)) {
      is JsonObject -> parseApiError(root)
      else ->
        MonolithErrorBody(
          message = trimmed.take(200),
          code = null,
          monolithTraceId = null,
        )
    }
  } catch (_: Exception) {
    MonolithErrorBody(message = trimmed.take(200), code = null, monolithTraceId = null)
  }
}

/**
 * The monolith's `ApiError`: `{"error": "<message>", "code": "<name>", "trace_id": "<id>"}`, with
 * `error` the one field that is always there. Anything else under `error` is kept as the message
 * text, so an unexpected body still shows up in the log.
 */
private fun parseApiError(root: JsonObject): MonolithErrorBody {
  val errorElement = root["error"]
    ?: return MonolithErrorBody(message = null, code = null, monolithTraceId = null)
  val errorText = (errorElement as? JsonPrimitive)?.takeIf { it.isString }?.content
  return MonolithErrorBody(
    message = errorText ?: errorElement.toString().take(200),
    code = root["code"]?.jsonPrimitive?.content,
    monolithTraceId = root["trace_id"]?.jsonPrimitive?.content,
    isContractEnvelope = errorText != null,
  )
}
