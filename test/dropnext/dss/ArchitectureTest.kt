package dropnext.dss

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import dropnext.dss.testutil.helper.kotlinSourceFileTexts
import dropnext.dss.testutil.helper.normalizedPath
import dropnext.dss.testutil.helper.pathContainsAllowListEntry
import dropnext.dss.testutil.helper.projectRoot
import dropnext.dss.testutil.helper.withoutComments
import java.io.File
import org.junit.jupiter.api.Test


/**
 * These tests try to pin our conventions of `src/` that can be checked mechanically:
 * the dependencies our packages may have on each other, the reflection ban, the secret contract,
 * and the boundaries `CLAUDE.md` states in prose.
 *
 * A rule that greps for code reads the file's `code` (so not the comments), never its raw text:
 * a comment explaining why a file avoids a construct must not be read as the construct itself.
 */
class ArchitectureTest {

  /**
   * Two memory representations over the same tree, because they cost wildly different amounts to traverse.
   *
   * The [srcScope] is a parsed, read-only model of the code, and building it — plus materializing a file's text
   * through it — is by far the most expensive part of any rule here (but has Konsist's advantages).
   * Our sources live in `src/` (not `src/main/kotlin/`), which defeats Konsist's built-in detection; the path
   * stays relative because Konsist resolves it against the project root it finds itself.
   *
   * The [srcFiles] data is for mere text search, and straight copies disk files to memory instead.
   *
   * Both live in the companion, so they are built once per test JVM, whatever lifecycle JUnit gives the class,
   * and shared by every rule and every class that needs them. Mirrors the monolith's `ArchitectureTest`.
   */
  companion object {
    private val srcScope by lazy { Konsist.scopeFromDirectory("src") }

    private val srcFiles by lazy { kotlinSourceFileTexts("src") }
  }

  /**
   * Our packages form a stack whose arrows point one way only. The two that carry the most weight:
   *
   * - `lib` may not reach into application code, and its packages may not reach into each other
   *   (only into the generic `lib/json`, `lib/crypto`, `lib/slf4j`). It is the part of this repo
   *   that could be lifted out into a library of its own.
   * - `domain` and `contract` are the vocabulary everything shares; they depend on nothing of ours.
   *
   * `contract` is generated under `build/`, so it never appears in this scope: every layer may
   * import it and no rule needs to say so.
   */
  @Test
  fun `the project's packages have correct dependencies on each other`() {
    srcScope.assertArchitecture {
      val boot = Layer("boot", "dropnext.dss.boot..")
      val bootConfig = Layer("boot/config", "dropnext.dss.boot.config..")
      val bootWarmup = Layer("boot/warmup", "dropnext.dss.boot.warmup..")
      val domain = Layer("domain", "dropnext.dss.domain..")
      val handler = Layer("handler", "dropnext.dss.handler..")
      val mapper = Layer("mapper", "dropnext.dss.mapper..")
      val path = Layer("path", "dropnext.dss.path..")
      val presentation = Layer("presentation", "dropnext.dss.presentation..")
      val routing = Layer("routing", "dropnext.dss.routing..")
      val workflow = Layer("workflow", "dropnext.dss.workflow..")
      val lib = Layer("lib", "dropnext.dss.lib..")
      val libShopify = Layer("lib/shopify", "dropnext.dss.lib.shopify..")
      val libMonolith = Layer("lib/monolith", "dropnext.dss.lib.monolith..")
      val libKtor = Layer("lib/ktor", "dropnext.dss.lib.ktor..")
      val libJson = Layer("lib/json", "dropnext.dss.lib.json..")
      val libCrypto = Layer("lib/crypto", "dropnext.dss.lib.crypto..")
      val libSlf4j = Layer("lib/slf4j", "dropnext.dss.lib.slf4j..")
      val libLogflare = Layer("lib/logflare", "dropnext.dss.lib.logflare..")

      val applicationLayers = setOf(boot, handler, mapper, path, presentation, routing, workflow)

      domain.doesNotDependOn(applicationLayers + lib)
      lib.doesNotDependOn(applicationLayers)
      libShopify.doesNotDependOn(libMonolith, libKtor)
      libMonolith.doesNotDependOn(libShopify, libKtor)
      libKtor.doesNotDependOn(libShopify, libMonolith)
      libJson.doesNotDependOn(domain, libShopify, libMonolith, libKtor, libCrypto, libSlf4j)
      libCrypto.doesNotDependOn(domain, libShopify, libMonolith, libKtor, libJson, libSlf4j)
      libSlf4j.doesNotDependOn(domain, libShopify, libMonolith, libKtor, libJson, libCrypto)
      // The log shipper may name the secret it authenticates with, and nothing else of ours: it runs
      // on its own thread, with its own HTTP client, so that logging a failure cannot re-enter the
      // code that failed. `app.kt` is what knows both it and `Config`.
      libLogflare.doesNotDependOn(libShopify, libMonolith, libKtor, libJson, libCrypto, libSlf4j)
      // What the process is started with and what it does before taking traffic: not the application itself,
      // so the handlers may read it but it wires nothing of theirs. The warm-up drives the real clients and the
      // routes, so it may reach every `lib` package and `path`. The config is read before any of `lib` is built
      // (`main` attaches the log shipper and builds the clients from it), so it reaches none of it. The two halves
      // know nothing of each other: `dssDependencies` hands the warm-up the few values it needs, so a test runs
      // the warm-up without a `Config`, and the config never learns what the process does with it.
      boot.doesNotDependOn(handler, mapper, presentation, routing, workflow)
      bootConfig.doesNotDependOn(bootWarmup, lib)
      bootWarmup.doesNotDependOn(bootConfig)
      mapper.doesNotDependOn(boot, handler, path, presentation, routing, workflow, libMonolith, libKtor)
      path.doesNotDependOn(boot, handler, mapper, presentation, routing, workflow, lib)
      workflow.doesNotDependOn(boot, handler, path, presentation, routing) // never the HTTP or view layers
      routing.doesNotDependOn(boot, mapper, presentation, workflow)        // routing wires handlers, not views
      presentation.doesNotDependOn(boot, handler, mapper, path, routing, workflow, lib) // data in, HTML out
    }
  }

