import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql
import groovy.json.JsonSlurper
import org.gradle.api.JavaVersion.VERSION_25
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile


plugins {
  alias(libs.plugins.kotlinJvm)

  // Running this compiler plugin prevents the need for using the JVM's reflection API (directly, or through `kotlin-reflect`).
  alias(libs.plugins.kotlinXSerialization)

  // Improves the DX on `assert` errors significantly (shows what went wrong).
  alias(libs.plugins.kotlinPowerAssert)

  // Generates typed Kotlin classes from the Shopify Admin Graphql schema and our `.graphql` query files.
  alias(libs.plugins.kotlinGraphql)

  // Generates DTOs from `openapi.json` so request/response shapes stay in sync with the monolith spec.
  alias(libs.plugins.openapiGenerator)

  // Code coverage reports; JaCoCo's Java agent instruments bytecode at test runtime only (no production deps).
  // Opt-in: run `./gradlew jacocoTestReport -Pcoverage`; output lands in `build/reports/jacoco/test/`.
  jacoco

  application
}

buildscript {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(25))
  }
}

application {
  mainClass.set("dropnext.dss.AppKt")

  // The JVM runs in UTC, everywhere: some libraries read the default timezone when converting
  // between time formats, and a host-dependent default is a bug that only shows up in one
  // environment. It also fixes the timezone of the `%d` in `logback.xml`, so a developer's log
  // line and a Fargate log line are read the same way. Baked into the start script the
  // `application` plugin generates, which is what the Dockerfile's ENTRYPOINT runs.
  applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

repositories {
  mavenCentral()
}

// Got tired of the src/main/kotlin/... path prefix.
// Since this is not a library, we don't care for the Maven convention.
sourceSets {
  main {
    kotlin { srcDir("src") }
    resources.srcDirs("src/resources")
  }
  test {
    kotlin { srcDir("test") }
    // Without this the test resources default to `src/test/resources` (which does not exist),
    // and anything dropped in `test/resources/` (e.g.: a fixture or a `junit-platform.properties`)
    // never reaches the classpath, silently.
    resources.srcDirs("test/resources")
  }
}

tasks {
  withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      // Better be strict; use suppressions to get rid of legitimate warnings.
      allWarningsAsErrors = true

      jvmTarget.set(JVM_25)
      freeCompilerArgs.addAll(
        "-opt-in=kotlin.uuid.ExperimentalUuidApi", // more specific than "-opt-in=kotlin.RequiresOptIn"
        "-opt-in=kotlin.time.ExperimentalTime",    // is very stable and works well with kotlinx.serialization
        "-Xbackend-threads=8",                     // Use multiple compiler threads
      )
    }
  }

  withType<Test> {
    useJUnitPlatform()

    // JaCoCo attaches its agent to every `Test` task by default, so a plain `./gradlew test` paid
    // on-the-fly instrumentation — the generated Graphql client, Konsist's embedded compiler — to
    // write an exec file nothing then read. Coverage is opt-in:
    //   ./gradlew jacocoTestReport -Pcoverage
    val coverageRequested = providers.gradleProperty("coverage").isPresent
    extensions.configure<JacocoTaskExtension> { isEnabled = coverageRequested }
    if (coverageRequested) {
      // Gradle does not track a task's extension state as an input, so the run that turns coverage
      // on is exactly the run that would be skipped as up-to-date — leaving the report no exec data.
      outputs.upToDateWhen { false }
    }

    // One fork; the classes run concurrently inside it (`test/resources/junit-platform.properties`), which shares
    // the JVM's warm-up and the Konsist parse instead of paying them per fork.
    maxParallelForks = 1

    // The HTML report is megabytes of small files per run for something a human opens rarely. The
    // XML always stays: the IDEs read it, and so does anything that inspects timings after a run.
    //   ./gradlew test -Ptest.htmlReport
    reports.html.required.set(providers.gradleProperty("test.htmlReport").isPresent)

    jvmArgs(
      // The same UTC default `applicationDefaultJvmArgs` gives the application: without it the test
      // JVM would run in the host's timezone and only agree with production by luck.
      "-Duser.timezone=UTC",

      "-Xms512m",
      "-Xmx768m",
      "-XX:MaxMetaspaceSize=256m",

      // Konsist's bundled `kotlin-compiler-embeddable` (2.0.21, not our 2.3.21) calls
      // `sun.misc.Unsafe::objectFieldOffset`, which JDK 25 terminally deprecated: four WARNING lines
      // per test run, from a dependency we do not control. Remove once Konsist ships a compiler that
      // no longer calls it — drop this line, and if `./gradlew test` stays quiet it is no longer needed.
      "--sun-misc-unsafe-memory-access=allow",
    )
  }

  java {
    sourceCompatibility = VERSION_25
    targetCompatibility = VERSION_25
  }
}

