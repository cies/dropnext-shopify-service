---
paths:
  - "test/dropnext/dss/**/*.kt"
---
# Rules for test/dropnext/dss/

Referenced from `CLAUDE.md` ("Testing"), which has the short list; this file has the mechanics.

## Three flavours, cheapest first
Pick the cheapest flavour that exercises the behaviour; a more expensive one adds nothing but time.

1. **Pure unit** — mappers, parsers, validation, config, view rendering. No fakes, no coroutines beyond the
   function's own `suspend`. (`ToProductVariantItemsTest`, `MatchShipmentToFulfillmentOrdersTest`, `RenderOAuthInstallPageTest`.)
2. **Fake-backed** — a workflow or a handler class against the in-memory fakes. Build the graph the way production
   does and swap the outside world:

   ```kotlin
   val monolith = FakeMonolithService()
   val shopify = FakeShopifyGraphqlService()
   val deps = dssDependencies(
     testConfig(),
     monolithService = monolith,
     shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(shopify),
     shopTokens = InMemoryShopTokenStore(mapOf(shopify.shop to ShopifyAdminToken("shpat_test"))),
   )
   ```

   Workflows are top-level functions: call them directly with the fakes (`syncShopifyOrderToMonolith(shopify, monolith, gid, topic)`).
3. **Wire-level** — the `Http*` implementations against a recording HTTP fake bound to port 0, and request → response
   tests of the whole `dssModule` under `testApplication`. This is the only flavour that proves JSON shapes, headers
   and status codes.

## The test infrastructure (`test/dropnext/dss/testutil/`)
Everything under `test/` is either a `*Test.kt` mirroring a source file or one of these, sorted by kind:
`fake/` (hand-written stand-ins for our interfaces), `fixture/` (named inputs) and `helper/` (the harness).
`TestSuiteArchitectureTest` fails a file that is neither.
| Helper | Stands in for | Use it when |
|---|---|---|
| `FakeMonolithService` | `MonolithService` | a workflow or handler must call the monolith; assert on `createOrderCalls`, `putStoreApiKeyCalls`, `getStoreCalls`, …; force failures with `createOrderStatus`, `putStoreApiKeyStatus`, `getStoreReturnsNotFound`, `getStoreTransportFailure`. |
| `FakeShopifyGraphqlService` | `ShopifyGraphqlService` for one shop | stub per-operation results (`orderForDssResult = Success(order)`, `Failure(ShopifyError.Network(…))`, a `…ResultQueue` for multi-call flows) and read the recorded inputs. Add a field only when a test needs it — no speculative surface. |
| `FakeShopifyGraphqlServiceFactory` | `ShopifyGraphqlServiceFactory` | hands the same service to every shop; `service = null` simulates "no Admin token resolvable", `tokenSourceUnavailable = true` a token lookup the monolith did not answer. |
| `FakeMonolithHttpServer` | the monolith over HTTP | testing `HttpMonolithService` itself: `enqueue()` a canned response, assert on `requests` (method, path, query, headers, body). |
| `FakeShopifyGraphqlServer` | Shopify Admin Graphql and the OAuth token exchange over HTTP | wire format, OAuth, and workflows end to end. Records each POST by `operationName` and serves the response registered for it. Pair with `shopifyRewritingHttpClient(port)` so production code keeps its real `*.myshopify.com` URLs. |
| `FakeLogflareServer` | the Logflare HTTP API | testing the log shipper: set `knownSourceName`, `sourcesStatusCode`, `logsStatusCode`; `awaitBatch()` waits for a flush and `receivedEvents` holds what was shipped; `logsStallMillis` holds a batch on the wire and `awaitBatchStarted()` fires when it arrives. |
| `testConfig()` (`fixture/ConfigFixtures.kt`) | `Config.fromEnv()` | every test that needs a `Config`; override only the parameter under test. |
| `withDssApp(deps) { client -> }` (`helper/DssApp.kt`) | the running service | any request → response test: it mounts the production `dssModule` under `testApplication`. Pass `authenticateAsMonolith = true` for the monolith-facing routes, and `warmUp = WarmUp(budget) { … }` to test the readiness gate (the default is `WarmUp.NONE`: the app is ready at once and no fake sees a warm-up call). The application starts on the first request; call `startApplication()` before polling `deps.readiness`. |
| `FakeWarmUpLoopbackService` | `WarmUpLoopbackService` | the warm-up workflow: set `apiCheckStatus` / `webhookDeliveryStatus` (`null` is no answer) and read `apiCheckCalls` / `webhookDeliveryCalls`, each recording the shop and the trace id. |
| `testHttpClient()` (`helper/testHttpClient.kt`) | an outbound client | talking to a fake server; short timeouts so a hung fake fails fast. |
| `base64HmacSha256` / `hexHmacSha256` (`helper/testHmac.kt`) | Shopify's signatures | signing a webhook body or an OAuth query string. |
| `capturingLogs { }` (`helper/capturedLogs.kt`) | the root logger | asserting on what was logged; declare `@ResourceLock(GLOBAL_LOG_REGISTRY)`. |
| `FakeFlakyServer` | an upstream that drops connections | transport-failure and retry cases; never bind-then-close a port to fake one. |


