# The install page says what happened and links back

Depends on: nothing.
Repos: the DSS only. No contract change, and no monolith change: the page links to `/retailer-portal`, which exists today.
It belongs with the monolith's `specs/guide-new-retailers-from-the-dashboard`, whose guide and store list hand out the install link
that ends on this page, but neither needs the other to land first.
Parallel: `020` (the look) can be built beside this one; it restyles what this spec renders.


## Why

A store connects when someone who can install apps on the Shopify store opens `{DSS_BASE_URL}/install?shop=…`
and approves the app. Shopify then sends them to `/oauth/callback`, and `renderOAuthInstallPage` shows them a report written for us:
"App installed", "Shopify token saved via monolith (store_id=…) and cached in memory", the webhook callback URL,
a table of webhook topics and subscription ids, the access scope handles.
The person reading it is a merchant, a developer or an agency, and the page does not tell them, in their words,
whether it worked, what happens now, or where to go next. There is no link anywhere on it.

This spec keeps the report, for support, and puts a page for the merchant above it.


## Behavioral contract

- **An outcome, derived from the report alone.** A pure function `ShopInstallReport.outcome(): ShopInstallOutcome` in `domain/`
  sorts every install into one of four, checked in this order:
  1. `UnknownStore`: the monolith refused the token with `404` (`MonolithPersistOutcome.Failed(httpStatus = 404)`):
     no store in DropNext has this Shopify address.
  2. `NotSaved`: any other `MonolithPersistOutcome.Failed`, a transport failure included.
  3. `ConnectedWithGaps(missingScopes, failedTopics)`: the token is saved, but the grant lacks a scope of `ShopifyAccessScope`
     (`ShopifyAccessScopeReport.missing`), or a webhook topic is `Unsuccessful` (`WebhookRegistrationReport.failures`).
  4. `Connected`: the token is saved, every scope is granted, and no topic failed.
  
  A failed product count or shop id lookup does not change the outcome: the install itself did not depend on them.
  Stale or obsolete subscriptions do not either: the registration reports and handles them.
- **The page for the merchant comes first**: a heading, a few sentences, and, for the outcomes that need it, a block saying what is wrong.
  The copy per outcome is below.
- **A way back.** Every outcome has a button, *Continue to DropNext*, linking to the retailer portal's root,
  `{portal origin}/retailer-portal`, in the same tab.
  The monolith redirects that to the dashboard, a user who is not signed in to the sign-in page first,
  and a retailer without a supplier order on to the onboarding guide.
  For a merchant who installed the app for someone else, the page also says they can close the tab.
- **The portal origin is derived, not configured.** `Config` gains `portalBaseUrl`: the scheme, host and port of `MONOLITH_BASE_URL`,
  without its path (`https://staging.dropnext.com/api/shopify-service/v1` gives `https://staging.dropnext.com`),
  as the monolith serves its portals and its DSS API on one host. No new environment variable.
  The path `/retailer-portal` is a monolith path the DSS now names, so it is a constant in `OutBoundMonolithPortalPaths` (`lib/monolith/`),
  next to the generated `OutBoundMonolithPaths` but hand-written, as no contract describes a browser page.
- **The report stays, collapsed.** Everything the page shows today, except its "App installed" heading, moves unchanged
  into a `<details>` element with the summary *Details for DropNext support*.
  It is closed for `Connected` and open for every other outcome, so whoever reads the page to support has it in front of them.
- **A reference for support.** The request's trace id (`currentTraceId()`, the one every log line of this callback carries)
  is shown at the bottom of the page for every outcome, as *Reference for support: `<trace id>`*,
  so a support request names the log lines to look up. The handler reads it and passes it to the renderer:
  `presentation` imports nothing but `domain`.
- **Nothing else about the callback changes**: its checks, its plain-text errors before the exchange,
  `Cache-Control: no-store`, the `noindex` robots meta, the `200` status for every outcome after the exchange.
  The token is still cached in memory when the monolith did not save it; the page no longer says so, the details still do.


## The copy

`acme-outdoor.myshopify.com` stands for `ShopDomain.normalizedShopifyHost`, `1,234` for the product count.
The `<title>` is the heading.

### Connected

**DropNext is connected to acme-outdoor.myshopify.com**

> We are now copying your products from Shopify. This can take a while for a large catalog.
> We found 1,234 products in your shop.
> From now on, orders placed in your Shopify store reach DropNext by themselves.
>
> If you installed the app for someone else, you can close this tab.

*Continue to DropNext*

The product line reads "at least 1,234 products" when Shopify's count is a lower bound (`ProductCount.isExact` false),
and is left out when the count failed.

### Connected with gaps

**DropNext is connected to acme-outdoor.myshopify.com, but not completely**

> We are now copying your products from Shopify, but not everything DropNext needs is in place:

Then one item per gap:
- **A missing scope:** "Shopify did not grant DropNext all the access it asked for, so some orders or fulfillments will fail.
  Open the install link again and approve the access Shopify asks for."
