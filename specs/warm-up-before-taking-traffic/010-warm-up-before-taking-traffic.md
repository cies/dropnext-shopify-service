# Spec: warm up before taking traffic

Status: draft
Author: cies (with Claude)
Date: 2026-09-13
Depends on: nothing. Independent of the `-XX:TieredStopAtLevel=1` Dockerfile change and of letting a monolith write
that is already on the wire finish past the budget; each of the three helps on its own.
Repos: DSS only (no infra change, no contract change unless open question 2 is answered with one).


## Problem

Every freshly started DSS task fails the first `orders/create` it receives. On staging, 2026-09-12, all three new
tasks (`510c7a`, `21d327`, `24c861`) answered it `502 timed_out` after ~4 s, and in none of the three did a monolith
request reach the ALB within those 4 s: the token lookup (`GET /stores`) was still in client setup and the TLS
handshake when the budget ended. The task before the budget existed (`c4e01b`) needed 6.8 s to load its first order.
Warm, the same webhook takes 0.3–0.6 s.

The cause is one-time JVM work done on the first request, on a task that has a quarter vCPU (256 CPU units). Measured
with the production runtime (the jlinked Corretto 25 and `JAVA_OPTS` of the Dockerfile) under a 0.25 CPU quota,
calling the production client code:

| Step (first time in the JVM)                     | Wall        | CPU       |
|--------------------------------------------------|-------------|-----------|
| First outbound HTTPS call (the monolith client)  | 4.6–5.5 s   | 1.1–1.4 s |
| Same call, second time                           | 0.02–0.19 s | ~0.03 s   |
| First call to a second host (the Graphql client) | 0.6 s       | 0.13 s    |
| First order mapping and request encoding         | 0.19 s      | 0.05 s    |

The CPU quota turns every CPU-second into four wall-seconds. In production the first delivery also pays the first
inbound request (~0.4 s: `took_ms` of the first `orders/updated` on every new task), the first real Shopify order query
(~2 s) and the monolith's own cold paths, so it needs 7–8 s against a 4 s budget and Shopify's 5.

A rolling deploy already gives us the time to pay this before any webhook arrives: ECS registers the new target, the
ALB needs two passing health checks 30 s apart before routing to it (`healthy_threshold = 2`, `interval = 30`), and
the old task keeps serving until then. Today `/health` answers `200` from the first moment, so the ALB sends the new
task its first webhook while it is still cold.


## What changes

The warm-up is one coroutine in two parts, run in order, each ending in one log line. The **outbound part** needs
nothing but the dependency graph: it calls the production clients directly and pays the one-time cost of the first
HTTPS call, which is most of the table above. The **inbound part** needs the server's socket to be bound: it sends the
service two requests over loopback (`http://127.0.0.1:<PORT>`, never through the ALB, which is not attached yet), so
the pipeline every request runs — the CIO parser, `CallId`, `CallLogging`, the body limit, `StatusPages`, content
negotiation, the bearer provider, the HMAC, the JSON answer — runs once end to end before the first delivery does.

1. **The warm-up workflow**, `workflow/warmUpBeforeTakingTraffic.kt`: a top-level function that takes the services
   and the "request myself" function it uses, runs the two parts below and answers a `WarmUpReport` (one entry per
   step: `ok`, `failed` with a short label, or `skipped` with a reason). It never throws for an upstream failure and
   never changes state anywhere.
2. **The outbound part**, started at once. Its steps, in order:
   1. **Monolith**: one `MonolithService.getStore` call (see open question 1 for the subdomain), through the
      *webhook* monolith service (`webhookMonolithService`, one retry) rather than the route one (three retries), so a
      monolith that is down costs at most ~10.5 s of the budget and the inbound part still runs. This is the step
      that pays the big one-time cost (OkHttp and Ktor client setup, the TLS provider, the trust store, the first
      handshake), for every later outbound call, not only the monolith's.
   2. **Shopify**: one `ShopifyGraphqlService.shopIdentity()` through the production factory, for a shop whose token
      is seeded at startup (see open question 2). Skipped with a reason when there is none.
   3. **Mapping** (optional, see open question 3): decode a checked-in `GetOrderForDss` response, map it with
      `mapOrderForMonolith` and encode the request with `MonolithJson`, with no network involved.