## Suspend code
Handlers, services and workflows are `suspend`; wrap the test body: ``fun `…`() = runBlocking { … }``. There is no
`runTest`/virtual time — nothing here depends on the clock.

## Request → response tests
- `withDssApp(deps) { client -> }` runs the production `dssModule(deps)` — the real auth guard, error shaping, trace
  ids and JSON content negotiation — under Ktor's `testApplication` (no socket), with the fakes injected through
  `dssDependencies`. Use it for every route family; `TestSuiteArchitectureTest` forbids hand-rolling an
  `embeddedServer` or an `HttpClient` in a test. A single Ktor plugin is tested the same way, with a bare
  `testApplication { application { installX() } }` (`InstallCallIdTest`, `InstallStatusPagesTest`): the test engine
  dispatches on the IO pool, so even the MDC-across-suspension case needs no socket.
- Send JSON through `AppJson`; decode the response into the generated DTO (`SyncShipmentsWithFulfillmentsResponse`,
  `ApiError`) rather than substring-matching the body.
- Assert the status **and** the effect: `assert(r.status == HttpStatusCode.OK)` plus
  `assert(monolith.createOrderCalls.single().shopifyOrderId == 1001L)`.
- Every handler test covers, besides the happy path, the unauthenticated request (`401`) and the "no Admin token"
  path (`DssError.MissingShopifyAdminToken`).

## Assertions
Use Kotlin power-assert (not JUnit assertions): a failing `assert` prints every intermediate value.

- `assertEquals(a, b)` → `assert(b == a)` (order reversed for readability)
- `assertTrue(x)` → `assert(x)`; `assertFalse(x)` → `assert(!x)`
- `assertContains(str, substr)` → `assert(substr in str)`
- `assertNull(x)` → `assert(x == null)`; `assertNotNull(x)` → `assert(x != null)`
- `assertThrows<T> { }` → `assert(runCatching { }.exceptionOrNull() is T)`

One fact per `assert`. Never accept more than one HTTP status in one assert. Prefer comparing decoded values over raw
strings; when a raw body must be checked (the plain-text OAuth errors), check the exact message.

## Fixtures have names
- `minimalOrder(...)` and `orderWithFulfillment(...)` (`testutil/fixture/OrderFixtures.kt`) build a `GetOrderForDss`
  order; `diagramCrossFoOrder()`, `openFulfillmentOrder(...)` and `diagramCrossFoShipment()`
  (`testutil/fixture/FulfillmentOrderFixtures.kt`) build the fulfillment-order scenarios; `shipment(...)` and
  `sampleProduct(...)` live beside them. Add a parameter to a fixture before writing a new literal: generated types have
  many required fields, and a literal per test rots on the next schema bump.
- `ShopDomain.parse("acme.myshopify.com")!!` is the conventional test shop; test tokens are
  `ShopifyAdminToken("shpat_test")` / `ShopifyAdminToken("shpat_fake")`. Never paste a token from `.env` into a test.

## Where tests live
- Mirror the subject's package: `src/dropnext/dss/lib/monolith/HttpMonolithService.kt` →
  `test/dropnext/dss/lib/monolith/HttpMonolithServiceTest.kt`. A `lowerCamel.kt` source of top-level functions gets a
  `PascalCaseTest.kt` (`syncShopifyTrackingEvent.kt` → `SyncShopifyTrackingEventTest.kt`).
- `TestSuiteArchitectureTest` enforces the inverse direction: every `*Test.kt` must have a source counterpart, which
  catches a stale test after a rename. Exceptions go in its `mirrorSourceAllowList` with a reason. Test
  infrastructure is recognised by living under `testutil/`, not by its file name.
- Fixtures and helpers live under `testutil/fixture/` and `testutil/helper/` as `internal` top-level functions, so a
  second package imports rather than copies. A private copy of a fixture in a test class is how four different
  `shipment(...)` builders happened.

## Parallelism
Test classes run concurrently, the methods within a class sequentially (`test/resources/junit-platform.properties`,
inside one Gradle fork). What makes that safe, and what a new test must keep true: fakes are per-test instances, every
HTTP fake binds port 0, and nothing assumes a fixed port, shared static state or an order between classes.
`TestSuiteArchitectureTest` checks part of it: no mutable companion state, and a fake server records into a concurrent
collection because its request thread writes what the test thread reads. A test that reads the root logger
(`capturingLogs`, a Logback appender, `System.setErr`) declares `@ResourceLock(GLOBAL_LOG_REGISTRY)`, which is JUnit's
global lock: the test runs alone, because every other test writes to that logger and a `single { … in it }` over its
lines is only right while nothing else runs. Put the lock on the methods that read the log rather than on the class
when only a few do, so the rest of the class still overlaps with the suite. `SlowestClassesFirstOrderer`
(`testutil/helper/`) hands the known-slow classes to the pool first and the log-reading ones last; regenerate its list
with `./gradlew test slowestTestClasses` when the timings move.

## Fakes record uniformly
Every fake implements `RecordingFake`: one `<method>Calls` list per recorded method and a `clear()`. `calls.size` is
how often, `calls.single()` is with what. No counters, no `last…` fields — a reader should never have to open the
fake to find out which shape this method uses.

## No mock frameworks, no new test dependencies
The dependency policy in `CLAUDE.md` applies to `test/` too. A fake is a class implementing our own interface.
