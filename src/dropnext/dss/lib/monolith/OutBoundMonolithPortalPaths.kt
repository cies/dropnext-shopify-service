package dropnext.dss.lib.monolith


/**
 * The monolith's browser pages the DSS links to, appended to `Config.portalBaseUrl`.
 * Hand-written, unlike `OutBoundMonolithPaths`: no contract describes a page.
 */
@Suppress("ConstPropertyName") // Less shouty field names, as in the generated paths.
object OutBoundMonolithPortalPaths {
  /** The monolith sends a signed-in retailer on to the dashboard, or the onboarding guide; anyone else to sign in. */
  const val retailerPortalRoot = "/retailer-portal"
}
