package dropnext.dss

import dropnext.dss.testutil.helper.KotlinSourceFile
import dropnext.dss.testutil.helper.kotlinSourceFileTexts
import dropnext.dss.testutil.helper.pathContainsAllowListEntry
import org.junit.jupiter.api.Test


/**
 * The conventions of `test/` itself. The suite is the thing that tells us the rest is right, so its
 * own rot has to be caught mechanically too: a fake that records in a shape of its own, a test that
 * spins up a server the production module already provides, an assertion loose enough to pass on the
 * wrong answer.
 *
 * Pure text over a file walk, no Konsist scope: these rules need file names and source lines, and
 * parsing every file to read them would cost seconds per rule.
 */
class TestSuiteArchitectureTest {

  /**
   * Read once per test JVM: JUnit builds a fresh instance per test method, so an instance-level `lazy` would
   * walk the tree again for every rule. Mirrors the monolith's `TestSuiteArchitectureTest`.
   */
  companion object {
    private val testFiles: List<KotlinSourceFile> by lazy { kotlinSourceFileTexts("test") }

    private val srcFiles: List<KotlinSourceFile> by lazy { kotlinSourceFileTexts("src") }
  }

  /** Files under `test/` that are infrastructure rather than tests; everything here lives under `testutil/`. */
  private fun KotlinSourceFile.isInfrastructure(): Boolean = "/test/dropnext/dss/testutil/" in path

  private fun report(rule: String, offenders: List<String>, remedy: String) {
    if (offenders.isEmpty()) return
    println("ERROR: $rule\n" + offenders.joinToString("\n") { "  - $it" } + "\n$remedy")
  }

  // ---------- what a test is allowed to assert with ----------

