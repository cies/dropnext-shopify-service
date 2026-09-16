package dropnext.dss.testutil.fixture

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken


/**
 * The shop, the token and the two secrets the whole suite shares.
 *
 * Every one of these used to be re-declared per test class — the shop in twelve files, the app secret under five
 * different names, the monolith bearer as eight spellings of `"x".repeat(32)`. A test that signed with its own secret
 * and configured the app with another passed for the wrong reason, and there was no single place to look up what a
 * test shop is called. Name a value here when more than one class needs it; keep a literal in the test when the case
 * is *about* that literal, such as a wrong secret of the right length.
 */
internal val ACME_SHOP: ShopDomain = ShopDomain.parse("acme.myshopify.com")!!

/** A second shop, for the cases about telling two of them apart. */
internal val OTHER_SHOP: ShopDomain = ShopDomain.parse("other.myshopify.com")!!

/** What Shopify may report as the shop's canonical host when the OAuth callback named another. */
internal val CANONICAL_ACME_SHOP: ShopDomain = ShopDomain.parse("acme-canonical.myshopify.com")!!

/** The Admin token [ACME_SHOP] resolves to unless a test is about another one. */
internal val TEST_ADMIN_TOKEN: ShopifyAdminToken = ShopifyAdminToken("shpat_test")

/** The app secret every signed webhook body and OAuth callback query in the suite is signed with. */
internal const val TEST_APP_SECRET: String = "shpss_test_app_secret"

/** The bearer the monolith-facing routes expect. Thirty-two characters, the length `Config` requires of a real one. */
internal const val TEST_MONOLITH_TO_DSS_API_KEY: String = "monolith-to-dss-test-api-key-000"