3. **The inbound part**, started when the socket is bound (see item 5): two requests to ourselves, through the shared
   HTTP client (`createSharedHttpClient()`; `ArchitectureTest` forbids building another), each under that client's
   request timeout. A `200`, `401` or `502` answer all mean the pipeline ran; only no answer, or a `5xx` from our own
   `StatusPages`, is a failure.
   1. **The check**: `GET /api/check?shop=<the same shop as the outbound part>` with
      `Authorization: Bearer <MONOLITH_TO_DSS_API_KEY>`. Behind the bearer provider it runs the token store (a cache
      hit for a seeded shop; for any other shop a monolith `GET /stores` over the connection the outbound part
      opened), then, when a token resolves, the read-only webhook-subscriptions query, then encodes the JSON answer.
      A scan never registers anything. A `401` (no token for that shop) is the expected answer in production, where
      nothing is seeded, and is reported as `api_check=401`, not as a failure.
   2. **The delivery**: `POST /webhooks/shopify`, self-signed: topic `orders/updated`, `X-Shopify-Shop-Domain` the
      same shop, `X-Shopify-Webhook-Id: warm-up`, a minimal JSON body, and `X-Shopify-Hmac-Sha256` computed with the
      app secret (`hmacSha256` in `lib/crypto/`, base64, the mirror image of `verifyWebhook`). It pays the body
      receive, the first `Mac` of the JVM, the dispatch and the report, and is skipped as `TOPIC_NOT_MIRRORED`: no
      service is resolved, nothing goes out. It is the request shape the ALB will send first, run once for real.
4. **Readiness**: a `Readiness` holder (an `AtomicBoolean` behind `isReady` / `markReady()`), built in
   `dssDependencies` and handed to `DiagnosticsHandlers`. `/health` answers `503` with
   `{"status":"warming_up","version":…}` until the holder is ready, and `200` with `{"status":"ok","version":…}`
   after, the shape it has today. The ALB matcher is `200`, so a warming task stays out of rotation without an infra
   change. Only `/health` is gated: the two loopback requests, and any request that reaches a warming task, are
   served as today.
5. **Trigger**: `dssModule` subscribes to two Ktor events, next to its `ApplicationStopped` subscription.
   `ApplicationStarted` launches the warm-up in the application's scope, bounded by `WARM_UP_BUDGET` (20 s, see "Edge
   cases"), and marks the holder ready in a `finally`: when the budget ends, when a step fails, and when the warm-up
   throws. `ServerReady` completes a `CompletableDeferred` the inbound part awaits before its first request. Two
   events because they are not the same moment (verified in the Ktor 3.4.1 sources): `ApplicationStarted` is raised
   by `EmbeddedServer.start()` right after the modules are loaded, *before* the engine binds its socket;
   `ServerReady` is raised by `CIOApplicationEngine.startSuspend()` after the bind. A loopback request on
   `ApplicationStarted` would be refused. The test engine never raises `ServerReady` (only `ApplicationStarted` and
   the stop events), which is fine: under test the warm-up is a no-op (item 6).
6. **Wiring for tests**: `dssDependencies` gets a `warmUp` parameter defaulting to the production workflow; the test
   helpers (`withDssApp`, the `deps(...)` builders) default it to a no-op that is ready at once, so no existing test
   sees an extra outbound call (a `FakeMonolithService.getStoreCalls` or a `FakeMonolithHttpServer` queue would).
7. **Two log lines**, one per part, when it ends:
   `Warm-up outbound done took_ms=… monolith=ok shopify=skipped reason=no_seeded_shop` and
   `Warm-up inbound done took_ms=… api_check=200 webhook=200`; info when every step is `ok` or `skipped` (a `401`
   from the check counts as such), warn when one `failed`. A step the budget never reached is `skipped reason=budget`.
   The delivery also leaves the handler's own `Webhook done … webhook_id=warm-up outcome=skipped` line at info, as
   any acknowledged delivery does; the fixed webhook id is what keeps it out of a per-shop delivery count. Both
   loopback requests send an `X-Trace-Id` of the warm-up's making (`CallId` adopts it), so the three lines tie together.


## Behavioral contract

- **Precondition, outbound part**: none beyond the dependency graph. It starts on `ApplicationStarted`, before the
  socket is bound; the clients need nothing of the server.
- **Precondition, inbound part**: the socket is bound (`ServerReady`). Until then it waits, inside the budget, so
  `/health` can answer `503` while the warm-up runs instead of refusing the connection.
