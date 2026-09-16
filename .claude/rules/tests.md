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
| `FakeMonolithService` | `MonolithService` | a workflow or handler must call the monolith; assert on `createOrderCalls`, `putStoreApiKeyCalls`, `getStoreCalls`, …; force failures with `createOrderStatus`, `putStoreApiKeyStatus`, `putStoreApiKeyTransportFailure`, `getStoreReturnsNotFound`, `getStoreTransportFailure`. |
| `FakeShopifyGraphqlService` | `ShopifyGraphqlService` for one shop | stub per-operation results (`orderForDssResult = Success(order)`, `Failure(ShopifyError.Network(…))`, a `…ResultQueue` for multi-call flows) and read the recorded inputs. Add a field only when a test needs it — no speculative surface. |
| `FakeShopifyGraphqlServiceFactory` | `ShopifyGraphqlServiceFactory` | hands the same service to every shop; `service = null` simulates "no Admin token resolvable", `tokenSourceUnavailable = true` a token lookup the monolith did not answer. |
| `FakeMonolithHttpServer` | the monolith over HTTP | testing `HttpMonolithService` itself: `enqueue()` a canned response, assert on `requests` (method, path, query, headers, body). |
| `FakeShopifyGraphqlServer` | Shopify Admin Graphql and the OAuth token exchange over HTTP | wire format, OAuth, and workflows end to end. Records each POST by `operationName` and serves the response registered for it. Pair with `shopifyRewritingHttpClient(port)` so production code keeps its real `*.myshopify.com` URLs. |
| `FakeLogflareServer` | the Logflare HTTP API | testing the log shipper: set `knownSourceName`, `sourcesStatusCode`, `logsStatusCode`; `awaitBatch()` waits for a flush and `receivedEvents` holds what was shipped; `logsStallMillis` holds a batch on the wire and `awaitBatchStarted()` fires when it arrives; `receivedSourcesAuthorizations` and `receivedIngestApiKeys` hold what each endpoint was authenticated with. |
| `testConfig()` (`fixture/ConfigFixtures.kt`) | `Config.fromEnv()` | every test that needs a `Config`; override only the parameter under test. Its two secrets default to `TEST_APP_SECRET` and `TEST_MONOLITH_TO_DSS_API_KEY`, so a test cannot sign with one value and configure another. |
| `testDependencies(...)` (`fixture/DependenciesFixtures.kt`) | `dssDependencies(...)` | **the** way a test builds the graph. Defaults: a monolith that accepts everything, an empty token store, and a shop whose Admin token does not resolve. `shopify = …` gives every shop a service, `honorTokenStore = true` makes the factory read the store as production does, `tokenSourceUnavailable = true` is a lookup the monolith did not answer. |
| `ACME_SHOP`, `OTHER_SHOP`, `CANONICAL_ACME_SHOP`, `TEST_ADMIN_TOKEN`, `TEST_APP_SECRET`, `TEST_MONOLITH_TO_DSS_API_KEY` (`fixture/ShopFixtures.kt`) | the shop, token and secrets | everywhere. Keep a literal in the test only when the case is *about* that literal, such as a wrong secret of the right length. |
| `FakeShopifyOAuthService` | `ShopifyOAuthService` | an OAuth callback test that is not about the code exchange itself: the signed state is a plain string, so no fake Shopify server is needed. Expiry and real signatures are `HttpShopifyOAuthService`'s. |
| `withDssApp(deps) { client -> }` (`helper/DssApp.kt`) | the running service | any request → response test: it mounts the production `dssModule` under `testApplication`. Pass `authenticateAsMonolith = true` for the monolith-facing routes, and `warmUp = WarmUp(budget) { … }` to test the readiness gate (the default is `WarmUp.NONE`: the app is ready at once and no fake sees a warm-up call). The application starts on the first request; call `startApplication()` before polling `deps.readiness`. It hands back whatever the block answers, for a value that outlives the application. |
| `FakeWarmUpLoopbackService` | `WarmUpLoopbackService` | the warm-up workflow: set `apiCheckStatus` / `webhookDeliveryStatus` (`null` is no answer) and read `apiCheckCalls` / `webhookDeliveryCalls`, each recording the shop and the trace id. |
| `withFakeShopifyServer { server, client -> }` / `withFakeMonolithServer { server, baseUrl -> }` (`helper/fakeServers.kt`) | a fake upstream's lifecycle | every test that needs one. Starts it, hands it over, stops it. Never write a `@BeforeAll`/`@AfterAll` of your own (checked); a server a `PER_CLASS` test shares between its methods is a field marked `@AutoClose`. `shopifyServiceOn(client)` builds the production Graphql service in front of the Shopify one. |
| `testHttpClient()` (`helper/testHttpClient.kt`) | an outbound client | talking to a fake server. Its timeouts are a 30-second backstop against a fake that never answers, never a deadline a test asserts on; a test *about* a timeout passes its own, short one. `throwingHttpClient(cause)` fails every request before the wire. |
| `awaitUntil { }` / `awaitUntilBlocking { }` (`helper/awaitUntil.kt`) | a polling loop | anything that becomes true on another coroutine or thread. Both are bounded and answer whether the condition held, so the test asserts the fact. Never write a bare `while` loop and never `Thread.sleep` to wait (a `Thread.sleep` in a `*Test.kt` fails the build). |
| `successValue()` / `failureReason()` (`helper/resultValues.kt`) | a result4k `Result` cast | reading what a result carries. `(x as Success).value` answers a wrong result with a `ClassCastException`; these name what came instead. |
| `MutableTimeSource` (`helper/MutableTimeSource.kt`) | `TimeSource.Monotonic` | the elapsed time a report prints, so `took_ms=` is a value rather than a presence check. |
| `base64HmacSha256` / `hexHmacSha256` (`helper/testHmac.kt`) | Shopify's signatures | signing a webhook body or an OAuth query string. |
| `capturingLogs { }` (`helper/capturedLogs.kt`) | the root logger | asserting on what was logged; declare `@ResourceLock(GLOBAL_LOG_REGISTRY)`. It answers a `CapturedLogs<T>`, which *is* the `List<String>` of lines and also carries the block's `.value`, for a test with something to assert about both. `mdcOf(line)` reads a line's MDC apart from its message. |
| `FakeFlakyServer` | an upstream that drops connections | transport-failure and retry cases; never bind-then-close a port to fake one. |


