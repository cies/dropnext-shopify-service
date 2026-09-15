# Distribution: custom today, and what going public takes

Status: record of the decision, checked against Shopify's documentation on 2026-09-16.
Author: cies (with Claude)
Repos: none by itself; `010` and `020` hold the work.


## Decision

The DropNext Shopify app is custom-distributed: it is not in the Shopify App Store, neither listed nor unlisted. While
that holds, `010` and `020` are not built. They stay as the record of what going public takes.


## What custom distribution means

- **Installs.** "Installed on a single Shopify store, on multiple stores that belong to the same Plus organization, or
  on transfer-disabled development stores." An app created before 2023-07-26 needs Partner Support to allow more than one
  store. One custom app therefore cannot serve retailers whose stores are unrelated: each would need an app of its own,
  with its own client id and secret, and the DSS is configured with one (`SHOPIFY_APP_CLIENT_ID`,
  `SHOPIFY_APP_CLIENT_SECRET`, which also verifies every webhook's HMAC).
- **Compliance webhooks.** Required of apps distributed through the App Store, including unlisted ("limited visibility")
  ones; a custom app is outside that scope. See `020`.
- **Expiring offline tokens.** Required of public apps; "This doesn't apply to custom apps." See `010`.
- **Protected customer data.** Level 1 and Level 2 (name, address, phone, email) are "Always available" to a custom app;
  a public app requests the fields and passes a review. Without access, Shopify redacts a field to `null`, or answers an
  unapproved type with a `200` carrying an `errors` entry.
- **What applies anyway.** Shopify's API Terms, section 6.2.3, restrict nothing to public apps (unlike, for example,
  2.3.17 and 2.3.22, which say "only applies to Public Applications"): "except where prohibited or varied by applicable
  law, delete all originals, copies and reproductions of the Merchant Data within 30 days when (A) the Merchant
  uninstalls the Application, (B) it is no longer required to provide the services of your Application to the Merchant
  to whom the Merchant Data relates, as may be described in the applicable Merchant Agreement or Developer Privacy
  Policy, (C) it is no longer required to provide the Platform Services, or (D) you receive an enforceable request to
  delete data from a Merchant, a Customer or Shopify."
  - "Merchant Data" is "information (including Personal Information) relating to a Merchant or Merchant Store,
    including business, financial and product information and any Customer Data": the orders, the products, the
    token, not only the customer's address and email.
  - "Enforceable request" is not defined. The laws the Terms name (GDPR, ePrivacy, PIPEDA, the FTC Act, COPPA) suggest a
    request a data protection law entitles someone to make, such as an erasure request under GDPR.
  - "Except where prohibited or varied by applicable law" is where bookkeeping retention comes in.
  - Without the webhooks, a request under (D) reaches DropNext another way (the merchant, Shopify), and the monolith is
    not told of an uninstall (A) at all today (see `020`, `app/uninstalled`). Nothing automates either.
- **Creating custom apps.** Since 2026-01-01 custom apps can no longer be created in the Shopify admin; that concerns
  merchant-created apps, not this one, which does OAuth and is managed in the Partner (Dev) Dashboard.


## Going public

- **It is a new app.** "You can't change the distribution method after you select it"; Shopify staff, 2025-07-14: "You
  need to create a new app and select public distribution." The new app has its own client id and secret, so every shop
  installs it and the old app is uninstalled.
- **`010` shrinks.** A public app created after 2026-04-01 must use expiring offline tokens from its first install, so
  every token of the new app is obtained with `expiring: 1`: the exchange of non-expiring tokens (`010`, "Migrating" and
  rollout step 3) is not needed. The refresh, and its single owner, are.
- **`020` comes first.** The compliance topics are declared in the new app's configuration, and the DSS handlers and the
  monolith's recording and jobs work, before the app is submitted: the privacy documentation says an app without them is
  rejected. (The current App Store requirements page no longer lists them as a review item; the privacy page still does.)
- **Protected customer data review.** The Level 2 fields `GetOrderForDss` reads (shipping name, address, phone, email)
  are requested in the Partner Dashboard with the data protection details, and reviewed.
- **The move.** While shops move from the old app to the new one, the DSS serves both: two client ids for OAuth, two
  secrets for HMAC verification, and a token per store that belongs to one app or the other. Config holds one of each
  today (open question 1).
- **Order:** `020` (monolith, DSS, app configuration) and `010` (refresh only) → the public app and its configuration →
  review → shops install the new app → the old app is uninstalled everywhere.


## Open questions for the human developer

1. The move: one DSS serving both apps for a while (two credential sets in `Config`, the webhook route telling the apps
   apart), or a second DSS deployment for the new app with its own callback URL?
2. Until then: how many unrelated retailer stores will one custom app need to reach? Beyond one store (or one Plus
   organization) the choice is an app per retailer, which the DSS does not support, or going public.


## Sources

- [Shopify: app distribution](https://shopify.dev/docs/apps/launch/distribution)
- [Shopify: select a distribution method](https://shopify.dev/docs/apps/launch/distribution/select-distribution-method)
- [Shopify: App Store visibility](https://shopify.dev/docs/apps/launch/distribution/visibility)
- [Shopify developer community: app locked to custom distribution](https://community.shopify.dev/t/app-locked-to-custom-distribution-need-shopify-staff-to-enable-public-distribution/19062)
- [Shopify: protected customer data](https://shopify.dev/docs/apps/launch/protected-customer-data)
- [Shopify: privacy law compliance](https://shopify.dev/docs/apps/build/compliance/privacy-law-compliance)
- [Shopify: offline access tokens](https://shopify.dev/docs/apps/build/authentication-authorization/access-tokens/offline-access-tokens)
- [Shopify API Terms](https://www.shopify.com/legal/api-terms)
- [Shopify changelog: legacy custom apps can't be created after January 1, 2026](https://changelog.shopify.com/posts/legacy-custom-apps-can-t-be-created-after-january-1-2026)
