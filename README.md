DropNext Shopify Service
========================

A stateless service that bridges retailer Shopify stores to the DropNext platform (`dropnext-monolith`):
* OAuth install flow,
* catalog/order webhooks,
* typed Admin Graphql calls, and
* forwarding of order events to the [`dropnext-monolith`](../dropnext-monolith).


### The stack

The tech stack is minimalistic, optimized for development velocity and AI-readiness.
Its application layer runs on the JVM and primarily uses libraries from the Kotlin ecosystem.

* [Kotlin](https://kotlinlang.org)
* [Ktor](https://ktor.io/) —
Lightweight HTTP server (CIO engine) and client (with OkHttp engine). Used both as the inbound
server (OAuth, webhooks, DSS REST) and as the outbound client (Shopify Admin API, monolith forwarding).
That Kotlin's defacto standard Graphql stack builds on top of KTor is the main reason this project is not
part of `dropnext-monolith` (which builds on `http4k`): to avoid dependency hell.
* [graphql-kotlin](https://github.com/ExpediaGroup/graphql-kotlin) —
Compile-time-typed Graphql client. Queries live as `*.graphql` files in `src/resources/` and the
Gradle plugin generates typed Kotlin classes from them — schema drift fails the build instead of
the producing error at runtime.
* [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) —
Powerful and minimalistic (no reflection) lib for JSON. Used for both inbound Shopify payloads
and outbound monolith DTOs.
* [openapi-generator](https://github.com/OpenAPITools/openapi-generator) —
Generates Kotlin DTOs from [`src/resources/monolith-dss-openapi.json`](./src/resources/monolith-dss-openapi.json) (the checked-in copy of the canonical `DSS <-> monolith` contract).
Handwritten copies of those DTOs are explicitly forbidden by `ArchitectureTest`.
* [Konsist](https://docs.konsist.lemonappdev.com/) —
Kotlin-native architecture tests; enforces package-layer dependencies and the "no reflection" rule.


### Project goals

* Be a thin, stateless bridge between Shopify and the monolith — no database, no sessions; the only state is an
  in-memory cache of per-shop Admin tokens that the monolith is the durable store for.
* Discoverable and functional architecture (CTRL-click yourself to mastery).
* Quick developer cycles (fast recompiles, few dependencies, no JVM reflection).
* 12-factor principles (configuration by env vars).
* Test with **fakes**, not mocks (see [`.claude/rules/tests.md`](.claude/rules/tests.md)).

Non-goals:
* Multi-tenant session storage — the platform boundary is responsible for idempotency and dedup.
* Async/reactive/coroutines beyond what Ktor's CIO engine provides out of the box.


### Code map

All Kotlin lives under `src/dropnext/dss/` (we set `srcDir("src")` in Gradle to skip the Maven `src/main/kotlin/` prefix). Layer rules are enforced by [`ArchitectureTest`](test/dropnext/dss/ArchitectureTest.kt).

| Package | Role |
| ------- | ---- |
| [`app.kt`](src/dropnext/dss/app.kt) | `main` — reads config (env plus a local `.env`), builds the dependency graph, starts Ktor. |
| [`dssModule.kt`](src/dropnext/dss/dssModule.kt) | The one composition root: plugins and every route family; `main` and the request → response tests both install it. |
| [`dependencies.kt`](src/dropnext/dss/dependencies.kt) | Assembles `DssDependencies` (HTTP clients, services, handlers); tests override per-collaborator. |
| [`config/`](src/dropnext/dss/config) | `Config.from(env)`: every env var read once, secrets wrapped; plus the `.env` reader. |
| [`path/`](src/dropnext/dss/path) | URL path constants — `Paths` (inbound), `OutBoundMonolithPaths` (generated), `OutBoundShopifyOAuthPaths`. Single source of truth. |
| [`domain/`](src/dropnext/dss/domain) | The vocabulary every layer shares and that depends on nothing: `ShopDomain`, ids and secrets as value classes, the install report; `fulfillment/` holds the pure shipment ↔ fulfillment-order matcher, a package's content and the cancel plan, and request validation. |
| [`mapper/`](src/dropnext/dss/mapper) | Shopify snapshot → monolith contract DTO (order, product, money). |
| [`workflow/`](src/dropnext/dss/workflow) | Multi-step orchestration that doesn't touch HTTP types directly: shop install, order/product sync, shipment ↔ fulfillment sync, tracking events, webhook registration. |
| [`handler/`](src/dropnext/dss/handler) | Ktor handlers — `ApplicationCall → response`: decode, validate, resolve the shop, call a workflow, map its answer. |
| [`routing/`](src/dropnext/dss/routing) | Thin Ktor route bindings — one `Route.*Routes(handlers)` function per handler family. |
| [`presentation/`](src/dropnext/dss/presentation) | Pure view layer — a report in, HTML out. No Ktor/HTTP types. |
| [`lib/shopify/`](src/dropnext/dss/lib/shopify) | Shopify protocol primitives: GID helpers, `ShopifyOAuthService`, `ShopifyHmacVerifierService`, `ShopifyGraphqlService` (typed results) + its factory, the `ShopTokenStore`, webhook shop/id/topic parsers. |
| [`lib/monolith/`](src/dropnext/dss/lib/monolith) | Outbound monolith client: `MonolithService` interface answering `MonolithResult`, `HttpMonolithService` impl, error body parsing, structured failure logging. |
| [`lib/json/`](src/dropnext/dss/lib/json) | Shared `kotlinx.serialization` configs: `AppJson` (inbound) and `MonolithJson` (outbound). |
| [`lib/crypto/`](src/dropnext/dss/lib/crypto), [`lib/slf4j/`](src/dropnext/dss/lib/slf4j) | The one HMAC-SHA256 and constant-time compare; the MDC keys shared with the monolith, `currentTraceId()` and `withMdcEntries`. |
| [`lib/ktor/`](src/dropnext/dss/lib/ktor) | Ktor server glue: one `install*` function per plugin (`CallId` for the trace id, `CallLogging` with `callIdMdc` and the route, method and webhook MDC providers, `StatusPages`, JSON, `RequestValidation`, the `bearer` auth for the monolith), `DssError` helpers, shared HTTP client builders. |
| `src/resources/` | `.graphql` queries (compile-time-typed by the Gradle plugin), `logback.xml`. |
| `src/graphql-schema/` | Committed Shopify Admin schema (regenerated via `./gradlew graphqlIntrospectSchema`). |
| [`src/resources/monolith-dss-openapi.json`](src/resources/monolith-dss-openapi.json) | Checked-in copy of the canonical DSS ↔ monolith contract (the monolith serves it at `/openapi.json`). DTOs under `dropnext.dss.contract` and `OutBoundMonolithPaths` are **generated** from this. |


### Our preferred IDE

See [docs/setup-intellij-idea.md](./docs/setup-intellij-idea.md) for IntelliJ IDEA setup and troubleshooting.


### Running the project for development

1. Copy `.env.example` to `.env` and fill in the required Shopify credentials (the app reads `.env` itself, layered over the process environment).
2. Expose the server with HTTPS (e.g. [ngrok](https://ngrok.com/)) and set `DSS_BASE_URL` to that origin (no trailing slash). Shopify requires HTTPS for OAuth and webhook callbacks.
3. Run the app:

   ```sh
   ./gradlew run
   ```

4. Point your browser to `http://localhost:{PORT}/install?shop=your-dev-store.myshopify.com` to start the OAuth flow.
### Build deployable containers

```sh
docker build -t dropnext-shopify-service:local .
docker run --rm --env-file .env -p 8080:8080 dropnext-shopify-service:local
```

If you set `PORT` to a different value, publish the same port on both sides (`-p 9090:9090` when `PORT=9090`).

`docker-compose.yml` provides two profiles:

```sh
docker compose up --build app                            # standard
docker compose --profile local-monolith up app-local-monolith  # DSS_ALLOW_INSECURE_MONOLITH=true, dev-only
```


### Codegen

#### Graphql (Shopify Admin API)

The project targets Admin API **`2026-04`**. Keep these in sync when changing versions:

* `graphql.client.endpoint` in `build.gradle.kts`
* `graphql.config.yml` (IDE plugin URL, if present)
* `Config.SHOPIFY_API_VERSION` in `Config.kt` (not a setting: the generated client speaks exactly this version)

After bumping the version, run `./gradlew graphqlIntrospectSchema graphqlGenerateClient` and fix any schema drift.

The schema is committed at `src/graphql-schema/schema.graphql` (so the IDE plugin can find it).

#### OpenAPI (monolith DTOs)

Monolith request/response bodies are **generated** from [`src/resources/monolith-dss-openapi.json`](src/resources/monolith-dss-openapi.json)
(OpenAPI 3.1, spec validation enabled in Gradle). The same spec describes both directions: the monolith
endpoints DSS calls and the DSS endpoints the monolith calls.

```sh
./gradlew openApiGenerate
```

* Generator: `kotlin` + `jvm-ktor`
* Output: `build/generated/openapi/.../dropnext/dss/contract/` (`modelPackage` is derived from `monolithContractGeneratedDtoPath` in `build.gradle.kts`)
* Policy: **Models only** (`apis=false`) — HTTP stays in handwritten `HttpMonolithService`

Do not add handwritten copies of `CreateShopifyOrderRequest` or other spec DTOs.
If codegen fails, fix the schema in the monolith that serves it, refresh the copy, then re-run `openApiGenerate` (or any compile task).

The checked-in copy is byte-for-byte what a running monolith serves; refresh it from a local instance with

```sh
curl -sS -H "Authorization: Bearer $DSS_TO_MONOLITH_API_KEY" "http://127.0.0.1:8080/api/shopify-service/v1/openapi.json" > src/resources/monolith-dss-openapi.json
```

and never hand-edit it. The generator reads the served document as is (`generateOutBoundMonolithPaths` only strips
the monolith's mount root, `/api/shopify-service/v1`, off the path keys, and fails the build on a key outside it),
so what the monolith serves and what this repo checks in cannot drift apart.

Nothing refreshes the copy for you, so two checks notice when it has fallen behind:

* **At build time**, `./gradlew checkMonolithContractCopy` (part of `check`, so of `build`) compares the copy with the
  document the sibling `../dropnext-monolith` checkout last rendered (`./gradlew renderDssOpenApi` there writes
  `build/dss-openapi/openapi.json`). It prints which checkout, branch and revision it read and every difference, and
  fails only on a breaking one: a removed path, operation, schema or property, an added or removed enum value, a field
  or parameter whose `required` changed either way, a changed type, or another API root. Without the sibling, or before it has rendered the document, it
  skips with a reason. It never runs the monolith's build, never reaches the network and never rewrites the copy.
* **At startup**, the warm-up compares the copy with what the monolith serves and logs the drift (see "Warm-up and
  readiness" below).


### The JetBrains [Graphql IDE plugin](https://plugins.jetbrains.com/plugin/8097-graphql)

With this plugin, you get syntax + error highlighting and autocomplete on Graphql queries in IntelliJ IDEA.
It also lets you run queries against an endpoint directly from the IDE.

The plugin's introspection (downloading of the schema) does not work well with the Shopify Graphql API.
To mitigate, we use the Gradle plugin's introspection (the `graphqlIntrospectSchema` task) and
point the IDE plugin at the committed `src/graphql-schema/schema.graphql`.


### Shopify Partner app

* Create or use a Partner app and set **Allowed redirection URL(s)** to exactly `{DSS_BASE_URL}{OAUTH_REDIRECT_PATH}`
(default path `/oauth/callback`).
* The scopes the install requests are code, never a setting: `ShopifyAccessScope` in
`src/dropnext/dss/domain/ShopifyAccessScope.kt`. Changing them takes a deploy, and an installed shop keeps the scopes it
granted until it opens the install link again; the install page names any scope the merchant did not grant. Mirror
them in the Partner Dashboard
(see [access scopes](https://shopify.dev/docs/api/usage/access-scopes)):
  * `read_products` — loading a product for the product webhooks, and the product count on the install page
  * `read_orders` — loading an order for the order webhook, the shipment sync and tracking updates
  * `write_merchant_managed_fulfillment_orders` — seeing and fulfilling order lines at the merchant's own locations
  * `write_third_party_fulfillment_orders` — seeing and fulfilling order lines at locations a fulfillment service app manages
  * `write_fulfillments` — adding tracking events to a fulfillment, and canceling a fulfillment


### Hosted deployment

* **App URL**: `DSS_BASE_URL` must be the HTTPS origin Shopify uses to reach this service.
* **Listen address**: binds `0.0.0.0` so it works in containers and typical PaaS hosting.
* **Access tokens**: the server is **stateless** — it does not persist Admin API tokens.
Webhooks and internal REST routes resolve the per-shop Admin token through the in-memory cache (filled by OAuth or `PUT /stores/api-key`), falling back to a monolith `GET /stores` lookup, which is how a cold start gets its tokens back.
* **Warm-up and readiness**: a freshly started task pays the JVM's one-time costs before it says it can take traffic,
  because at a quarter vCPU the first outbound HTTPS call alone takes longer than a webhook's whole budget. It makes one
  store lookup on the monolith (for a placeholder shop it answers `404` for) and, once its socket is bound, two requests
  to itself over `127.0.0.1`: `GET /stores/shopify-status` and a
  self-signed delivery for `orders/updated` (a topic the service does not subscribe to) with webhook id `warm-up`, so the
  inbound pipeline has run once end to end.
  `/health` answers `503 {"status":"warming_up"}` meanwhile and `200 {"status":"ok"}` after, at the latest about
  twenty seconds after start whatever the upstreams did; the load balancer's `200` matcher keeps the task out of
  rotation until then while the task it replaces keeps serving. Two log lines, `Warm-up outbound done …` and
  `Warm-up inbound done …`, say what each step came to (info, or warn when a step failed). The warm-up changes
  nothing: no token is cached or dropped, the scan behind the check is read-only, the delivery is acknowledged without work.
* **Shared-secret checks**: two of those steps check the bearer secrets. The store lookup proves the monolith accepts
  `DSS_TO_MONOLITH_API_KEY` (any answer does, the `404` included). `GET /stores/shopify-status` proves only that our
  own bearer provider accepts the `MONOLITH_TO_DSS_API_KEY` it was configured with, which is a wiring check: the DSS
  cannot see the value the monolith holds, so a monolith with another key is not caught at startup. Only a `401` proves
  a key wrong, and then the task never reports ready: it logs
  `Warm-up refuses traffic: …` at error, writes the reason to stderr and exits with status `1`, so ECS replaces it and a
  rolling deployment keeps the old task serving. A `403`, a `5xx` or no answer fails the step without stopping the task,
  because a monolith that is down must not stop the service from serving the shops whose tokens it holds.
* **Contract drift**: once the task has reported ready, it fetches the monolith's `/openapi.json` and compares it with
  the checked-in copy, on the warm-up's trace id and bounded by the monolith client's timeout, so it never holds the
  gate. Each difference is one `warn` line (`Contract drift kind=… location=…`, at most twenty),
  followed by a `Contract drift summary differences=…` line with the true total; an identical document is one `info`
  summary, and one that cannot be fetched is one `warn` line (`Contract drift not checked reason=…`). It never stops the
  task: right after a monolith deployment the two differ by design.


### Webhooks registered on install

Subscriptions all use the same HTTPS callback: `{DSS_BASE_URL}/webhooks/shopify`.

* `PRODUCTS_CREATE`, `PRODUCTS_UPDATE`, `PRODUCTS_DELETE`
* `ORDERS_CREATE`

On `products/create` and `products/update`, the app parses the webhook body for the resource id and runs `GetProductById`. On `orders/create`, it runs `GetOrderForDss` and POSTs to the monolith. All three are subscribed id-only (`id`, `admin_graphql_api_id`), because the resource is loaded right after; `products/delete` carries only the id anyway.

The install leaves nothing else subscribed at the callback URL: a subscription there for a topic the service does not handle (such as `orders/updated`, which earlier versions registered) is deleted. A shop installed before a change to these topics or their fields gets it through `POST /stores/shopify-webhooks/register` (see "DSS internal REST"), which runs the same registration without the merchant; `GET /stores/shopify-status?shopify_subdomain=` shows what would change, and under `access_scopes` which of the scopes the install asks for the shop's grant lacks: a shop installed before that list changed keeps its old grant until it opens the install link again.

**Compliance webhooks:** the app is custom-distributed, not public, so Shopify's mandatory compliance topics (`customers/data_request`, `customers/redact`, `shop/redact`) are not required: they are neither subscribed nor handled, and no customer data is redacted automatically. Shopify's API Terms still require deleting a merchant's data within 30 days of an uninstall or an enforceable deletion request. Going public is a new Shopify app, and these topics come first then.

**Shop domain:** webhooks use `X-Shopify-Shop-Domain` (forward this header through your reverse proxy). DSS logs include a per-request `trace_id` (Ktor's `CallId` plugin, put in the Logback MDC by `CallLogging` and kept across coroutine suspensions); a caller's `X-Request-Id` or `X-Trace-Id` is adopted, every response carries it as `X-Trace-Id`, and every call to the monolith forwards it as `X-Trace-Id`. A monolith error body carries the monolith's own trace id, logged as `monolith_trace_id`. Every line of a request also carries `route` and `method` in the MDC, every line of a webhook delivery `topic` and `webhook_id`, and every line logged once a handler has resolved the shop `shop`; the events shipped to Logflare add `service` (`dss`) and `version`.


### DSS internal REST

**Canonical:** the spec the monolith serves at `/openapi.json`, checked in here as [`src/resources/monolith-dss-openapi.json`](src/resources/monolith-dss-openapi.json) (Gradle `openApiGenerate` uses it).

* `POST /sync-shipments-with-fulfillments` — accepts `SyncShipmentsWithFulfillmentsRequest` (sync DropNext shipments ↔ Shopify fulfillments).
* `POST /tracking-update` — accepts `TrackingUpdateRequest` (tracking status → Shopify FulfillmentEvent).
* `GET /products/catalog?shop=` — the shop's whole catalog as its variant ids grouped by product (`ShopCatalogResponse`), with how long to wait before the next call (`next_request_after_seconds`). The DSS pages Shopify and waits for the shop's rate budget as it goes, so this can take up to about two minutes; it answers the complete catalog or fails, never a part of it.
* `POST /products/fetch` — accepts `FetchProductsRequest` (at most ten product ids); answers the products, with every page of their variants, as the `ProductVariantItem`s a product webhook would upsert, the ids Shopify no longer has, the ids it did not get to, and how long to wait before the next call. It paces itself against the shop's rate budget and, when a product keeps failing or 25 seconds have passed, answers what it loaded and lists the rest as not fetched; only a call that loaded nothing fails.
* `PUT /stores/api-key` — accepts `UpdateStoreApiKeyRequest`; caches the Shopify Admin token in memory and forwards it to the monolith, with `granted_access_scopes` and `missing_access_scopes` as they came (the monolith sends neither; the OAuth install sends both). A `502` (or a `404` when the monolith answers, in its own JSON error, that it does not know the store; a router's `404` is a `502`) means the monolith did not persist it; the token stays cached either way. A blank `api_key` is a `400` before the cache is touched; `shopify_shop_id` may be `null` when unknown.
* `GET /stores/shopify-status?shopify_subdomain=` — answers `ShopifyStoreStatusResponse`: whether the shop's token resolves, which of the scopes the install asks for its grant lacks (`access_scopes`: `complete`, `missing`, or `unknown`), one row per handled webhook topic (`active`, `mismatched` or `missing`, with stale subscriptions pointing elsewhere) and the `obsolete` subscriptions at our URL. Read-only: one monolith lookup and two Shopify queries. A token Shopify refuses is a `401` (and is reported to the monolith, below), a scan refused for a missing scope a `403`.
* `POST /stores/shopify-webhooks/register` — accepts `RegisterShopifyWebhooksRequest`; runs the install's webhook registration for one shop without the merchant and answers `RegisterShopifyWebhooksResponse`: the same rows with what the run changed (`added`, `updated`, `repointed`, `not_applied`, `failed`; obsolete ones `deleted` or `delete_failed`) and a `summary` of the counts. A scan that fails is a `502` and changes nothing. It never touches the token.

The per-shop Admin token is resolved server-side via the in-memory cache (filled by OAuth or `PUT /stores/api-key`) with a fallback to monolith `GET /stores`. These routes require `Authorization: Bearer <MONOLITH_TO_DSS_API_KEY>`.

**A broken installation is reported to the monolith** (`POST /stores/shopify-access-problems`) the moment the DSS sees one: a token Shopify refuses (`token_rejected`) or a scope the grant lacks (`access_scope_missing`, with the handles Shopify named), seen by an `orders/create` or product webhook, by `sync-shipments-with-fulfillments`, `tracking-update`, `products/catalog` or `products/fetch`, by the store status or by the webhook registration. A scope the install does not ask for is not reported, because no reconnect grants it: it is answered `502` (and `200` to a Shopify webhook) and logged at error until a deploy adds it. The report never changes the DSS's own answer; a failed one is logged, and one the time budget leaves no room for is logged as `reason=budget_exhausted`.


### Security notes (production)

* Use **HTTPS** everywhere between clients, monolith, and this service;
set `DSS_ALLOW_INSECURE_MONOLITH=true` (with `DSS_MODE=DEV`; `PROD` refuses the flag at startup) only on developer machines.
* Set `MONOLITH_TO_DSS_API_KEY` so internal REST is not open on the network;
the header is compared in **constant time** to reduce timing leaks.
* **Secrets in env**: `SHOPIFY_APP_CLIENT_SECRET` and the two API keys are as sensitive as passwords —
use a secrets manager in production, not committed `.env` files.
* The HTTP client does **not** log request bodies (avoids leaking tokens to logs).
Unhandled server errors return a generic message; details stay in server logs only.


### Environment variables

| Variable | Required | Description |
| -------- | -------- | ----------- |
| `SHOPIFY_APP_CLIENT_ID` | yes | App Client ID (OAuth client id used for install flow) |
| `SHOPIFY_APP_CLIENT_SECRET` | yes | App secret (OAuth HMAC, token exchange, webhook HMAC) |
| `DSS_BASE_URL` | yes | Public https origin of this server (tunnel URL in dev); anything but an absolute `https://` URL is refused at startup |
| `OAUTH_REDIRECT_PATH` | no | Default `/oauth/callback` (must match Partner redirect URL); anything but an absolute path is refused at startup |
| `PORT` | no | Default `8080` |
| `MONOLITH_BASE_URL` | yes | **REST root URL** DSS appends segments to (`/orders`, `/stores`, `/stores/api-key`, `/product-variants`). May include a path prefix, e.g. `https://staging.dropnext.com/api/shopify-service/v1` (no trailing slash). Leave `MONOLITH_API_PREFIX` empty when the full prefix is already in this value. Anything but an absolute `https://` URL (or `http://` with `DSS_ALLOW_INSECURE_MONOLITH`) is refused at startup. Its origin (scheme, host and port, without the path) is also where the install page sends the merchant on, to `/retailer-portal`, as the monolith serves its portals on the host of its DSS API. So this must be the host the portals are on: an API-only host sends every merchant to a host without the portal, and nothing reports it. |
| `MONOLITH_API_PREFIX` | no | Inserted **after** base: `{BASE}/{PREFIX}/stores/api-key`. Example env `MONOLITH_API_PREFIX=api/v1`. Omit slashes at edges; empty (default) uses paths directly under base. |
| `DSS_TO_MONOLITH_API_KEY` | yes | Bearer token sent on every monolith request (`Authorization`). The monolith checks incoming DSS calls against the variable of the same name and answers every call without it `401`. Letters, digits and `-._~+/` only; a placeholder value is refused at startup, and a value the monolith answers `401` for makes the task exit before it takes traffic. |
| `MONOLITH_TO_DSS_API_KEY` | yes | Bearer token every monolith -> DSS call must carry (`Authorization: Bearer ...`). The monolith sends the variable of the same name. Letters, digits and `-._~+/` only, at least 32 characters. |
| `DSS_ALLOW_INSECURE_MONOLITH` | no | Set `true` only for local dev, together with `DSS_MODE=DEV`, so `MONOLITH_BASE_URL` may use `http://`. In `PROD` mode (the default) the flag itself is refused at startup, and an insecure URL without it always is. |
| `LOGFLARE_SOURCE_NAME` | no | Logflare source to ship logs to, e.g. `dropnext.dss` (the monolith ships to `dropnext.app`). Created through the API when it does not exist yet; when that API cannot hand out a token (the Logflare inside a local `supabase start` answers its source list with a 500) the service creates the source under a token derived from the name and ships under that. Shipping needs this **and** `LOGFLARE_API_KEY`; with either missing the service logs to stdout only. |
| `LOGFLARE_API_KEY` | no | Logflare account key. Lives in AWS Secrets Manager (`dropnext/<env>/dss`), never in a repo. |
| `LOGFLARE_ENDPOINT` | no | Default `https://api.logflare.app`. Point it at a local stub to try shipping without an account. Must be an absolute URL. |
| `VERSION_TAG` | no | What `/health`, `/api` and the startup log report as the version. The Dockerfile bakes it in from the build argument `dnc` passes (the image tag), so it is not something to set by hand; unset, as in a local run, it reads `local-dev`. |
| `DSS_MODE` | no | `DEV` or `PROD` (default), the same switch as the monolith's `MONOLITH_MODE`. `DEV` logs one line per HTTP request (method, path, status — never the query string); useful while working on a webhook locally. |


### Troubleshooting

* **Startup exits quickly**: verify required vars `SHOPIFY_APP_CLIENT_ID` and `SHOPIFY_APP_CLIENT_SECRET`, plus `DSS_BASE_URL`, `MONOLITH_BASE_URL` and `MONOLITH_TO_DSS_API_KEY`.
* **OAuth callback mismatch in Shopify**: ensure `{DSS_BASE_URL}{OAUTH_REDIRECT_PATH}` exactly matches the Partner Dashboard redirect URL.
* **DSS auth failures (`401`)**: a bare `401` with a `WWW-Authenticate: Bearer` header means the `Authorization: Bearer <MONOLITH_TO_DSS_API_KEY>` header was missing or wrong; a `401` with a JSON body means no Shopify Admin token is resolvable for the shop, neither in memory nor at the monolith — complete OAuth or `PUT /stores/api-key`.
* **`[monolith] MONOLITH_BASE_URL is unset` despite being configured**: duplicate `MONOLITH_BASE_URL` / `DSS_TO_MONOLITH_API_KEY` lines (often empty trailing blocks pasted from templates) cause **last value wins**. Remove the trailing empties so only one assignment remains; redeploy/restart.
* **`Monolith store api-key … status=404` with HTML `<h1>Not Found`**: DSS hit `{MONOLITH_BASE_URL}/stores/api-key` (before optional prefix) on a host or path where the monolith's DSS API is not mounted. Point `MONOLITH_BASE_URL` at the portal host with the DSS API's path (e.g. `https://staging.dropnext.com/api/shopify-service/v1`), or set `MONOLITH_API_PREFIX` if the routes live under a further path; a separate API-only host would break the install page's *Continue to DropNext* button (see the `MONOLITH_BASE_URL` row). The install page reads such a `404` as not saved, never as an unknown store, since only the monolith's own JSON error says it has no such store. Confirm with `curl -i -X PUT https://your-monolith…/stores/api-key` (+ Bearer header) outside DSS.
* **502 Bad Gateway on `DSS_BASE_URL`**: the reverse proxy forwards to the wrong container port. The JVM binds `PORT` (see `[http] Listening …` startup line). Dockerfile sets `ENV PORT=9999`, but dashboards that add an empty `PORT=` override that with blank. Set `PORT=9999` explicitly or remove the `PORT` key so the image default wins; Traefik/nginx must target the **same** port.
* **Insecure monolith URL rejected**: set `DSS_ALLOW_INSECURE_MONOLITH=true` and `DSS_MODE=DEV` only for local development; production stays HTTPS and refuses the flag.

IDE-specific troubleshooting lives in [docs/setup-intellij-idea.md](./docs/setup-intellij-idea.md).