dependencies {
  // Shopify Admin API Graphql client (typed Kotlin classes generated from `.graphql` queries).
  implementation(libs.graphqlKotlinKtorClient)
  implementation(libs.graphqlKotlinClientSerialization)

  // Ktor (HTTP server + client).
  implementation(libs.ktorClientOkhttp)
  implementation(libs.ktorClientContentNegotiation)
  implementation(libs.ktorSerializationKotlinxJson)
  implementation(libs.ktorServerCio)
  implementation(libs.ktorServerStatusPages)
  implementation(libs.ktorServerContentNegotiation)
  implementation(libs.ktorServerCallLogging)
  implementation(libs.ktorServerCallId)            // `CallId` + `callIdMdc`: the per-request trace id, in the MDC across suspensions
  implementation(libs.ktorServerRequestValidation) // Runs the domain validators on decoded bodies; failures become 400s in `StatusPages`
  implementation(libs.ktorServerBodyLimit)         // Caps request bodies before they are buffered; the webhook route is unauthenticated until the HMAC is checked
  implementation(libs.ktorServerAuth)
  implementation(libs.ktorClientCallId)            // Forwards the trace id to the monolith as `X-Trace-Id`

  // HTML rendering for the OAuth install success page (no reflection).
  implementation(libs.kotlinxHtml)

  // Typed Result: every outbound call (Shopify, monolith) answers `Result<T, Error>` (kotlin-stdlib only).
  implementation(libs.result4k)

  // Logging
  implementation(libs.kotlinLogging)  // Kotlinesque wrapper over SLF4J
  implementation(libs.slf4jApi)       // facade API (also used directly for MDC)
  implementation(libs.kotlinxCoroutinesSlf4j) // `MDCContext`: the MDC across suspensions outside a request (the warm-up); Ktor's call logging already ships it
  implementation(libs.logbackClassic) // Logback backend (configured via src/resources/logback.xml)

  // Test dependencies (see also the `power-assert` plugin definition, makes errors more actionable)
  testImplementation(kotlin("test-junit5")) // Umbrella package that pulls in lots of other testing libs
  testRuntimeOnly(libs.junitJupiterEngine) // Needed separately when using JUnit5
  testImplementation(libs.konsist) // For architecture tests, among other features
  testImplementation(libs.ktorServerTestHost) // In-memory Ktor test engine (`testApplication { … }`)
}

// Prints the `SlowestClassesFirstOrderer` hint list from the last test run, longest first.
tasks.register("slowestTestClasses") {
  group = "verification"
  description = "Prints the SlowestClassesFirstOrderer hint list from the last test run."
  val resultsDir = layout.buildDirectory.dir("test-results/test")
  doLast {
    val results = resultsDir.get().asFile
    require(results.isDirectory) { "No test results in $results: run ./gradlew test first." }
    val attribute = { header: String, name: String ->
      Regex("$name=\"([^\"]*)\"").find(header)?.groupValues?.get(1).orEmpty()
    }
    val slowest = results.listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
      .orEmpty()
      .map { file ->
        val header = file.readText().substringAfter("<testsuite ").substringBefore(">")
        attribute(header, "time").toDouble() to attribute(header, "name")
      }
      .filter { (seconds, _) -> seconds >= 1.0 }
      .sortedByDescending { (seconds, _) -> seconds }
    println(slowest.joinToString("\n") { (seconds, name) -> "      \"$name\", // %.2fs".format(seconds) })
  }
}

