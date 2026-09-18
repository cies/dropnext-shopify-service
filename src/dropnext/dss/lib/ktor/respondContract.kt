package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.ContractJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationStrategy


/**
 * Answers a monolith call with a contract body, encoded by [ContractJson] rather than by the `ContentNegotiation` the
 * application installs.
 *
 * `call.respond(body)` would reach for the installed [dropnext.dss.lib.json.AppJson], which drops a `null` and so
 * leaves out keys the contract marks required; the monolith fails to decode those. Every body named in
 * `src/resources/monolith-dss-openapi.json` goes out through here, so that stays true of the ones added next.
 *
 * The serializer is passed rather than reified, because the service resolves none by reflection.
 */
suspend fun <T> ApplicationCall.respondContract(
  serializer: SerializationStrategy<T>,
  body: T,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  respondText(ContractJson.encodeToString(serializer, body), ContentType.Application.Json, status)
}
