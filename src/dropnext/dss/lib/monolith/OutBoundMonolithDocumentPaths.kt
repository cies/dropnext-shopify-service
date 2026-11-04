package dropnext.dss.lib.monolith


/**
 * Monolith paths that are not operations of its OpenAPI document, so `generateOutBoundMonolithPaths` cannot emit them:
 * the document does not list the route it is served from. Relative to the same root as [OutBoundMonolithPaths], which
 * the deployed `MONOLITH_BASE_URL` already ends in.
 */
@Suppress("ConstPropertyName") // Spelled like the generated paths beside it.
object OutBoundMonolithDocumentPaths {
  /** `GET` — the monolith's DSS-facing OpenAPI document, the one `src/resources/monolith-dss-openapi.json` copies. */
  const val openApiJson = "/openapi.json"
}