// Reproducible builds: every configuration's resolved dependencies get locked to `gradle/dependency-locks/`.
// Refresh locks with `./gradlew dependencies --write-locks`.
dependencyLocking {
  lockAllConfigurations()
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
  functions.addAll(listOf("kotlin.assert", "kotlin.require", "kotlin.check"))
}

jacoco {
  toolVersion = libs.versions.jacocoVersion.get()
}

// Without `-Pcoverage` the agent never attached, so there is no exec data. `jacocoTestReport` cannot
// say so itself: its `executionData` is `@SkipWhenEmpty`, so an empty run leaves the task SKIPPED and
// the build green with no report and no explanation. This runs either way, and fails with the fix.
val verifyCoverageRequested by tasks.registering {
  description = "Fails when jacocoTestReport is asked for without -Pcoverage."

  val coverageRequested = providers.gradleProperty("coverage").isPresent
  doFirst {
    require(coverageRequested) {
      "No coverage data is collected by default. Run: ./gradlew jacocoTestReport -Pcoverage"
    }
  }
}

// Fail the missing-`-Pcoverage` case before paying for a full test run, not after it.
tasks.test {
  mustRunAfter(verifyCoverageRequested)
}

val monolithContractGeneratedDtoPath = "dropnext/dss/contract"
val monolithPathsGeneratedDir = layout.buildDirectory.dir("generated/monolith-paths")
val monolithPathsGeneratedFile =
  layout.buildDirectory.file("generated/monolith-paths/dropnext/dss/lib/monolith/OutBoundMonolithPaths.kt")

tasks.named<JacocoReport>("jacocoTestReport") {
  // So `./gradlew jacocoTestReport` runs the tests too; otherwise it would silently report on stale exec data.
  dependsOn(tasks.named("test"), verifyCoverageRequested)

  reports {
    html.required.set(true)
    xml.required.set(true) // Useful for any future CI integrations (Codecov, SonarQube, etc.).
    csv.required.set(false)
  }
  classDirectories.setFrom(
    files(
      classDirectories.files.map { dir ->
        fileTree(dir) {
          // Exclude generated OpenAPI DTOs (not authored by us).
          exclude("$monolithContractGeneratedDtoPath/**")

          // Exclude the Graphql client `graphqlGenerateClient` emits from `src/resources/*.graphql`:
          // thousands of `@Serializable` data holders, also not authored by us.
          exclude("dropnext/graphql/**")

          // Exclude the path constants `generateOutBoundMonolithPaths` emits. Generated, and every
          // `const val` in them is inlined at the call site, so the object itself is never loaded —
          // it would report as wholly uncovered while saying nothing about what is tested.
          exclude("dropnext/dss/lib/monolith/OutBoundMonolithPaths*")

          // Exclude the application bootstrap (main, shutdown hook, config summary): not unit-testable.
          exclude("dropnext/dss/AppKt*")
        }
      }
    )
  )
}

// The committed schema: what the generated client compiles against, and what the IDE's Graphql
// plugin reads. Refreshed by `graphqlIntrospectSchema` below, never as part of a normal build.
val shopifyAdminSchemaFile: File = layout.projectDirectory.file("src/graphql-schema/schema.graphql").asFile

graphql {
  client {
    packageName = "dropnext.graphql.generated"
    serializer = GraphQLSerializer.KOTLINX

    // `schemaFile` rather than `endpoint` on purpose: setting `endpoint` here makes the plugin wire
    // `graphqlGenerateClient` onto `graphqlIntrospectSchema`, which puts a network call to
    // shopify.dev in every build — one that also rewrites the committed schema underneath you, so a
    // Shopify-side edit lands in your working tree as an unrelated diff. Codegen reads the committed
    // file; refreshing it is the deliberate, manual step below.
    schemaFile = shopifyAdminSchemaFile

    // A deprecated field in an operation fails the codegen, so it is fixed now rather than discovered when the API
    // version that removes it arrives. Deprecated input fields and later deprecations are out of its sight; the
    // `ShopifyDeprecationWarnings` client plugin logs those when Shopify reports them.
    allowDeprecatedFields = false

    // Prevents "To prevent Denial Of Service attacks, parsing has been canceled" errors on Shopify's huge schema.
    parserOptions {
      maxCharacters = 25000000
      maxTokens = 250000
    }
  }
}