## Suspend code
Handlers, services and workflows are `suspend`; wrap the test body: ``fun `…`() = runBlocking { … }``. That is the
default, because almost nothing here depends on the clock.

The exception is the webhook time budget, which is *made* of durations. `mirrorWithinBudget` is its own function so it
can be driven under `runTest`, where the scheduler skips every `delay` and `testScheduler.currentTime` is an exact
assertion about how long the policy would have waited (`MirrorWithinBudgetTest`). Use `runTest` only for that kind of
case: code whose subject is elapsed time. A test that reaches for it to make an unrelated wait go away is hiding a
missing `awaitUntil`. `TestSuiteArchitectureTest` allows `runTest` only in the files on its `runTestAllowList`.

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
- The shop, the token and the two secrets are named once in `testutil/fixture/ShopFixtures.kt` (`ACME_SHOP`,
  `TEST_ADMIN_TOKEN`, `TEST_APP_SECRET`, `TEST_MONOLITH_TO_DSS_API_KEY`) — import them rather than re-deriving them, so
  a test cannot sign with one secret while the service is configured with another. A `shpat_…` literal is still right
  where the name *is* the point (`shpat_from_monolith`, `shpat_after_reinstall`). Never paste a token from `.env`.

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
`TestSuiteArchitectureTest` checks most of it: no mutable companion state, a fake server records into a concurrent
collection because its request thread writes what the test thread reads, a test that reads the root logger declares the
lock, and a lock names the `GLOBAL_LOG_REGISTRY` constant rather than a string literal only it knows. A test that reads the root logger
(`capturingLogs`, a Logback appender, `System.setErr`) declares `@ResourceLock(GLOBAL_LOG_REGISTRY)`, which is JUnit's
global lock: the test runs alone, because every other test writes to that logger and a `single { … in it }` over its
lines is only right while nothing else runs. Put the lock on the methods that read the log rather than on the class
when only a few do, so the rest of the class still overlaps with the suite. `SlowestClassesFirstOrderer`
(`testutil/helper/`) hands the known-slow classes to the pool first and the log-reading ones last; regenerate its list
with `./gradlew test slowestTestClasses` when the timings move.

`TestSuiteWarmUp` (`testutil/helper/`) runs one loopback request and one test-engine request before the first class, so
no test pays the first load of the CIO server, OkHttp and the Ktor test engine while the pool is busy. It is registered
through `test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`, the one hook that runs
once per JVM. It cannot fail a run; if it ever cannot run, the suite is only slower.

## Fakes record uniformly
Every fake implements `RecordingFake`: one `<method>Calls` list per recorded method and a `clear()`. `calls.size` is
how often, `calls.single()` is with what. No counters, no `last…` fields — a reader should never have to open the
fake to find out which shape this method uses.

## One spelling per thing
`org.junit.jupiter.api.Test`, never `kotlin.test.Test`. On the JVM the latter is a typealias for the former, so both
produce the same annotation and JUnit cannot tell them apart — it exists for multiplatform code, which this is not.
Two spellings for one concept only cost a reader a moment of doubt, and `kotlin.test` drags `assertEquals` and friends
into scope beside it, which this suite forbids. Likewise `@BeforeEach` / `@AfterEach`, not `@BeforeTest` / `@AfterTest`.
`TestSuiteArchitectureTest` fails any reference to `kotlin.test`, imported or fully qualified.

That rule is also why `build.gradle.kts` declares `src/**/*.kt` and `test/**/*.kt` as inputs of the `test` task. The
architecture rules read the sources as text, and Gradle otherwise keys the task on the bytecode alone — so a change
that compiles to identical classes is served from the build cache with the rules never run. Swapping the JUnit
annotation for its `kotlin.test` typealias is exactly that change, and it hid from the rule until the inputs were
declared. Anything else that reads a file rather than a class has the same requirement.


## No mock frameworks, no new test dependencies
The dependency policy in `CLAUDE.md` applies to `test/` too. A fake is a class implementing our own interface; a
reference to MockK or Mockito fails `TestSuiteArchitectureTest`.

Two test-only libraries are declared, both already on the runtime classpath before they were named:
`junit-platform-launcher` (the `LauncherSessionListener` above) and `kotlinx-coroutines-test` (`runTest`, for the
budget policy alone).
