# Spec 040: portal pages and actions

Depends on: `010` (the client and the DTOs), `030` (the status row and `checkStoreShopifyStatus`).
Repos: monolith only.
Parallel: the retailer page and the admin page are independent of each other.


## What changes

### Retailer portal

- **The stores list** (`listStoresPage`): the badge column reads the status row instead of `encoded_api_key`:
  `Connected` (green; a valid token, no flag), `Reconnect needed` (red; the flag is set), `Not connected` (gray; no
  token), `Unknown` (yellow; the last check got no answer). The `Store` DTO gains the status row's fields it needs
  (`selectStores` joins `store_shopify_statuses`).
- **The store edit page** (`updateStorePage`) gains a "Shopify connection" card under the form:
  - the status line (as the badge, plus the reason and since when, when flagged), when it was last checked and by
    what source;
  - the access scopes: one row per scope the install asks for, granted or missing, with what it is for. The list of
    scopes the install asks for comes from the status row's `granted` and `missing` together, so the monolith holds no
    copy of `ShopifyAccessScope`;
  - the webhook rows from `status_detail`, one per topic with its status badge, and the obsolete ones;
  - three actions, each a `POST` form with the authenticity token, for `ManageStores` (open question 3 in `000`):
    - **Check now**: `POST /settings/stores/{storeId}/shopify-status/check` runs `checkStoreShopifyStatus` and
      redirects back with a flash saying what it found;
    - **Re-register webhooks**: `POST /settings/stores/{storeId}/shopify-webhooks/register` calls
      `DssWebhookService.registerShopifyWebhooks`, then runs the check so the row reflects the result, and redirects back
      with the summary counts in the flash;
    - **Reconnect the DropNext app**: a link to `DssWebhookService.installUrl(subdomain)` opening in a new tab, with
      one sentence saying who has to open it (someone who can install apps on that Shopify store) and that Shopify
      will ask them to approve the app's access. Shown always: it is also how a never-connected store connects, and
      the way to a grant Shopify's screen calls an update. When the flag is set, the link is the primary action.
- **Paths**: `Paths.retailerPortal.settings.checkStoreShopifyStatus(storeId)` and
  `registerStoreShopifyWebhooks(storeId)`; handlers `CheckStoreShopifyStatusPostHandler` and
  `RegisterStoreShopifyWebhooksPostHandler` in `storeHandlers.kt`; the router binds them under `ManageStores`.
- **The notification** of `030` links to the store edit page, where the reconnect link is.

### Admin portal

- **A "Shopify stores" page**, `Paths.adminPortal.listShopifyStores` (`/shopify-stores`), in the menu beside
  retailers, for a new `AdminUserPermission.ViewShopifyStores` (implied by `ManageRetailers`; `ManageShopifyStores`
  for the actions, implying the view): every store of every retailer with the retailer's name, the subdomain, the badge,
  the missing scopes, the webhooks status, the last check, and per row the same three actions as the retailer page
  (admin paths of their own, binding the same workflow calls). A **Check all** button enqueues one
  `CheckStoreShopifyStatus` job (`050`) per connected store, and the page says the checks are running; the rows update
  on reload. Synchronous checks of every store would hold the request for the sum of the DSS round trips.
- **The retailer detail page** (`showRetailer`) lists the retailer's stores with the badge and a link to the admin
  page filtered to the retailer, so an admin looking at a retailer sees a broken store there.
- **The admin's notification**: none in this spec (open question 4 in `000`).

### Both portals

- The install link is never rendered from a value the DSS answered: it is built from `DSS_BASE_URL` and the store's
  subdomain, so a page renders it while the DSS is down and a DSS answer cannot put another host in a link.
- The pages never call the DSS on `GET`: everything shown comes from the status row; only the `POST` actions call it.


## Behavioral contract

- A store with no status row shows `Not connected` when `encoded_api_key` is null, and `Unknown` with "never checked"
  when it is not (a store connected before this change): the first check or the daily one (`050`) fills it.
- **Check now** on a store the DSS answers `401` for: the row says `missing`, the badge `Not connected`, the flash says
  the shop has no token and points at the reconnect link.
- **Re-register webhooks** on a store without a token: the flash says so; nothing is registered.
- **Every action answers within the DSS's own timeouts** (the shared client's), and a DSS that does not answer is a
  flash saying so, never a `500`.
- The actions are `POST` with the CSRF token; the reconnect link is a plain `GET` link to the DSS, which is the
  start of Shopify's OAuth and carries nothing of ours but the shop.


## Edge cases

- The retailer user is not the person who can install apps on the shop: the page says so under the link, and the link
  can be copied. Nothing more is possible from our side.
- A store whose flag was set by a `dss_report` and whose reason names a scope the install does not ask for (Shopify
  tightened a field): the scopes table shows a granted list without the scope and a missing list with it; the page
  says the reconnect will not add it and to contact DropNext. Detect: the missing scope is not in the row's
  `granted ∪ missing` from the last `install` or check.
- An admin acting on a store while its retailer's user does the same: both `POST`s run; the row ends with the later
  check. Fine.


## Reuse inventory

- `listStoresPage`, `updateStorePage`, `StoreForm`, `storeHandlers.kt`, `badge`, `card`, `simpleTable`,
  `formActions`, `hiddenAuthenticityTokenFieldFor`, `redirectTo` with a flash, `Authorize.userCan`.
- Admin: `listRetailers` page as the pattern for a cross-retailer table; `adminPortalMenu`.
- `030`'s `checkStoreShopifyStatus` and `DssWebhookService`.


## Test plan

- `ShopifyConnectionCardTest` (pure, the `kotlinx.html` render of the card for each badge state and the flagged state).
- `StoreShopifyStatusE2eTest` (retailer, with `FakeDssWebhookService`): the check `POST` updates the row and redirects
  with the flash; the register `POST` calls the fake and re-checks; a `ViewStores`-only user gets no buttons and a
  `403` on the `POST`s; the link on the page is the install URL for the store's subdomain.
- `ShopifyStoresAdminE2eTest`: the page lists stores across retailers; "Check all" enqueues one job per connected
  store; the per-row actions work under `ManageShopifyStores` and are refused under `ViewShopifyStores`.
- `ShopifyOrderRetryHandlerE2eTest` untouched.