  /**
   * What `domain` may import: the standard library, the generated Shopify data types (the
   * fulfillment matcher walks an `Order`), the contract DTOs it validates, and the rest of
   * `domain`. It is the one layer with no framework underneath it.
   */
  private val forbiddenImportsInDomain = listOf(
    "io.ktor.",
    "com.expediagroup.",
    "io.github.oshai.",   // a domain function returns its answer, it does not narrate it
    "dropnext.dss.lib.",
    "dropnext.dss.boot.",
    "dropnext.dss.handler.",
    "dropnext.dss.mapper.",
    "dropnext.dss.path.",
    "dropnext.dss.presentation.",
    "dropnext.dss.routing.",
    "dropnext.dss.workflow.",
  )

  @Test
  fun `domain package must not depend on a framework or an application layer`() {
    srcScope
      .files
      .filter { "/dropnext/dss/domain/" in normalizedPath(it.path) }
      // Strict here and in the directory-scoped rules below: after a package rename the filter matches nothing,
      // and a lenient assert passes on an empty list.
      .assertFalse(strict = true) { file ->
        val forbidden = file.imports.map { it.name }.filter { name -> forbiddenImportsInDomain.any { name.startsWith(it) } }
        if (forbidden.isNotEmpty()) {
          println(
            "ERROR: Domain file ${file.path} imports $forbidden. Move the framework-facing part to the " +
              "layer that owns it and leave `domain` holding only the model."
          )
        }
        forbidden.isNotEmpty()
      }
  }

  /**
   * What `boot/config` may not import. `Config` is read before anything else exists, so it is a function of the
   * environment map and nothing more: it reports what is wrong by failing the boot, not by logging (a line logged
   * before `main` attaches the Logflare appender reaches stdout only), and it starts no coroutine and sees no Ktor
   * type, which keeps `ConfigTest` a pure unit test. The layer rule above keeps `lib` out of it.
   */
  private val forbiddenImportsInBootConfig = listOf(
    "io.ktor.",
    "kotlinx.coroutines.",
    "io.github.oshai.",
    "org.slf4j.",
    "ch.qos.logback.",
  )

  @Test
  fun `boot_config reads the environment without a framework or a logger`() {
    srcScope
      .files
      .filter { "/dropnext/dss/boot/config/" in normalizedPath(it.path) }
      .assertFalse(strict = true) { file ->
        val forbidden = file.imports.map { it.name }.filter { name -> forbiddenImportsInBootConfig.any { name.startsWith(it) } }
        if (forbidden.isNotEmpty()) {
          println(
            "ERROR: Config file ${file.path} imports $forbidden. Fail the boot with an error instead of logging, " +
              "and build whatever needs a framework from the `Config` in `dssDependencies` or `main`."
          )
        }
        forbidden.isNotEmpty()
      }
  }