- **Postcondition**: `Readiness.isReady` is true no later than `WARM_UP_BUDGET` after start, whatever the upstreams
  did and whether `ServerReady` ever fired.
- **Invariant**: the warm-up changes nothing: no token is remembered or forgotten, no monolith write, no Shopify
  mutation. Both parts name a shop whose token is seeded, or one the monolith does not know (open question 1), so the
  store is never filled by the warm-up; a `shopIdentity` answered `401` must not reach `ShopTokenStore.forget` for a
  token the store holds for real traffic (open question 2 decides where that token comes from). The scan is read-only
  and an `orders/updated` delivery is acknowledged without work.
- **Invariant**: the loopback requests never leave the container: `127.0.0.1`, plain HTTP, the port the server itself
  listens on (`Config.serverPort`). The bearer key and the app secret travel only over that.
- **Invariant**: webhook, OAuth and monolith-facing routes behave as today during the warm-up; only `/health`
  changes. Requests that reach a warming task (see "Edge cases") are served, cold.


## Edge cases

- **The monolith is down or deploying at the same time** (it was, on 2026-09-12). The outbound part's monolith step
  fails after its timeout and one retry: 5 s per attempt plus 0.5 s of backoff, ~10.5 s worst case, which leaves the
  inbound part its turn inside the 20 s budget. The check then asks the monolith once more for an unseeded shop, and
  fails the same way, but the delivery still runs, since it needs no upstream. The task becomes ready anyway:
  refusing traffic because the monolith is down would not make webhooks succeed, and ECS would replace an unhealthy
  task in a loop.
- **The monolith answers `401` or `500` to the warm-up request** (both happened on 2026-09-12: a wrong API key, a
  token-encryption key mismatch). Logged by `logMonolithFailure` and by the retry line as today, reported `failed`
  in the outbound line: a misconfiguration becomes visible at startup instead of on the first webhook.
- **`ServerReady` never fires.** A bind failure ends the process anyway (`start(wait = true)` throws). Should it
  happen otherwise, the inbound part waits until the budget ends, reports `skipped reason=server_not_bound`, and the
  task is ready.
- **A loopback request gets no answer** (refused, or past the client's timeout): that step is `failed`, the next one
  still runs, the task is ready. The port cannot be wrong: it is the same `Config.serverPort` the connector binds.
- **The only task restarts after a crash** (no old task to keep serving). The ALB fails open when every target is
  unhealthy and routes to the warming task: the first webhooks are cold, as today. Nothing gets worse.
- **Warm-up slower than the health checks.** With a 30 s grace period (`health_check_grace_period_seconds`) and three
  failed checks 30 s apart before ECS calls a task unhealthy, a 20 s budget cannot get a task killed.
- **SIGTERM during the warm-up.** The application scope is cancelled with the server; the `finally` marks the holder
  ready on the way out, which is harmless. A loopback request in flight is either drained with the other in-flight
  requests or refused, and either is a `failed` step nobody reads.
- **The delivery holds a mirror slot** (`MAX_CONCURRENT_MIRRORS`) for the milliseconds the skip takes; nothing else
  is going on at that moment.
- **Local development** (`DSS_MODE=DEV`) runs the same warm-up. With no reachable monolith it ends `failed` within
  the budget and the service is ready; see open question 4. The delivery and its `Webhook done` line show up on every
  local start, which is also a free check that the app secret in `.env` is the one the service signs with.


## Reuse inventory

- `MonolithService.getStore` over `webhookMonolithService` (`dependencies.kt`, one retry) and `logMonolithFailure`
  (`lib/monolith/`).
- `ShopifyGraphqlServiceFactory.forShop` and `ShopifyGraphqlService.shopIdentity` (`lib/shopify/graphql/`); the
  `ShopIdentity.graphql` operation already exists.
- `createSharedHttpClient()` (`lib/ktor/httpClientBuilders.kt`) for the loopback requests; `Paths.apiCheck` and
  `Paths.webhooksShopify` for their paths; `Config.serverPort`, `Config.monolithToDssApiKey` and
  `Config.appClientSecret` for the port, the bearer and the signature.
- `hmacSha256` (`lib/crypto/`) and `ShopifyHmacVerifierService.verifyWebhook` (`lib/shopify/webhook/`) as the model
  for the one signing helper the delivery needs, next to the verifier.
- `Config.shopAccessTokens` (seeded tokens) and the `dssDependencies` parameter-default pattern (`dependencies.kt`).
- `DiagnosticsHandlers.handleHealth` and its `HealthResponse` (`handler/DiagnosticsHandlers.kt`).
- The `monitor.subscribe(ApplicationStopped)` pattern in `dssModule.kt`; `ApplicationStarted` and `ServerReady` are
  the other two definitions in `io.ktor.server.application`.
- `WebhookDeliveryReport` already carries `webhook_id`; nothing to add for the `warm-up` id.
- `mapOrderForMonolith` (`mapper/`), `MonolithJson` (`lib/json/`), the order fixtures as a model for the checked-in
  response (`test/dropnext/dss/testutil/fixture/OrderFixtures.kt`), should open question 3 keep the mapping step.
- Test side: `withDssApp`, `capturingLogs`.


## Tests

The warm-up itself is not required to be under test. The inbound part cannot run under the test engine (no socket,
no `ServerReady`), and a fake-backed test of the outbound part would prove only that the fakes were called. The test
helpers default `warmUp` to a no-op that is ready at once, so the suite runs as today.

What is under test is the readiness gate, the contract the ALB relies on, request → response through `withDssApp`:

- With a warm-up that suspends on a gate the test controls: `/health` answers `503` with `status=warming_up` before
  the gate opens and `200` with `status=ok` after.
- With a warm-up that never returns and a short budget: `/health` answers `200` once the budget has passed.
- With a warm-up that throws: `/health` answers `200`.

Optional, pure unit: the signing helper round-trips through `ShopifyHmacVerifierService.verifyWebhook`, so the
delivery cannot be rejected by our own verifier after a change to either side.


## Open questions

1. **Which shop do both parts name?** a) The first shop in `DSS_SHOP_ACCESS_TOKENS` (a real `StoreResponse` is
   decoded; staging seeds two shops, production probably none). b) A value no Shopify shop can have, e.g. with an
   underscore, answered `404` → `Success(null)`. To check on the monolith side: that such a request is not logged as an
   error there, since with b) it now arrives twice per start (the outbound step and the check's token lookup).
   Suggested: a) when a seeded shop exists, b) otherwise.
