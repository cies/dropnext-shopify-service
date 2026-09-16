package dropnext.dss.lib.ktor

import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.TEST_MONOLITH_TO_DSS_API_KEY
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test


/**
 * The guard in front of every monolith-facing route. It is the only thing standing between the
 * public internet and the endpoints that write Shopify Admin tokens, so the cases that matter are
 * the near-misses: a header that looks almost right must not pass.
 *
 * `PUT /stores/api-key` is the probe because its handler forwards the token to the monolith, which
 * the [FakeMonolithService] records: a refusal that only answered `401` would look the same as one
 * that answered `401` after the handler had already cached and forwarded the token.
 */
class InstallMonolithWebhookAuthTest {

  private val monolith = FakeMonolithService()

  @Test
  fun `the exact secret with the Bearer prefix reaches the handler`() =
    probeWith("Bearer $TEST_MONOLITH_TO_DSS_API_KEY", ::assertReachedTheHandler)

  /** RFC 6750: a refused bearer token is a bare 401 with the `WWW-Authenticate` challenge, nothing in the body. */
  @Test
  fun `a request without an Authorization header is refused with the bearer challenge`() = probeWith(authorization = null) { response ->
    assertRefused(response)
    assert(response.bodyAsText().isEmpty())
  }

  @Test
  fun `an empty Authorization header is refused`() = probeWith("", ::assertRefused)

  @Test
  fun `the bare secret without the Bearer prefix is refused`() = probeWith(TEST_MONOLITH_TO_DSS_API_KEY, ::assertRefused)

  /** The scheme is matched, so `Basic` never reaches the comparison. */
  @Test
  fun `the secret under the Basic scheme is refused`() = probeWith("Basic $TEST_MONOLITH_TO_DSS_API_KEY", ::assertRefused)

  /** RFC 7235 calls the scheme case-insensitive, and Ktor's provider follows it; the token itself is still exact. */
  @Test
  fun `a lowercase bearer prefix is accepted`() = probeWith("bearer $TEST_MONOLITH_TO_DSS_API_KEY", ::assertReachedTheHandler)

  /**
   * Equal length, so the constant-time comparison runs to the end rather than short-circuiting on a
   * length check — the case a timing-safe compare exists for.
   */
  @Test
  fun `a wrong secret of the same length is refused`() =
    probeWith("Bearer ${"x".repeat(TEST_MONOLITH_TO_DSS_API_KEY.length)}", ::assertRefused)

  /** Ktor's header parser trims the whitespace around the token, per RFC 7235; what is compared is the token alone. */
  @Test
  fun `a correct secret with trailing whitespace is accepted`() =
    probeWith("Bearer $TEST_MONOLITH_TO_DSS_API_KEY ", ::assertReachedTheHandler)

  @Test
  fun `a prefix of the correct secret is refused`() =
    probeWith("Bearer ${TEST_MONOLITH_TO_DSS_API_KEY.dropLast(1)}", ::assertRefused)

  @Test
  fun `the correct secret with one character appended is refused`() =
    probeWith("Bearer ${TEST_MONOLITH_TO_DSS_API_KEY}x", ::assertRefused)

  // ---------- helpers ----------

  private fun probeWith(authorization: String?, assertions: suspend (HttpResponse) -> Unit) =
    withDssApp(testDependencies(monolith = monolith)) { client ->
      assertions(client.putStoreApiKey(authorization))
    }

  /** The guard let the request through: the handler ran, and it ran with the body that was sent. */
  private fun assertReachedTheHandler(response: HttpResponse) {
    assert(response.status == HttpStatusCode.OK)
    assert(monolith.putStoreApiKeyCalls.single().shopifySubdomain == "acme")
  }

  /** The guard answered instead of the handler, and said how to authenticate. */
  private fun assertRefused(response: HttpResponse) {
    assert(response.status == HttpStatusCode.Unauthorized)
    assert(response.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
    assert(monolith.putStoreApiKeyCalls.isEmpty())
  }

  private suspend fun HttpClient.putStoreApiKey(authorization: String?): HttpResponse =
    put(Paths.storesApiKey) {
      authorization?.let { header("Authorization", it) }
      contentType(ContentType.Application.Json)
      setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 1L))
    }
}