  /**
   * The Ktor server imports `boot/warmup` may have: the application and the lifecycle events the warm-up waits for.
   * The warm-up reaches the request pipeline over loopback, through a real client, because that is what warms it: the
   * CIO parser, `CallId`, `CallLogging`, `StatusPages`, the bearer provider and the HMAC all run once. Calling a route
   * or a handler directly would skip every one of them (the layer rule above forbids importing ours), and so would
   * handling an `ApplicationCall` itself.
   */
  private val allowedKtorServerImportsInBootWarmup = listOf(
    "io.ktor.server.application.Application", // `startWarmUp` extends it, to subscribe to its monitor.
    "io.ktor.server.application.ApplicationStarted", // Starts the warm-up: the outbound part needs only the graph.
    "io.ktor.server.application.ServerReady", // The inbound part waits for the socket to be bound.
  )

  @Test
  fun `boot_warmup sees no more of the Ktor server than the application lifecycle`() {
    srcScope
      .files
      .filter { "/dropnext/dss/boot/warmup/" in normalizedPath(it.path) }
      .assertFalse(strict = true) { file ->
        val forbidden = file.imports
          .map { it.name }
          .filter { it.startsWith("io.ktor.server.") }
          .filterNot { it in allowedKtorServerImportsInBootWarmup }
        if (forbidden.isNotEmpty()) {
          println(
            "ERROR: Warm-up file ${file.path} imports Ktor server types: $forbidden. Warm the pipeline through " +
              "`WarmUpLoopbackService` over loopback; a lifecycle event goes in allowedKtorServerImportsInBootWarmup."
          )
        }
        forbidden.isNotEmpty()
      }
  }

