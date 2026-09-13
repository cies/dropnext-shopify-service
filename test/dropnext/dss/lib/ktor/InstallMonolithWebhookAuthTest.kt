package dropnext.dss.lib.ktor

import dropnext.dss.DssDependencies
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.dssDependencies
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.testConfig
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


private val SECRET = "s".repeat(32)


/**
 * The guard in front of every monolith-facing route. It is the only thing standing between the
 * public internet and the endpoints that write Shopify Admin tokens, so the cases that matter are
 * the near-misses: a header that looks almost right must not pass.
 *
 * `PUT /stores/api-key` is the probe because it answers `200` once the guard lets it through, which
 * separates "the guard passed" from "the guard rejected" without reading the body.
 */
class InstallMonolithWebhookAuthTest {

  @Test
  fun `the exact secret with the Bearer prefix reaches the handler`() = withDssApp(deps()) { client ->
    val r = client.putStoreApiKey("Bearer $SECRET")
    assert(r.status == HttpStatusCode.OK)
  }

  /** RFC 6750: a refused bearer token is a bare 401 with the `WWW-Authenticate` challenge, nothing in the body. */
  @Test
  fun `a request without an Authorization header is refused with the bearer challenge`() = withDssApp(deps()) { client ->
    val r = client.putStoreApiKey(authorization = null)
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
    assert(r.bodyAsText().isEmpty())
  }

  @Test
  fun `an empty Authorization header is refused`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("").status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `the bare secret without the Bearer prefix is refused`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey(SECRET).status == HttpStatusCode.Unauthorized)
  }

  /** The scheme is matched, so `Basic` never reaches the comparison. */
  @Test
  fun `the secret under the Basic scheme is refused`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("Basic $SECRET").status == HttpStatusCode.Unauthorized)
  }

  /** RFC 7235 calls the scheme case-insensitive, and Ktor's provider follows it; the token itself is still exact. */
  @Test
  fun `a lowercase bearer prefix is accepted`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("bearer $SECRET").status == HttpStatusCode.OK)
  }

  /**
   * Equal length, so the constant-time comparison runs to the end rather than short-circuiting on a
   * length check — the case a timing-safe compare exists for.
   */
  @Test
  fun `a wrong secret of the same length is refused`() = withDssApp(deps()) { client ->
    val wrong = "x".repeat(SECRET.length)
    assert(client.putStoreApiKey("Bearer $wrong").status == HttpStatusCode.Unauthorized)
  }

  /** Ktor's header parser trims the whitespace around the token, per RFC 7235; what is compared is the token alone. */
  @Test
  fun `a correct secret with trailing whitespace is accepted`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("Bearer $SECRET ").status == HttpStatusCode.OK)
  }

  @Test
  fun `a prefix of the correct secret is refused`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("Bearer ${SECRET.dropLast(1)}").status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `the correct secret with one character appended is refused`() = withDssApp(deps()) { client ->
    assert(client.putStoreApiKey("Bearer ${SECRET}x").status == HttpStatusCode.Unauthorized)
  }

  // ---------- helpers ----------

  private suspend fun HttpClient.putStoreApiKey(authorization: String?): HttpResponse =
    put(Paths.storesApiKey) {
      authorization?.let { header("Authorization", it) }
      contentType(ContentType.Application.Json)
      setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", apiKey = "shpat_x", shopifyShopId = 1L))
    }

  private fun deps(): DssDependencies = dssDependencies(
    config = testConfig(monolithToDssApiKey = SECRET),
    monolithService = FakeMonolithService(),
  )
}