  @Test
  fun `test code asserts through Kotlin's assert, not JUnit's`() {
    val junitAssertion = Regex("""\b(assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertContains|assertThrows)\s*[(<]""")
    val offenders = testFiles
      .filter { junitAssertion.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these test files use a JUnit or kotlin.test assertion",
      offenders,
      "Use Kotlin's `assert(...)`: the power-assert plugin prints every intermediate value, which a " +
        "one-line assertEquals failure does not.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * An assertion that accepts two statuses passes when the endpoint answers the wrong one, which is
   * the single most common way a request → response test stops meaning anything.
   */
  @Test
  fun `no assertion accepts more than one HTTP status`() {
    val offenders = testFiles.flatMap { file ->
      file.code.lines().withIndex()
        .filter { (_, line) ->
          val trimmed = line.trim()
          trimmed.startsWith("assert(") &&
            "HttpStatusCode" in trimmed &&
            (" || " in trimmed || "setOf(" in trimmed || "listOf(" in trimmed)
        }
        .map { (idx, line) -> "${file.path}:${idx + 1}  ${line.trim()}" }
    }
    report(
      "these assertions accept more than one HTTP status",
      offenders,
      "Assert the one status the endpoint must answer. If two are genuinely possible, the test is " +
        "really two tests.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * A ratchet, not a ban: the OAuth and diagnostics endpoints answer plain text, so reading the raw
   * body is right there. Everywhere else the body is JSON and belongs decoded into its DTO, where a
   * changed envelope fails the test instead of sliding past a substring match.
   */
  @Test
  fun `raw response body assertions do not grow`() {
    val rawBodyReads = testFiles.sumOf { file -> Regex("""bodyAsText\(\)""").findAll(file.code).count() }
    if (rawBodyReads > MAX_RAW_BODY_READS) {
      println(
        "ERROR: $rawBodyReads uses of `bodyAsText()` in test/, up from the pinned $MAX_RAW_BODY_READS.\n" +
          "Decode the response into its contract DTO instead. If the endpoint really answers text " +
          "(the OAuth pages, the diagnostics endpoints), raise MAX_RAW_BODY_READS and say why here."
      )
    }
    assert(rawBodyReads <= MAX_RAW_BODY_READS)
  }

  // ---------- where test code lives and what it is called ----------

  @Test
  fun `a test file is named after the class it declares`() {
    val topLevelClass = Regex("""^(?:@\w+\s*(?:\([^)]*\)\s*)?)*(?:internal |private )?(?:abstract |open )?class (\w+)""", RegexOption.MULTILINE)
    val offenders = testFiles
      .filterNot { it.isInfrastructure() }
      .mapNotNull { file ->
        val declared = topLevelClass.findAll(file.code).map { it.groupValues[1] }.toList()
        when {
          declared.isEmpty() -> "${file.path}: declares no test class"
          declared.size > 1 -> "${file.path}: declares ${declared.size} top-level classes ($declared)"
          declared.single() != file.name -> "${file.path}: declares ${declared.single()}"
          else -> null
        }
      }
    report(
      "these test files are not named after the class they declare",
      offenders,
      "One test class per file, named the same. A `lowerCamel.kt` source of top-level functions gets " +
        "a `PascalCaseTest.kt`.",
    )
    assert(offenders.isEmpty())
  }

  @Test
  fun `test infrastructure lives under testutil`() {
    val offenders = testFiles.mapNotNull { file ->
      val isTest = file.name.endsWith("Test")
      when {
        isTest && file.isInfrastructure() -> "${file.path}: a test under testutil/"
        !isTest && !file.isInfrastructure() -> "${file.path}: not a test and not under testutil/"
        else -> null
      }
    }
    report(
      "these files sit on the wrong side of the test / infrastructure split",
      offenders,
      "A `*Test.kt` mirrors a source file in its package; everything else goes under " +
        "`testutil/{fake,fixture,helper}/`.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * Test files exempt from the mirror rule: cross-cutting meta-tests with no single source
   * counterpart. Add entries sparingly and always with the reason.
   */
  private val mirrorSourceAllowList = listOf(
    // Meta-tests: they enforce project-wide rules rather than exercise one source file.
    "/test/dropnext/dss/ArchitectureTest.kt",
    "/test/dropnext/dss/TestSuiteArchitectureTest.kt",
  )

  @Test
  fun `every test file has a matching source file in the mirrored package`() {
    val srcPaths = srcFiles.map { it.path }.toSet()
    val offenders = testFiles
      .filter { it.name.endsWith("Test") }
      .filterNot { pathContainsAllowListEntry(it.path, mirrorSourceAllowList) }
      .mapNotNull { file ->
        val stem = file.name.removeSuffix("Test")
        val mirroredDir = file.path.substringBeforeLast("/").replace("/test/", "/src/")
        val pascal = "$mirroredDir/$stem.kt"
        val lowerCamel = "$mirroredDir/${stem.replaceFirstChar { it.lowercaseChar() }}.kt"
        if (pascal in srcPaths || lowerCamel in srcPaths) null else "${file.path}  (expected $pascal)"
      }
    report(
      "these test files have no matching source file in the mirrored package",
      offenders,
      "Rename the test to match its source, move it to the mirrored package, or add it to " +
        "mirrorSourceAllowList with a reason.",
    )
    assert(offenders.isEmpty())
  }

  @Test
  fun `every mirror allowlist entry names a file that exists`() {
    val paths = testFiles.map { it.path }
    val stale = mirrorSourceAllowList.filter { entry -> paths.none { entry in it } }
    report(
      "these mirrorSourceAllowList entries name files that no longer exist",
      stale,
      "Stale exemption — drop it from the allowlist.",
    )
    assert(stale.isEmpty())
  }

  // ---------- what a test may own ----------

  /**
   * A handler test that builds its own server tests its own wiring, not the application's: the
   * plugins `dssModule` installs (trace ids, status pages, content negotiation, the auth guard) are
   * all absent from a hand-rolled route.
   */
  /** The two architecture tests quote the very patterns they forbid, in string literals. */
  private val ownsTransportAllowList = listOf(
    "/test/dropnext/dss/ArchitectureTest.kt",
    "/test/dropnext/dss/TestSuiteArchitectureTest.kt",
  )

  @Test
  fun `no test owns a server or an HTTP client`() {
    val ownsServer = Regex("""\bembeddedServer\s*\(""")
    val ownsClient = Regex("""\bHttpClient\s*\(""")
    val offenders = testFiles
      .filterNot { it.isInfrastructure() }
      .filterNot { pathContainsAllowListEntry(it.path, ownsTransportAllowList) }
      .filter { ownsServer.containsMatchIn(it.code) || ownsClient.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these tests construct their own server or HTTP client",
      offenders,
      "Use `withDssApp(deps)` for a request → response test and `testHttpClient()` for a client; both " +
        "live in testutil/helper/.",
    )
    assert(offenders.isEmpty())
  }

  @Test
  fun `test code keeps no mutable state in a companion object`() {
    val companionWithVar = Regex("""companion object\s*\{[^}]*\bvar\s""", RegexOption.DOT_MATCHES_ALL)
    val offenders = testFiles
      .filter { companionWithVar.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these test files hold mutable state in a companion object",
      offenders,
      "Companion state is shared by every instance and outlives a test method; make it a property of " +
        "the test class instead.",
    )
    assert(offenders.isEmpty())
  }

  // ---------- how a fake records ----------

  @Test
  fun `a fake that records calls is a RecordingFake`() {
    val declaresCallsList = Regex("""\bval \w+Calls\s*:""")
    val offenders = testFiles
      .filter { "/testutil/fake/" in it.path && it.name.startsWith("Fake") }
      .filter { declaresCallsList.containsMatchIn(it.code) && "RecordingFake" !in it.code }
      .map { it.path }
    report(
      "these fakes record calls without implementing RecordingFake",
      offenders,
      "Implement `RecordingFake` and its `clear()`, so every fake is reset the same way.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * Three shapes for "what was this asked" means the reader has to open the fake before writing an
   * assertion. One list per method answers both "how often" and "with what".
   */
  @Test
  fun `a fake records into lists, not counters or last-call fields`() {
    val counter = "CallCount"
    val lastCall = Regex("""\bvar last[A-Z]\w*\s*:""")
    val offenders = testFiles
      .filter { "/testutil/fake/" in it.path }
      .filter { counter in it.code || lastCall.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these fakes record with a counter or a last-call field",
      offenders,
      "Record into a `<method>Calls` list: `calls.size` is the count and `calls.single()` is the input.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * A fake server's recordings are written by its own request thread and read by the test thread, so
   * a plain `mutableListOf` is a visibility hazard today and a data race the moment test classes run
   * concurrently.
   */
  @Test
  fun `a fake server records into a concurrent collection`() {
    val plainMutableList = Regex("""\bval \w*(?:Calls|calls|[Rr]equests)\s*:[^=]*=\s*mutableListOf\(\)""")
    val offenders = testFiles
      .filter { "/testutil/fake/" in it.path && it.name.endsWith("Server") }
      .filter { plainMutableList.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these fake servers record into a non-concurrent collection",
      offenders,
      "Use `CopyOnWriteArrayList`: the server's request thread writes what the test thread reads.",
    )
    assert(offenders.isEmpty())
  }

  // ---------- every outbound call is covered at the wire ----------

  /**
   * The rule `CLAUDE.md` states: every `MonolithService`, `ShopifyGraphqlService` and `ShopifyOAuthService`
   * method needs a wire-level test. Without it, a renamed Graphql variable or a changed JSON key first fails a
   * *workflow* test, whose message points at the wrong layer.
   */
  @Test
  fun `every outbound call is named by a wire-level test`() {
    val offenders = OUTBOUND_INTERFACES.flatMap { (interfaceName, implName) ->
      val declaration = srcFiles.single { it.name == interfaceName }
      val methods = Regex("""suspend fun (\w+)\(""").findAll(declaration.code).map { it.groupValues[1] }.toSet()
      val wireTest = testFiles.singleOrNull { it.name == implName }
        ?: return@flatMap listOf("$implName.kt is missing: it is where $interfaceName is covered at the wire")
      val testNames = Regex("""fun `([^`]+)`""").findAll(wireTest.code).map { it.groupValues[1] }.toList()
      methods.filter { method -> testNames.none { it.startsWith(method) } }
        .map { "$interfaceName.$it has no test in $implName.kt whose name starts with it" }
    }
    report(
      "these outbound calls have no wire-level test",
      offenders,
      "Add a test to the Http* test named after the method, with a response that carries data so the " +
        "deserialization is exercised.",
    )
    assert(offenders.isEmpty())
  }
}

/**
 * How many `bodyAsText()` reads the suite is allowed; see the rule that reads it. Raised from 23 for
 * the OAuth callback's failure cases, which answer plain text by design (a merchant's browser reads them),
 * and again from 27 when the callback learnt to answer a refused code as a `400` of its own, and from 29
 * when the status pages learnt to answer every exception on those paths in plain text.
 */
private const val MAX_RAW_BODY_READS = 31


/** Interface to the test file that has to name each of its methods. */
private val OUTBOUND_INTERFACES = mapOf(
  "MonolithService" to "HttpMonolithServiceTest",
  "ShopifyGraphqlService" to "HttpShopifyGraphqlServiceTest",
  "ShopifyOAuthService" to "HttpShopifyOAuthServiceTest",
  "WarmUpLoopbackService" to "HttpWarmUpLoopbackServiceTest",
)