  /**
   * `/health` answers `503` until `Readiness.markReady()`, and that `503` is what keeps a cold task out of the load
   * balancer's rotation while the task it replaces keeps serving. `startWarmUp` marks it in a `finally`, once the
   * warm-up has paid the one-time cost of the first request. Any other caller opens the gate before that, and the new
   * task's first webhook times out again without anything else failing.
   */
  @Test
  fun `only startWarmUp marks the service ready`() {
    val markReadyCall = Regex("""(?<!fun )\bmarkReady\s*\(""")
    val offenders = srcFiles
      .filterNot { it.path.endsWith("/src/dropnext/dss/boot/warmup/startWarmUp.kt") }
      .filter { markReadyCall.containsMatchIn(it.code) }
      .map { it.path }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: These files mark the service ready:\n" + offenders.joinToString("\n") { "  - $it" } +
          "\nOnly `startWarmUp` may, when the warm-up has ended; put the work that must precede traffic in the `WarmUp`."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * Runtime reflection needs no import in Kotlin: `java.lang.*` is imported by default, and `javaClass` and
   * `::class.java` are members. So the rule reads the code, not the import list. What stays allowed is what the
   * compiler resolves: a `KClass` type token, a `KProperty` in a delegate signature, `typeOf`.
   */
  private val reflectionUsage = Regex(
    listOf(
      """^[ \t]*import\s+java\.lang\.reflect\.""",
      """::class\s*\.\s*java\b""",
      // The class's name for a message is not introspection; `LogflareBatchSender` names an exception by it.
      """\.javaClass\b(?!\s*\.\s*simpleName\b)""",
      """\bClass\s*\.\s*forName\s*\(""",
      """\.get(?:Declared)?(?:Method|Field|Constructor)s?\s*\(""",
      """\.newInstance\s*\(""",
      """\bkotlin\.reflect\.(?:full|jvm)\b""",
      """::class\s*\.\s*(?:members|memberProperties|memberFunctions|declaredMembers|declaredMemberProperties|declaredMemberFunctions|constructors|primaryConstructor)\b""",
      """\.callBy\s*\(""",
    ).joinToString("|"),
    RegexOption.MULTILINE,
  )

  /**
   * Files that are allowed to use reflection because they integrate with libraries that require it
   * (none currently — keep this list empty unless an unavoidable case appears).
   */
  private val reflectionAllowList = listOf<String>()

  @Test
  fun `forbid use of JVM reflection`() {
    val offenders = srcFiles
      .filterNot { pathContainsAllowListEntry(it.path, reflectionAllowList) }
      .flatMap { file ->
        reflectionUsage.findAll(file.code)
          .map { "  - ${file.path}:${file.code.lineNumberAt(it.range.first)}  ${it.value.trim()}" }
          .toList()
      }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: these files use JVM reflection:\n" + offenders.joinToString("\n") +
          "\nAvoid it. If it is unavoidable, add the file to reflectionAllowList in ArchitectureTest, with a one-line reason."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * Ktor server packages imply request/response handling and thus should not appear in workflows.
   * Outbound `io.ktor.client.*` usage is allowed — workflows may make HTTP calls, they just must
   * not handle inbound requests.
   */
  private val forbiddenKtorPrefixesInWorkflows = listOf(
    "io.ktor.server.",
    "io.ktor.http.",
  )

  @Test
  fun `workflow package must not depend on Ktor server or HTTP request types`() {
    srcScope
      .files
      .filter { "/dropnext/dss/workflow/" in normalizedPath(it.path) }
      .assertFalse(strict = true) { file ->
        val httpImports = file.imports
          .map { it.name }
          .filter { importName -> forbiddenKtorPrefixesInWorkflows.any { importName.startsWith(it) } }
        if (httpImports.isNotEmpty()) {
          println(
            "ERROR: Workflow file ${file.path} imports Ktor server / HTTP types: $httpImports. " +
              "Workflows must not handle HTTP requests; pass extracted values instead."
          )
        }
        httpImports.isNotEmpty()
      }
  }

  @Test
  fun `presentation layer does not know about Ktor server or HTTP client`() {
    // The view should take data in and return a String. It must not see ApplicationCall,
    // HttpClient, or any other transport-layer type, so it can be tested in isolation.
    val forbiddenPrefixes = listOf("io.ktor.server.", "io.ktor.client.", "io.ktor.http.", "dropnext.graphql.generated.")
    val presentationFiles = srcFiles.filter { "/src/dropnext/dss/presentation/" in it.path }
    assert(presentationFiles.isNotEmpty()) // After a package rename the sweep below would pass on nothing.
    val violations = presentationFiles
      .flatMap { file ->
        file.text.lines()
          .withIndex()
          .filter { (_, line) ->
            val trimmed = line.trim()
            trimmed.startsWith("import ") && forbiddenPrefixes.any { trimmed.contains(it) }
          }
          .map { (idx, line) -> "${file.path}:${idx + 1}  ${line.trim()}" }
      }
    assert(violations.isEmpty()) {
      "presentation/* must not depend on Ktor or the Shopify schema — keep it a pure view layer:\n" +
        violations.joinToString("\n")
    }
  }

  /**
   * Files allowed to build a `kotlinx.serialization.json.Json` instance or use the default one. Everywhere else must
   * reuse the shared `AppJson` (inbound Shopify) or `MonolithJson` (outbound monolith) singletons
   * so serialization config stays consistent.
   */
  private val jsonConstructionAllowList = listOf(
    "/dropnext/dss/lib/json/", // Where `AppJson` and `MonolithJson` are built.
  )

  /**
   * Layers allowed to import from `dropnext.graphql.generated.*`. Every Graphql operation runs
   * inside `lib/shopify` (the `ShopifyGraphqlService` methods), which answers typed results, and the
   * interface itself exposes the `Order` and `Product` snapshots plus two enums; the layers that walk
   * those snapshots are the translation boundaries and the workflows. Handlers, routing, presentation
   * and the rest program against the interface's own types. Add to this list only for a new layer.
   */
  private val graphqlGeneratedAllowList = listOf(
    // The single place that constructs and runs Graphql operations.
    "/dropnext/dss/lib/shopify/",
    // Walks the `Order`'s fulfillment orders to match shipments; pure domain logic over the snapshot.
    "/dropnext/dss/domain/fulfillment/",
    // Map the `Order` and `Product` snapshots into the monolith contract DTOs.
    "/dropnext/dss/mapper/",
    // Compose the service's primitives, so they see what the service answers: the snapshots they plan
    // mutations from and the enums they hand back in. A schema bump reaches them through the interface.
    "/dropnext/dss/workflow/",
  )

  @Test
  fun `forbid dropnext-graphql-generated imports outside lib_shopify, the translation boundaries and the workflows`() {
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, graphqlGeneratedAllowList) }
      .assertFalse { file ->
        val offending = file.imports
          .map { it.name }
          .filter { it.startsWith("dropnext.graphql.generated.") }
        if (offending.isNotEmpty()) {
          println(
            "ERROR: File ${file.path} imports Graphql-generated types: $offending. " +
              "Route Graphql calls through ShopifyGraphqlService methods so handlers and the view stay decoupled " +
              "from the Shopify Admin schema. Only lib/shopify, the translation boundaries (mapper, matcher) " +
              "and the workflows may see generated types; a new layer needs a graphqlGeneratedAllowList entry " +
              "with a one-line comment justifying it."
          )
        }
        offending.isNotEmpty()
      }
  }

