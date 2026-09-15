package dropnext.dss

import dropnext.dss.boot.config.DssMode
import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.boot.warmup.startWarmUp
import dropnext.dss.lib.ktor.installCallId
import dropnext.dss.lib.ktor.installCallLogging
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.lib.ktor.installMonolithWebhookAuth
import dropnext.dss.lib.ktor.installRequestBodyLimit
import dropnext.dss.lib.ktor.installRequestValidation
import dropnext.dss.lib.ktor.installStatusPages
import dropnext.dss.path.Paths
import dropnext.dss.routing.diagnosticsRoutes
import dropnext.dss.routing.monolithWebhookRoutes
import dropnext.dss.routing.oauthRoutes
import dropnext.dss.routing.shopifyWebhookRoutes
import dropnext.dss.routing.webhookSubscriptionRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.routing.routing


/**
 * The whole Ktor application: plugins, then every route family.
 *
 * The one-composition root, so a request → response test under
 * `testApplication { application { dssModule(deps, WarmUp.NONE) } }`
 * runs the exact stack production runs (same auth guard, same error shaping, same trace ids). [warmUp] is what
 * `/health` waits for before it says the task may take traffic: the graph's own in production, none under test.
 */
fun Application.dssModule(deps: DssDependencies, warmUp: WarmUp) {
  // The graph's HTTP clients live as long as the application: `main`'s server stop and `testApplication`'s
  // teardown both end here.
  monitor.subscribe(ApplicationStopped) { deps.close() }
  startWarmUp(deps.readiness, warmUp)

  installCallId()
  installCallLogging(enabled = deps.config.mode == DssMode.DEV)
  installRequestBodyLimit()
  installStatusPages(plainTextErrorPaths = setOf(Paths.install, deps.config.oauthRedirectPath))
  installJsonContentNegotiation()
  installRequestValidation()
  installMonolithWebhookAuth(deps.config.monolithToDssApiKey)

  routing {
    diagnosticsRoutes(handlers = deps.diagnosticsHandlers)
    webhookSubscriptionRoutes(handlers = deps.webhookSubscriptionHandlers)
    oauthRoutes(
      handlers = deps.oauthHandlers,
      oauthCallbackPath = deps.config.oauthRedirectPath,
    )
    shopifyWebhookRoutes(handlers = deps.shopifyWebhookHandlers)
    monolithWebhookRoutes(handlers = deps.monolithWebhookHandlers)
  }
}
