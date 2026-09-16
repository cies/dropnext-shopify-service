package dropnext.dss.lib.ktor

import dropnext.dss.contract.ApiError
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.slf4j.METHOD_MDC_KEY
import dropnext.dss.lib.slf4j.ROUTE_MDC_KEY
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.mdcOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * What the one exception-to-answer mapping guarantees: Ktor's own request-shape exceptions stay 400s
 * instead of falling into the catch-all, a missing query parameter names itself (in plain text on the
 * paths a browser reads), and the catch-all logs the path without the query string, where the OAuth
 * callback's `code` and `hmac` travel.
 */
class InstallStatusPagesTest {

  private fun throwingApp(
    cause: Throwable,
    plainTextErrorPaths: Set<String> = emptySet(),
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      installStatusPages(plainTextErrorPaths)
      installJsonContentNegotiation()
      routing { get("/boom") { throw cause } }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.errorResponse(path: String): Pair<HttpStatusCode, ApiError> {
    val client = createClient { install(ClientContentNegotiation) { json(AppJson) } }
    val response = client.get(path)
    return response.status to response.body<ApiError>()
  }

  @Test
  fun `a body Ktor could not decode is a 400, not a 500`() = throwingApp(BadRequestException("Failed to convert request body")) {
    val (status, error) = errorResponse("/boom")
    assert(status == HttpStatusCode.BadRequest)
    assert(error.error == "invalid request body: Failed to convert request body")
  }

  @Test
  fun `a failed request validation is a 400 listing every reason`() =
    throwingApp(RequestValidationException(Any(), listOf("first reason", "second reason"))) {
      val (status, error) = errorResponse("/boom")
      assert(status == HttpStatusCode.BadRequest)
      assert(error.error == "first reason; second reason")
    }

  @Test
  fun `a missing query parameter is a 400 naming the parameter`() =
    throwingApp(MissingRequestParameterException("shop")) {
      val (status, error) = errorResponse("/boom")
      assert(status == HttpStatusCode.BadRequest)
      assert(error.error == "Missing shop")
    }

  @Test
  fun `a missing query parameter on a plain-text path is answered in plain text`() =
    throwingApp(MissingRequestParameterException("shop"), plainTextErrorPaths = setOf("/boom")) {
      val response = client.get("/boom")
      assert(response.status == HttpStatusCode.BadRequest)
      assert(response.contentType()?.withoutParameters() == ContentType.Text.Plain)
      assert(response.bodyAsText() == "Missing shop")
    }

  @Test
  fun `a body over the limit is a 413, not a 500`() = throwingApp(PayloadTooLargeException(MAX_REQUEST_BODY_BYTES)) {
    val (status, error) = errorResponse("/boom")
    assert(status == HttpStatusCode.PayloadTooLarge)
    assert(error.error == "request body too large")
  }

  @Test
  fun `anything else is the generic 500`() = throwingApp(IllegalStateException("the database is on fire")) {
    val (status, error) = errorResponse("/boom")
    assert(status == HttpStatusCode.InternalServerError)
    assert(error.error == "internal error")
  }

  /** A bug on the OAuth callback used to answer a JSON envelope to a merchant's browser while a missing parameter answered text. */
  @Test
  fun `the generic 500 on a plain-text path is answered in plain text`() =
    throwingApp(IllegalStateException("the database is on fire"), plainTextErrorPaths = setOf("/boom")) {
      val response = client.get("/boom")
      assert(response.status == HttpStatusCode.InternalServerError)
      assert(response.contentType()?.withoutParameters() == ContentType.Text.Plain)
      assert(response.bodyAsText() == "internal error")
    }

  @Test
  fun `a body over the limit on a plain-text path is answered in plain text`() =
    throwingApp(PayloadTooLargeException(MAX_REQUEST_BODY_BYTES), plainTextErrorPaths = setOf("/boom")) {
      val response = client.get("/boom")
      assert(response.status == HttpStatusCode.PayloadTooLarge)
      assert(response.contentType()?.withoutParameters() == ContentType.Text.Plain)
      assert(response.bodyAsText() == "request body too large")
    }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the unhandled-error line names the method and the path but never the query string`() {
    val lines = capturingLogs {
      throwingApp(IllegalStateException("boom")) {
        client.get("/boom?code=super-secret-code&hmac=deadbeef&shop=acme.myshopify.com")
      }
    }

    val line = lines.single { "Unhandled error" in it }
    assert("GET /boom" in line)
    assert("super-secret-code" !in line)
    assert("deadbeef" !in line)
    assert("code=" !in line)
  }

  /** Which route threw is the first question of an incident; the line answers it as a field, still without the query string. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the unhandled-error line carries the route and the method in its MDC`() {
    val lines = capturingLogs {
      testApplication {
        application {
          // In the order `dssModule` installs them: the error handler logs under the call logging's MDC.
          installCallLogging(enabled = false)
          installStatusPages(plainTextErrorPaths = emptySet())
          installJsonContentNegotiation()
          routing { get("/boom") { throw IllegalStateException("boom") } }
        }
        client.get("/boom?code=super-secret-code")
      }
    }

    val mdc = mdcOf(lines.single { "Unhandled error" in it })
    assert(mdc[ROUTE_MDC_KEY] == "/boom")
    assert(mdc[METHOD_MDC_KEY] == "GET")
  }
}