- **A failed topic:** "Shopify did not accept every notification DropNext needs, so some products or orders may not reach DropNext.
  Please contact DropNext support at support@dropnext.com."

> The details below say exactly what is missing.

*Continue to DropNext*

### Unknown store

**DropNext does not know acme-outdoor.myshopify.com yet**

> Shopify approved the app, but there is no store with this Shopify address in DropNext, so nothing was connected.
> Add the store in DropNext under *Settings › Stores*, check that its Shopify address is acme-outdoor.myshopify.com,
> and then open the install link again.

*Continue to DropNext*

### Not saved

**DropNext could not save the connection to acme-outdoor.myshopify.com**

> Shopify approved the app, but DropNext could not save the connection.
> Please open the install link again in a few minutes.
> If that does not help, contact DropNext support at support@dropnext.com, and mention the reference below.

*Continue to DropNext*


## What changes

- `domain/ShopInstallOutcome.kt`: the sealed `ShopInstallOutcome` (`Connected`, `ConnectedWithGaps`, `UnknownStore`, `NotSaved`)
  and `ShopInstallReport.outcome()`. Named after "what a view renders is an `*Outcome`".
- `boot/config/Config.kt`: `portalBaseUrl`, derived from `monolithBaseUrl` where the latter is already parsed and checked.
- `lib/monolith/OutBoundMonolithPortalPaths.kt`: `retailerPortalRoot = "/retailer-portal"`.
  `ArchitectureTest`'s rule against a hand-written `OutBoundMonolithPaths` matches that exact file name, so this needs no allowlist entry.
- `handler/OAuthHandlers.kt`: takes the portal URL as a constructor parameter (built in `dependencies.kt` from
  `config.portalBaseUrl` + `OutBoundMonolithPortalPaths.retailerPortalRoot`),
  and passes it with `currentTraceId()` to the renderer.
- `presentation/renderOAuthInstallPage.kt`: `renderOAuthInstallPage(report, portalUrl, traceId)`;
  the merchant's part per outcome, then the existing blocks inside `<details>`, then the reference.
  The existing `render*` functions stay as they are.
- `CLAUDE.md`, "OAuth install flow": one sentence on the merchant's part, the outcome, and the link back,
  and that the DSS names one monolith page (`OutBoundMonolithPortalPaths`).


## Edge cases

- **The monolith answered `404` for an unknown store**: `UnknownStore`, which tells the merchant to add the store first.
  This is the likeliest failure for a new retailer, whose guide hands out the link before or after adding the store.
- **The monolith did not answer at all** (`httpStatus` null): `NotSaved`.
- **A missing scope and a failed topic at once**: `ConnectedWithGaps` with both items.
- **The token was not saved and a scope is missing**: `NotSaved` or `UnknownStore`; the saving comes first,
  as a missing scope cannot be fixed before the store is connected. The details still list the scopes.
- **No trace id in the MDC** (never on this route, as `CallId` always sets one): the reference line is left out.
- **`MONOLITH_BASE_URL` points at a host the browser cannot reach** (a local `http://host.docker.internal:8080`):
  the button leads nowhere useful in that development setup. Production and staging serve both on one public host.
- **A store installed again** (the reinstall work's "Reconnect" link) ends on the same page; `Connected` reads right for it too.


## Tests to write

- `ShopInstallOutcomeTest` (pure): each of the four outcomes from a minimal report; the order of precedence
  (`404` with a missing scope is `UnknownStore`, a transport failure with a failed topic is `NotSaved`);
  a failed product count with everything else fine is `Connected`.
- `RenderOAuthInstallPageTest`:
  - per outcome: its heading in `<h1>` and `<title>`, and a sentence of its copy;
  - every outcome links *Continue to DropNext* to the portal URL it was given;
  - `<details>` is closed for `Connected` and open for the other three, and holds what the page showed before
    (the existing tests keep their assertions, now inside it);
  - the reference line shows the trace id, and is absent without one;
  - the product line: exact, a lower bound, and absent when unknown;
  - the existing "App installed" heading test is replaced, not kept.
- `ConfigTest`: `portalBaseUrl` drops the path of `MONOLITH_BASE_URL` and keeps a port (`http://localhost:8080/api` gives `http://localhost:8080`).
- `OAuthHandlersTest`: a successful callback's page links to the configured portal, and shows the call's trace id.


## Decisions

1. **The target is `/retailer-portal`** for everyone: a supplier never installs the app.
2. **The portal's origin is derived from `MONOLITH_BASE_URL`**; there is no `MONOLITH_PORTAL_URL`.
3. **The merchant's part names `support@dropnext.com`** where it asks to contact support (gaps and not saved),
   beside the reference: a merchant who reads "contact support" wants to know how, and the reference alone does not say.
   The address is the one the monolith's e-mail footer uses, and the page's footer (`020`) links it as well.