// Refreshes the committed schema: `./gradlew graphqlIntrospectSchema graphqlGenerateClient`.
// Reads Shopify's public schema proxy, which needs no token. Bumping the version here means bumping
// it in the other places `.claude/rules/graphql.md` lists.
tasks.graphqlIntrospectSchema {
  // endpoint = "https://beta.pokeapi.co/graphql/v1beta"
  endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2026-04"
  outputFile = shopifyAdminSchemaFile

  // Asking for a refresh means asking for a download. Gradle would otherwise call the task
  // up-to-date whenever the endpoint is unchanged and the file exists — which is every refresh that
  // is not a version bump, i.e. exactly the ones that pick up Shopify's edits within a version.
  // Free: nothing depends on this task, so it only ever runs when named on the command line.
  outputs.upToDateWhen { false }
}

tasks.graphqlGenerateClient {
  // The plugin defaults to `src/main/resources`; we moved resources to `src/resources`.
  queryFileDirectory.set(layout.projectDirectory.dir("src/resources"))

  // Codegen reads the file introspection writes, so on the one command that runs both
  // (`graphqlIntrospectSchema graphqlGenerateClient`) Gradle needs the order spelled out. Not a
  // `dependsOn`: that is exactly the build-time network call this arrangement removes.
  mustRunAfter(tasks.graphqlIntrospectSchema)
}

private val openApiSpecFile: File =
  layout.projectDirectory.file("src/resources/monolith-dss-openapi.json").asFile
    .let { if (it.isFile && it.canRead() && it.length() > 0L) it else null }
    ?: throw GradleException(
      """
        OpenAPI spec not found. Expected (non-empty):
          ${layout.projectDirectory.asFile.absolutePath}${File.separator}src${File.separator}resources${File.separator}monolith-dss-openapi.json
        Docker: COPY src/resources/monolith-dss-openapi.json before running Gradle (see Dockerfile).
      """.trimIndent(),
    )

