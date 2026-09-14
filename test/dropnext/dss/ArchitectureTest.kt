package dropnext.dss

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import dropnext.dss.testutil.helper.kotlinSourceFileTexts
import dropnext.dss.testutil.helper.normalizedPath
import dropnext.dss.testutil.helper.pathContainsAllowListEntry
import dropnext.dss.testutil.helper.withoutComments
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
      .assertFalse { file ->
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
      .assertFalse { file ->
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
    "io.ktor.server.application.Application",
    "io.ktor.server.application.ApplicationStarted",
    "io.ktor.server.application.ServerReady",
  )

  @Test
  fun `boot_warmup sees no more of the Ktor server than the application lifecycle`() {
    srcScope
      .files
      .filter { "/dropnext/dss/boot/warmup/" in normalizedPath(it.path) }
      .assertFalse { file ->
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

  /** Reflection-related imports that are always forbidden in production code. */
  private val reflectionImports = listOf(
    "java.lang.reflect.",
    "java.lang.Class",
  )

  /**
   * `kotlin.reflect` imports allowed because they are used by Kotlin's property-delegate protocol
   * and for compile-time property-name access (`.name`), not for runtime introspection.
   */
  private val allowedKotlinReflectImports = listOf(
    "kotlin.reflect.KProperty",
    "kotlin.reflect.KProperty0",
    "kotlin.reflect.KProperty1",
    "kotlin.reflect.KProperty2",
  )

  /**
   * Source patterns that indicate actual runtime reflection (as opposed to just using `KProperty`
   * for delegate signatures or `.name` access).
   */
  private val reflectionUsagePatterns = listOf(
    Regex("""\.\s*get\s*\("""),          // KProperty.get(instance)
    Regex("""\.\s*set\s*\("""),          // KMutableProperty.set(instance, value)
    Regex("""\.\s*call\s*\("""),         // KCallable.call(...)
    Regex("""\.\s*callBy\s*\("""),       // KCallable.callBy(...)
    Regex("""::class\s*\.\s*members"""),
    Regex("""::class\s*\.\s*memberProperties"""),
    Regex("""::class\s*\.\s*memberFunctions"""),
    Regex("""::class\s*\.\s*declaredMembers"""),
    Regex("""::class\s*\.\s*declaredMemberProperties"""),
    Regex("""::class\s*\.\s*declaredMemberFunctions"""),
  )

  /**
   * Files that are allowed to use reflection because they integrate with libraries that require it
   * (none currently — keep this list empty unless an unavoidable case appears).
   */
  private val reflectionAllowList = listOf<String>()

  @Test
  fun `forbid use of JVM reflection`() {
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, reflectionAllowList) }
      .assertFalse { file ->
        val importNames = file.imports.map { it.name }

        val forbiddenImports = importNames
          .filter { importName -> reflectionImports.any { importName.startsWith(it) } }

        val forbiddenKotlinReflectImports = importNames
          .filter { it.startsWith("kotlin.reflect.") }
          .filter { importName -> allowedKotlinReflectImports.none { allowed -> importName == allowed } }

        val hasAllowedKotlinReflectImport = importNames.any { importName ->
          allowedKotlinReflectImports.any { allowed -> importName == allowed }
        }
        val hasReflectionUsage = hasAllowedKotlinReflectImport &&
          reflectionUsagePatterns.any { it.containsMatchIn(file.text.withoutComments()) }

        val allOffending = forbiddenImports + forbiddenKotlinReflectImports
        if (allOffending.isNotEmpty()) {
          println(
            "ERROR: File ${file.path} uses reflection imports: $allOffending. " +
              "Avoid JVM reflection. If unavoidable, add the file to reflectionAllowList in ArchitectureTest."
          )
        }
        if (hasReflectionUsage) {
          println(
            "ERROR: File ${file.path} imports allowed KProperty types but uses them for runtime reflection. " +
              "Only use KProperty for delegate signatures and .name access."
          )
        }
        allOffending.isNotEmpty() || hasReflectionUsage
      }
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
      .assertFalse { file ->
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
    val violations = srcFiles.filter { "/src/dropnext/dss/presentation/" in it.path }
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
   * Files allowed to construct a `kotlinx.serialization.Json {}` instance. Everywhere else must
   * reuse the shared `AppJson` (inbound Shopify) or `MonolithJson` (outbound monolith) singletons
   * so serialization config stays consistent.
   */
  private val jsonConstructionAllowList = listOf(
    "/dropnext/dss/lib/json/",
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
    val jsonConstructor = Regex("""\bJson\s*\{""")
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, jsonConstructionAllowList) }
      .assertFalse { file ->
        val hasJsonImport = file.imports.any { it.name == "kotlinx.serialization.json.Json" }
        val constructs = hasJsonImport && jsonConstructor.containsMatchIn(file.text.withoutComments())
        if (constructs) {
          println(
            "ERROR: File ${file.path} constructs its own `Json { ... }` instance. " +
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
  @Test
  fun `only lib_slf4j touches the MDC directly`() {
    val mdcImports = setOf("org.slf4j.MDC", "kotlinx.coroutines.slf4j.MDCContext")
    srcScope
      .files
      .filterNot { file -> pathContainsAllowListEntry(file.path, listOf("/dropnext/dss/lib/slf4j/")) }
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
    "/dropnext/dss/lib/ktor/httpClientBuilders.kt",
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

  /** The `@Serializable data class Xxx(...)` declarations with their constructor text. */
  private fun serializableClassConstructorsIn(text: String): List<Pair<String, String>> =
    Regex("""data class (\w+)\(""").findAll(text).filter { match ->
      text.substring(0, match.range.first).split('\n').dropLast(1).reversed()
        .takeWhile { it.trim().startsWith("@") }
        .any { "@Serializable" in it }
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

  /**
   * Packages allowed to be star-imported. Mirrors `ij_kotlin_packages_to_use_import_on_demand`
   * in `.editorconfig` — `kotlinx.html` is an HTML-builder eDSL whose ergonomics depend on
   * pulling in all tag/attribute functions at once.
   */
  private val allowedWildcardImportPackages = listOf(
    "kotlinx.html",
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
}