  @Test
  fun `forbid ad-hoc Json instance construction outside lib_json`() {
    // Both builders (`Json { }`, `Json(from = …) { }`), imported or fully qualified, and the default instance, whose
    // settings are not ours: `Json.Default` and the `Json.encodeTo…` / `decodeFrom…` / `parseTo…` calls that run on it.
    val ownJsonInstance = Regex("""\bJson\s*[({]|\bJson\s*\.\s*(?:Default|encodeTo\w*|decodeFrom\w*|parseTo\w*)\b""")
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, jsonConstructionAllowList) }
      .assertFalse { file ->
        val constructs = ownJsonInstance.containsMatchIn(file.text.withoutComments())
        if (constructs) {
          println(
            "ERROR: File ${file.path} builds its own `Json` instance or uses the default one. " +
              "Reuse AppJson or MonolithJson from dropnext.dss.lib.json instead, " +
              "or add a new shared singleton there if a different config is genuinely needed."
          )
        }
        constructs
      }
  }

  /**
   * The MDC is thread-local and a suspended coroutine resumes on whatever thread is free: a plain `MDC.put` covers the
   * lines up to the first suspension and then leaks into whatever the thread runs next. `lib/slf4j` holds the keys and
   * `withMdcEntries`, which gets that right; everything else goes through them, `MDCContext` included, so there is one
   * way to add to the MDC.
   */
  private val mdcAccessAllowList = listOf(
    "/dropnext/dss/lib/slf4j/", // Holds the keys and `withMdcEntries`, the one way everything else adds to the MDC.
  )

  @Test
  fun `only lib_slf4j touches the MDC directly`() {
    val mdcImports = setOf("org.slf4j.MDC", "kotlinx.coroutines.slf4j.MDCContext")
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, mdcAccessAllowList) }
      .assertFalse { file ->
        val offending = file.imports.map { it.name }.filter { it in mdcImports }
        if (offending.isNotEmpty()) {
          println(
            "ERROR: File ${file.path} imports ${offending.joinToString()}. " +
              "Use `withMdcEntries` or `currentTraceId` from dropnext.dss.lib.slf4j instead."
          )
        }
        offending.isNotEmpty()
      }
  }

  /**
   * Files allowed to construct a Ktor `HttpClient(...)`. Everywhere else must receive an
   * `HttpClient` via dependency injection so engine config and OkHttp pooling stay consistent.
   */
  private val httpClientConstructionAllowList = listOf(
    "/dropnext/dss/lib/ktor/httpClientBuilders.kt", // Builds the shared client and the monolith client derived from it.
  )

  @Test
  fun `forbid ad-hoc HttpClient construction outside SharedHttpClient`() {
    // Match `HttpClient(` as a constructor call. `HttpClient` as a type reference (e.g. parameter
    // type) lacks the trailing `(`, so the pattern is precise without needing imports.
    val httpClientConstructor = Regex("""\bHttpClient\s*\(""")
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, httpClientConstructionAllowList) }
      .assertFalse { file ->
        val constructs = httpClientConstructor.containsMatchIn(file.text.withoutComments())
        if (constructs) {
          println(
            "ERROR: File ${file.path} constructs its own `HttpClient(...)`. " +
              "Inject the shared client created by `createSharedHttpClient()` instead."
          )
        }
        constructs
      }
  }

  /**
   * The process environment is read in exactly one place, `Config.fromEnv` in `boot/config/`, so the README's
   * variable table and the code cannot disagree about which variables exist. A file merely named `Config`
   * elsewhere does not qualify.
   */
  @Test
  fun `only Config reads the process environment`() {
    val environmentRead = "System." + "getenv"
    val offenders = srcScope
      .files
      .filter { environmentRead in it.text.withoutComments() }
      .filterNot { normalizedPath(it.path).endsWith("/dropnext/dss/boot/config/Config.kt") }
      .map { it.path }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: These files read the process environment directly:\n" + offenders.joinToString("\n") { "  - $it" } +
          "\nDeclare the variable in `Config` and read it from there."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * The access scopes the install requests are code (`ShopifyAccessScope`), never configuration: a change to them takes
   * a deploy and every shop's re-authorization either way, and `SHOPIFY_SCOPES` was a setting that drifted from what the
   * code needed.
   */
  @Test
  fun `the access scopes are never a setting`() {
    val scopeName = Regex("""\b[A-Z0-9_]*SCOPE[A-Z0-9_]*\b""")
    val configFiles = srcFiles.filter { "/src/dropnext/dss/boot/config/" in it.path }
    assert(configFiles.isNotEmpty()) // After a package rename the sweep below would pass on nothing.
    val inConfig = configFiles
      .flatMap { file -> scopeName.findAll(file.code).map { "  - ${file.path}: ${it.value}" } }
    val envExampleVariable = Regex("""^[#\s]*([A-Z0-9_]*SCOPE[A-Z0-9_]*)\s*=""", RegexOption.MULTILINE)
    val inEnvExample = envExampleVariable.findAll(File(projectRoot, ".env.example").readText())
      .map { "  - .env.example: ${it.groupValues[1]}" }
      .toList()
    val offenders = inConfig + inEnvExample
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: the access scopes are configured here:\n" + offenders.joinToString("\n") +
          "\nThey are hardcoded in `ShopifyAccessScope`; change them there."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * Shopify refuses an operation that declares a variable it never uses, on every call and for every shop. The client
   * generator does not apply that rule and the fakes do not either, so without this check the refusal is first seen in
   * production. The typical cause is a page size added as a variable while the selection still reads another one.
   */
  @Test
  fun `every variable a Graphql operation declares is used in it`() {
    val operationFiles = File(projectRoot, "src/resources").listFiles { file -> file.extension == "graphql" }.orEmpty()
    assert(operationFiles.isNotEmpty()) // After a move of the operations the sweep below would pass on nothing.
    val comment = Regex("#[^\n]*")
    val declaration = Regex("""\$(\w+)\s*:""")
    val offenders = operationFiles.sortedBy { it.name }.flatMap { file ->
      val text = comment.replace(file.readText(), "")
      val headerEnd = text.indexOf('{')
      val header = text.substring(0, headerEnd)
      val body = text.substring(headerEnd)
      declaration.findAll(header)
        .map { it.groupValues[1] }
        .filterNot { name -> Regex("""\$$name\b""").containsMatchIn(body) }
        .map { name -> "  - ${file.name}: \$$name" }
        .toList()
    }
    if (offenders.isNotEmpty()) {
      println("ERROR: these Graphql variables are declared but never used:\n" + offenders.joinToString("\n"))
    }
    assert(offenders.isEmpty())
  }

  /**
   * A secret renders as `"***"` and nothing else, and it must not be able to leave the process
   * through serialization. A secret that quietly serialized itself into a payload or interpolated
   * itself into a log line would not be visible to a reviewer; this rule is.
   */
  @Test
  fun `every secret type redacts its toString and is not Serializable`() {
    val secretsFile = srcScope.files.single { it.name == "Secrets" }.text
    val declaration = Regex("""((?:@\w+\s+)*)value class (\w+)\(val value: String\)([^{]*)\{([^}]*)\}""")
    val declarations = declaration.findAll(secretsFile).toList()
    assert(declarations.size >= 4) // Guards against the sweep silently walking an empty list.

    val offenders = declarations.mapNotNull { match ->
      val (annotations, name, supertypes, body) = match.destructured
      when {
        "@Serializable" in annotations -> "  - $name is @Serializable"
        supertypes.trim().isNotEmpty() -> "  - $name implements${supertypes.trimEnd()}"
        """override fun toString() = "***"""" !in body -> "  - $name does not redact its toString"
        else -> null
      }
    }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: these types in domain/Secrets.kt break the secret contract:\n" + offenders.joinToString("\n") +
          "\nA secret implements no interface, is not @Serializable (so it cannot end up in a payload), and renders as \"***\"."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * The other half of the rule above: `@Serializable` is not inherited, so a wire DTO carrying a
   * secret-typed property would serialize the secret itself. The contract DTOs deliberately keep
   * a `String` for the token and the handler wraps at the boundary.
   */
  @Test
  fun `no serializable class in src carries a secret as a property`() {
    val secretNames = Regex("""value class (\w+)\(val value: String\)""")
      .findAll(srcScope.files.single { it.name == "Secrets" }.text)
      .map { it.groupValues[1] }
      .toList()
    val secretMention = Regex(""":\s*(${secretNames.joinToString("|")})\??\s*(?:[,)=]|$)""")

    val offenders = srcScope.files.flatMap { file ->
      serializableClassConstructorsIn(file.text)
        .filter { (_, constructor) -> secretMention.containsMatchIn(constructor) }
        .map { (name, _) -> "  - $name in ${file.path}" }
    }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: these @Serializable classes carry a secret as a property:\n" + offenders.joinToString("\n") +
          "\nKeep the property a String and wrap it after the boundary."
      )
    }
    assert(offenders.isEmpty())
  }

  /**
   * `CLAUDE.md` has every JSON body use snake_case keys, named per property. `AppJson` has no naming strategy, so a
   * property without `@SerialName` goes on the wire under its Kotlin name. The contract DTOs are generated under
   * `build/`, outside this scope, and follow the monolith's spec.
   */
  @Test
  fun `serializable classes spell their JSON keys in snake_case`() {
    val property = Regex("""\bva[lr]\s+(\w+)\s*:""")
    val serialName = Regex("""@SerialName\("([^"]*)"\)""")
    val offenders = srcScope.files.flatMap { file ->
      serializableClassConstructorsIn(file.text.withoutComments()).flatMap { (className, constructor) ->
        val properties = property.findAll(constructor).toList()
        properties.mapIndexedNotNull { index, match ->
          // What stands between the previous property and this one holds this one's annotations.
          val annotations = constructor.substring(if (index == 0) 0 else properties[index - 1].range.last + 1, match.range.first)
          val name = match.groupValues[1]
          val key = serialName.find(annotations)?.groupValues?.get(1)
          when {
            key == null && name.any(Char::isUpperCase) -> "  - $className.$name in ${file.path} has no @SerialName"
            key != null && !snakeCaseKey.matches(key) -> "  - $className.$name in ${file.path} is named \"$key\""
            else -> null
          }
        }
      }
    }
    if (offenders.isNotEmpty()) {
      println(
        "ERROR: these @Serializable properties do not spell their JSON key in snake_case:\n" + offenders.joinToString("\n") +
          "\nAnnotate a multi-word property with @SerialName(\"snake_case_name\"); a format someone else owns that is not " +
          "snake_case needs an allowlist here, with a one-line reason."
      )
    }
    assert(offenders.isEmpty())
  }

  private val snakeCaseKey = Regex("""[a-z][a-z0-9]*(?:_[a-z0-9]+)*""")

  /**
   * The `@Serializable` class declarations with their constructor text: a `data class` as much as a plain one, and the
   * annotation above the declaration as much as on its line. A shape these rules do not see is a shape they do not keep.
   */
  private fun serializableClassConstructorsIn(text: String): List<Pair<String, String>> =
    Regex("""\bclass (\w+)\(""").findAll(text).filter { match ->
      val before = text.substring(0, match.range.first).split('\n')
      val annotationsAbove = before.dropLast(1).reversed().takeWhile { it.trim().startsWith("@") }
      "@Serializable" in before.last() || annotationsAbove.any { "@Serializable" in it }
    }.map { match ->
      var depth = 1
      var index = match.range.last + 1
      while (index < text.length && depth > 0) {
        when (text[index]) {
          '(' -> depth++
          ')' -> depth--
        }
        index++
      }
      match.groupValues[1] to text.substring(match.range.last + 1, index - 1)
    }.toList()

  private fun String.lineNumberAt(index: Int): Int = 1 + (0 until index).count { this[it] == '\n' }

  /**
   * Packages allowed to be star-imported. Mirrors `ij_kotlin_packages_to_use_import_on_demand`
   * in `.editorconfig` — `kotlinx.html` is an HTML-builder eDSL whose ergonomics depend on
   * pulling in all tag/attribute functions at once.
   */
  private val allowedWildcardImportPackages = listOf(
    "kotlinx.html", // The HTML-builder eDSL, one function per tag and attribute.
  )

  @Test
  fun `main sources do not use wildcard imports`() {
    // Konsist's KoImport.name strips the trailing `.*`, so a Konsist-based check silently passes.
    // We grep the source files directly — no extra dependency, no false negatives.
    val wildcardImportLine = Regex("""^\s*import\s+([\w.]+)\.\*\s*$""")
    val violations = srcFiles
      .flatMap { file ->
        file.text.lines()
          .withIndex()
          .mapNotNull { (idx, line) ->
            val match = wildcardImportLine.matchEntire(line) ?: return@mapNotNull null
            val pkg = match.groupValues[1]
            if (pkg in allowedWildcardImportPackages) null
            else "${file.path}:${idx + 1}  ${line.trim()}"
          }
      }
    assert(violations.isEmpty()) {
      "Wildcard imports are forbidden in production sources — use explicit imports:\n" +
        violations.joinToString("\n")
    }
  }

  @Test
  fun `no hand-written Kotlin under the contract package`() {
    // The contract DTOs are generated by openApiGenerate from monolith-dss-openapi.json into
    // `build/`; a hand-written copy under `src/` would drift from the spec.
    val handWritten = srcScope
      .files
      .filter { "/dropnext/dss/contract/" in normalizedPath(it.path) }
    assert(handWritten.isEmpty()) {
      "Contract DTOs belong in the OpenAPI codegen only: ${handWritten.map { it.path }}"
    }
  }

  @Test
  fun `OutBoundMonolithPaths is generated not hand-written`() {
    val handWritten = srcScope
      .files
      .filter { it.name == "OutBoundMonolithPaths.kt" }
    assert(handWritten.isEmpty()) {
      "OutBoundMonolithPaths.kt is generated from monolith-dss-openapi.json: ${handWritten.map { it.path }}"
    }
  }

  /**
   * A file whose main declaration is a type is `PascalCase.kt`; a file of top-level functions is
   * `lowerCamel.kt` after its main function. The mirror rule below relies on it, and so does a
   * reader looking for `installShop` under `workflow/`. A PascalCase file may also group a family
   * of types (`Secrets.kt`, `ShopifyIds.kt`) or declare one top-level value (`AppJson.kt`).
   */
  @Test
  fun `a file of top-level functions is named lowerCamel and a file of types PascalCase`() {
    val typeDeclaration = Regex("""^(?:@\w+\s+)*(?:public |internal |private )?(?:sealed |data |value |enum |abstract |open )*(?:class|interface|object) (\w+)""", RegexOption.MULTILINE)
    val offenders = srcScope.files.mapNotNull { file ->
      val declaredTypes = typeDeclaration.findAll(file.text).map { it.groupValues[1] }.toList()
      val declaresValueNamedAfterFile = Regex("""^(?:internal |private )?val ${file.name}\b""", RegexOption.MULTILINE).containsMatchIn(file.text)
      val startsUpper = file.name.first().isUpperCase()
      when {
        startsUpper && declaredTypes.isEmpty() && !declaresValueNamedAfterFile ->
          "  - ${file.path}: PascalCase but declares no type; name it lowerCamel after its main function"
        !startsUpper && declaredTypes.any { it == file.name.replaceFirstChar(Char::uppercaseChar) } ->
          "  - ${file.path}: lowerCamel but its main declaration is a type; name it PascalCase"
        else -> null
      }
    }
    if (offenders.isNotEmpty()) {
      println("ERROR: these files break the file naming rule:\n" + offenders.joinToString("\n"))
    }
    assert(offenders.isEmpty())
  }

  /**
   * An exemption outlives what it exempted: a path nothing lives at any more, or an import nothing makes, silently
   * exempts whatever is put there next. Every allowlist in this class is listed here; a new one joins it.
   */
  @Test
  fun `every allowlist entry still matches something in src`() {
    val paths = srcFiles.map { it.path }
    val pathAllowLists = mapOf(
      "reflectionAllowList" to reflectionAllowList,
      "jsonConstructionAllowList" to jsonConstructionAllowList,
      "graphqlGeneratedAllowList" to graphqlGeneratedAllowList,
      "mdcAccessAllowList" to mdcAccessAllowList,
      "httpClientConstructionAllowList" to httpClientConstructionAllowList,
    )
    val stalePaths = pathAllowLists.flatMap { (listName, entries) ->
      entries.filter { entry -> paths.none { entry in it } }.map { "  - $listName: $it" }
    }
    val warmUpImports = srcFiles.filter { "/src/dropnext/dss/boot/warmup/" in it.path }.flatMap { importsOf(it.code) }
    val staleWarmUpImports = allowedKtorServerImportsInBootWarmup
      .filterNot { it in warmUpImports }
      .map { "  - allowedKtorServerImportsInBootWarmup: $it" }
    val wildcardPackages = srcFiles.flatMap { importsOf(it.code) }.filter { it.endsWith(".*") }.map { it.removeSuffix(".*") }
    val staleWildcards = allowedWildcardImportPackages
      .filterNot { it in wildcardPackages }
      .map { "  - allowedWildcardImportPackages: $it" }
    val stale = stalePaths + staleWarmUpImports + staleWildcards
    if (stale.isNotEmpty()) {
      println(
        "ERROR: these allowlist entries match nothing in src/:\n" + stale.joinToString("\n") +
          "\nStale exemption: drop it from the allowlist."
      )
    }
    assert(stale.isEmpty())
  }

  /** What a file imports, a star import with its `.*`, read from the code so a commented-out import does not count. */
  private fun importsOf(code: String): List<String> =
    Regex("""^[ \t]*import\s+(\w+(?:\.\w+)*(?:\.\*)?)""", RegexOption.MULTILINE).findAll(code).map { it.groupValues[1] }.toList()
}