// Generates `OutBoundMonolithPaths` from the same monolith OpenAPI spec as DTO codegen
// (`apis=false` skips path constants in openApiGenerate).
val generateOutBoundMonolithPaths by tasks.registering {
  group = "build"
  description = "Generates OutBoundMonolithPaths.kt from monolith-dss-openapi.json."

  val source = openApiSpecFile
  val outputFile = monolithPathsGeneratedFile

  inputs.file(source)
  outputs.file(outputFile)

  doLast {
    @Suppress("UNCHECKED_CAST")
    val spec = JsonSlurper().parseText(source.readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val paths = spec["paths"] as? Map<String, Map<String, Any?>>
      ?: throw GradleException("OpenAPI spec missing non-empty 'paths' object")

    @Suppress("UNCHECKED_CAST")
    val servers = spec["servers"] as? List<Map<String, Any?>>
    val apiPathPrefix = servers
      ?.firstOrNull()
      ?.get("url")
      ?.toString()
      ?.trim()
      ?.trimEnd('/')
      ?.takeIf { it.isNotEmpty() }

    fun segmentToConstPart(segment: String, isFirstSegment: Boolean): String =
      segment.split('-')
        .filter { it.isNotEmpty() }
        .mapIndexed { wordIndex, word ->
          val lower = word.lowercase()
          if (isFirstSegment && wordIndex == 0) lower
          else lower.replaceFirstChar(Char::uppercaseChar)
        }
        .joinToString("")

    fun pathToConstName(path: String): String =
      path.trim('/')
        .split('/')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, segment -> segmentToConstPart(segment, isFirstSegment = index == 0) }
        .joinToString("")

    val httpMethodOrder = listOf("delete", "get", "patch", "post", "put")

    // The monolith mounts its contract under `servers[0].url` and http4k bakes that mount prefix
    // into every path key as well, so the served document names `/api/shopify-service/v1/orders`
    // under a server of `/api/shopify-service/v1` (a to-do on the monolith side). The constants are
    // appended to `MONOLITH_API_PREFIX`, which is that same prefix, so it is stripped here once.
    fun withoutServerPrefix(path: String): String =
      if (apiPathPrefix != null && path.startsWith("$apiPathPrefix/")) path.removePrefix(apiPathPrefix) else path

    val pathEntries = paths.keys.sorted().map { fullPath ->
      val path = withoutServerPrefix(fullPath)
      val operations = paths[fullPath].orEmpty()
      val kdocLines = operations.keys
        .filter { it in httpMethodOrder }
        .sortedBy { httpMethodOrder.indexOf(it) }
        .map { method ->
          @Suppress("UNCHECKED_CAST")
          val op = operations[method] as? Map<String, Any?>
          val summary = op?.get("summary")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
          val summarySuffix = summary?.let { " — $it" }.orEmpty()
          "   * `${method.uppercase()} $path`$summarySuffix"
        }
      Triple(pathToConstName(path), path, kdocLines)
    }

    val duplicateNames = pathEntries.groupBy { it.first }.filter { it.value.size > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
      throw GradleException(
        "OpenAPI paths map to duplicate OutBoundMonolithPaths constant names: $duplicateNames",
      )
    }

    val body = buildString {
      appendLine("package dropnext.dss.lib.monolith")
      appendLine()
      appendLine("/**")
      appendLine(" * Outbound paths the DSS calls on the DropNext monolith. Appended to")
      appendLine(" * `Config.monolithBaseUrl` (+ optional `Config.monolithApiPrefix`) by `HttpMonolithService`.")
      appendLine(" *")
      appendLine(" * **Generated** from `src/resources/monolith-dss-openapi.json` by the")
      appendLine(" * `generateOutBoundMonolithPaths` Gradle task — do not edit by hand.")
      appendLine(" */")
      appendLine("@Suppress(\"ConstPropertyName\") // Less shouty field names.")
      appendLine("object OutBoundMonolithPaths {")

      if (apiPathPrefix != null) {
        appendLine("  /** OpenAPI `servers[0].url` — typical value for `MONOLITH_API_PREFIX`. */")
        appendLine("  const val apiPathPrefix = \"$apiPathPrefix\"")
        appendLine()
      }

      pathEntries.forEach { (constName, path, kdocLines) ->
        appendLine("  /**")
        kdocLines.forEach { appendLine(it) }
        appendLine("   */")
        appendLine("  const val $constName = \"$path\"")
        appendLine()
      }

      appendLine("}")
    }

    val target = outputFile.get().asFile
    target.parentFile.mkdirs()
    target.writeText(body.trimEnd() + "\n")
  }
}

openApiGenerate {
  generatorName.set("kotlin")
  // file: URI — required on Windows when validateSpec is enabled (absolute paths break $ref resolution).
  inputSpec.set(openApiSpecFile.toURI().toString())
  skipValidateSpec.set(false)
  outputDir.set("${layout.buildDirectory.get()}/generated/openapi")
  modelPackage.set(monolithContractGeneratedDtoPath.replace('/', '.'))
  generateApiTests.set(false)
  generateModelTests.set(false)
  globalProperties.set(
    mapOf(
      "models" to "",
      "apis" to "false",
      "supportingFiles" to "false",
    )
  )
  configOptions.set(
    mapOf(
      "library" to "jvm-ktor",
      "serializationLibrary" to "kotlinx_serialization",
      "dateLibrary" to "string",
      "enumPropertyNaming" to "UPPERCASE",
    )
  )
}

sourceSets["main"].kotlin.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")
sourceSets["main"].kotlin.srcDir(monolithPathsGeneratedDir)

tasks.named("compileKotlin") {
  dependsOn(tasks.named("openApiGenerate"), tasks.named("generateOutBoundMonolithPaths"))
}