2. **Where does the Shopify step get a shop and a token in production**, where nothing is seeded? a) Skip the step
   there: the monolith step already pays the one-time TLS and client cost, the check skips its query when no token
   resolves, and the rest of the first Shopify call is about 0.6 s at a quarter vCPU. b) A new variable naming one
   warm-up shop, resolved through `forShop` (which also fills the token cache from the monolith). c) Send the
   `ShopIdentity` query through the Graphql client to Shopify's public, token-less proxy
   (`https://shopify.dev/admin-graphql-direct-proxy/<version>`, which `graphqlIntrospectSchema` already reads): to
   check whether it answers queries and not only introspection, and whether leaning on it at every startup is
   acceptable. Suggested: a), and revisit after measuring on staging.
3. **Keep the mapping step?** It saves ~0.2–0.4 s of the first delivery at the cost of a checked-in response file in
   `src/resources/`. The self-delivered `orders/updated` does not run the mapper, and a self-delivered `orders/create`
   would write. Suggested: leave it out of the first version.
4. **Skip the warm-up in `DEV`?** A local run against no monolith waits up to 20 s for `/health`. Suggested: run it
   in both modes (it is what catches a wrong `MONOLITH_BASE_URL` or key) unless that wait turns out to annoy.


## Out of scope

- `-XX:TieredStopAtLevel=1` in `JAVA_OPTS`: measured to halve the cold path's CPU (the first outbound call 2.8 s
  instead of 4.6–5.5 s at a quarter vCPU), a one-line Dockerfile change of its own.
- More CPU for the task (an infra change) and a JDK AOT cache (a training run in the image build).
- Letting a monolith write that is already on the wire finish past the webhook budget.
- Warming the real order path (the `GetOrderForDss` query, the mapper, the monolith `POST`): it needs a real order
  and a write, or the checked-in response of open question 3.


## Verification on staging

After a deploy: the `Warm-up outbound done` line, the `Webhook done … webhook_id=warm-up` line and the
`Warm-up inbound done` line, in that order and sharing a trace id, precede the ECS "registered targets" event by tens
of seconds, and the first `orders/create` after the deploy is `outcome=mirrored` with a `took_ms` in the range of a
warm one (the ALB access log shows the `GET /stores` it made within a second of the delivery).
