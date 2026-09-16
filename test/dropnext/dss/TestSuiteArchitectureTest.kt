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

  /**
   * The two architecture tests quote the very patterns they forbid, in string literals, so they are read as if they
   * broke their own rules. Nothing else belongs here.
   */
  private val ownRulePatternAllowList = listOf(
    "/test/dropnext/dss/ArchitectureTest.kt", // Its error message for the HttpClient rule quotes `HttpClient(...)`.
    "/test/dropnext/dss/TestSuiteArchitectureTest.kt", // This file: its rules list the calls they forbid.
  )

  // ---------- what a test is allowed to assert with ----------

  @Test
  fun `test code asserts through Kotlin's assert, not JUnit's`() {
    // A bare call, or one qualified by the JUnit or kotlin.test class it lives in. A call on some other receiver is
    // not one of these: Konsist's `files.assertFalse(strict = true) { … }` is how the layer rules assert.
    val names = "assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertContains|assertThrows"
    val junitAssertion = Regex("""(?:(?<![.\w])|\b(?:Assertions|Assert|kotlin\.test)\.)(?:$names)\s*[(<]""")
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
   *
   * Two shapes: a list or an alternative of `HttpStatusCode`s, and a comparison that is not an equality (a range, a
   * success check, a status the answer must not be). A domain status compared that way is flagged too: it accepts more
   * than one answer just the same. The rule reads one line, so an `assert(` spread over several goes unseen.
   */
  @Test
  fun `no assertion accepts more than one HTTP status`() {
    val statusAlternatives = Regex("""\|\||\bsetOf\(|\blistOf\(""")
    val statusRange = Regex("""\bstatus\s*(?:!=|!?in\b)|\bstatus\s*\.\s*(?:isSuccess\s*\(|value\s*(?:[<>]|!=|!?in\b))""")
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .flatMap { file ->
        file.code.lines().withIndex()
          .filter { (_, line) ->
            val trimmed = line.trim()
            val alternatives = "HttpStatusCode" in trimmed && statusAlternatives.containsMatchIn(trimmed)
            trimmed.startsWith("assert(") && (alternatives || statusRange.containsMatchIn(trimmed))
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

  // ---------- which testing library a test is written against ----------

  /**
   * On the JVM `kotlin.test.Test` is an `actual typealias` for `org.junit.jupiter.api.Test`: both spellings compile to
   * the very same annotation, and JUnit cannot tell them apart. It exists so one source set can be tested on JVM, JS
   * and Native, which is not what this is. Here it only buys two spellings for one concept — a reader of an unfamiliar
   * file cannot tell whether the difference means anything — and it drags `assertEquals` and its siblings into scope
   * beside it, which the rule above forbids. The same holds for `BeforeTest` / `AfterTest`, whose JUnit names
   * (`BeforeEach`, `AfterEach`) at least say when they run.
   */
  @Test
  fun `a test is annotated with JUnit's Test, not the kotlin_test typealias`() {
    val kotlinTestReference = Regex("""(?:^import |@)kotlin\.test\.""", RegexOption.MULTILINE)
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filter { kotlinTestReference.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these test files are written against kotlin.test rather than JUnit",
      offenders,
      "Annotate with `org.junit.jupiter.api.Test`, and use `@BeforeEach` / `@AfterEach` for the lifecycle " +
        "hooks. One spelling per thing, and no kotlin assertion library in scope beside it.",
    )
    assert(offenders.isEmpty())
  }

  /** The dependency policy holds for `test/` too: a fake is a class implementing our own interface. */
  @Test
  fun `no test uses a mocking library`() {
    val mockingLibrary = Regex("""\b(?:io\.mockk|org\.mockito)\b""")
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filter { mockingLibrary.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these test files use a mocking library",
      offenders,
      "Write a fake in testutil/fake/ that implements the interface and records what it was asked (`RecordingFake`).",
    )
    assert(offenders.isEmpty())
  }

  // ---------- how a test waits ----------

  /**
   * A sleep waits for a guess: too short and the test is flaky, long enough and every run pays for it. `awaitUntil`
   * waits for the fact and says so when it does not come. The infrastructure under `testutil/` is exempt, because it is
   * where the bounded waits live (`awaitUntil`, `logbackContext`) and where a fake plays a slow upstream
   * (`FakeLogflareServer`).
   */
  @Test
  fun `a test waits for a condition, not for a sleep`() {
    val sleep = Regex("""\bThread\s*\.\s*sleep\s*\(""")
    val offenders = testFiles
      .filterNot { it.isInfrastructure() }
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filter { sleep.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these tests sleep",
      offenders,
      "Wait with `awaitUntil { … }` or `awaitUntilBlocking { … }` and assert what it answers.",
    )
    assert(offenders.isEmpty())
  }

  /** Tests that may run under virtual time. */
  private val runTestAllowList = listOf(
    // The webhook budget is made of durations, so how long the policy would have waited is the assertion.
    "/test/dropnext/dss/handler/MirrorWithinBudgetTest.kt",
  )

  /**
   * Under `runTest` every `delay` is skipped. That is the point for code whose subject is elapsed time, and a way to hide
   * a missing `awaitUntil` everywhere else.
   */
  @Test
  fun `only a test about elapsed time runs under virtual time`() {
    val virtualTime = Regex("""\brunTest\b""")
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filterNot { pathContainsAllowListEntry(it.path, runTestAllowList) }
      .filter { virtualTime.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these tests run under virtual time",
      offenders,
      "Use `runBlocking`. If elapsed time is what the test is about, add it to runTestAllowList with the reason.",
    )
    assert(offenders.isEmpty())
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

  /** An exemption for a file that is gone silently exempts whatever is put at that path next. */
  @Test
  fun `every allowlist entry names a file that exists`() {
    val paths = testFiles.map { it.path }
    val allowLists = mapOf(
      "mirrorSourceAllowList" to mirrorSourceAllowList,
      "ownRulePatternAllowList" to ownRulePatternAllowList,
      "runTestAllowList" to runTestAllowList,
    )
    val stale = allowLists.flatMap { (listName, entries) ->
      entries.filter { entry -> paths.none { entry in it } }.map { "$listName: $it" }
    }
    report(
      "these allowlist entries name files that no longer exist",
      stale,
      "Stale exemption: drop it from the allowlist. A new allowlist in this class joins the map above.",
    )
    assert(stale.isEmpty())
  }

  // ---------- what a test may own ----------

  /**
   * A handler test that builds its own server tests its own wiring, not the application's: the
   * plugins `dssModule` installs (trace ids, status pages, content negotiation, the auth guard) are
   * all absent from a hand-rolled route.
   */
  @Test
  fun `no test owns a server or an HTTP client`() {
    val ownsServer = Regex("""\bembeddedServer\s*\(""")
    val ownsClient = Regex("""\bHttpClient\s*\(""")
    val offenders = testFiles
      .filterNot { it.isInfrastructure() }
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
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

  /**
   * A class-level hook pair is a resource's lifecycle spelled differently in every class, and easy to leave half
   * written. `withFakeShopifyServer`, `withFakeMonolithServer` and `withDssApp` start and stop what a test needs; a
   * field that must outlive the methods of a `PER_CLASS` instance says so with JUnit's `@AutoClose`.
   */
  @Test
  fun `no test class owns a resource through a class-level lifecycle hook`() {
    val classLifecycleHook = Regex("""@(?:org\.junit\.jupiter\.api\.)?(?:BeforeAll|AfterAll)\b""")
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filter { classLifecycleHook.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these test files set up or tear down through a class-level lifecycle hook",
      offenders,
      "Use the `with…` helpers in testutil/helper/, or mark the field `@AutoClose` (with the name of its close method).",
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

  // ---------- the lock that makes a log assertion right ----------

  /**
   * `@ResourceLock("...")` compiles and locks nothing anyone else locks: JUnit matches resource keys by string, so a
   * typo, or a second spelling of the same idea, silently lets two tests that must not overlap run together.
   * `GLOBAL_LOG_REGISTRY` is the one key this suite has.
   */
  @Test
  fun `a resource lock names a constant, not a string literal`() {
    val literalLock = Regex("""@ResourceLock\(\s*\"""")
    val offenders = testFiles
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .filter { literalLock.containsMatchIn(it.code) }
      .map { it.path }
    report(
      "these tests lock a resource by string literal",
      offenders,
      "Use `@ResourceLock(GLOBAL_LOG_REGISTRY)`. A key spelled out is a key nothing else shares.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * The root logger is global and every test writes to it, so `lines.single { … }` over what was captured is only
   * true while nothing else runs. Without the lock such a test passes alone and fails, rarely and for a reason that
   * reads like a bug in the code, once the suite gets busy enough.
   */
  @Test
  fun `a test that reads the root logger declares the global log lock`() {
    val readsTheLogger = listOf("capturingLogs {", "System.setErr", "logbackContext()")
    val testMethod = Regex("""\n  fun `([^`]+)`\(""")
    val offenders = testFiles
      .filterNot { it.isInfrastructure() }
      .filterNot { pathContainsAllowListEntry(it.path, ownRulePatternAllowList) }
      .flatMap { file ->
        val classIsLocked = Regex("""@ResourceLock[\s\S]{0,80}?\nclass """).containsMatchIn(file.code)
        val starts = testMethod.findAll(file.code).toList()
        starts.mapIndexedNotNull { index, match ->
          val declaration = file.code.substring(starts.getOrNull(index - 1)?.range?.last ?: 0, match.range.first)
          val body = file.code.substring(match.range.first, starts.getOrNull(index + 1)?.range?.first ?: file.code.length)
          val isTest = "@Test" in declaration
          val locked = classIsLocked || "@ResourceLock" in declaration
          if (isTest && !locked && readsTheLogger.any { it in body }) "${file.path}: ${match.groupValues[1]}" else null
        }
      }
    report(
      "these tests read the root logger without declaring the lock",
      offenders,
      "Add `@ResourceLock(GLOBAL_LOG_REGISTRY)` to the method, so it runs while nothing else writes to that logger.",
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
   * The rule `CLAUDE.md` states: every method of an outbound interface (`OUTBOUND_INTERFACES`, four of them) needs a
   * wire-level test. Without it, a renamed Graphql variable or a changed JSON key first fails a *workflow* test, whose
   * message points at the wrong layer.
   *
   * A name is all a text rule can see: the rule wants a `@Test` in the `Http*` test whose name starts with the method.
   * Whether that case answers with data, so the decoding runs, is for the reviewer.
   */
  @Test
  fun `every outbound call is named by a wire-level test`() {
    val offenders = OUTBOUND_INTERFACES.flatMap { (interfaceName, implName) ->
      val declaration = srcFiles.single { it.name == interfaceName }
      val methods = abstractMethodsOf(interfaceName, declaration.code)
      if (methods.isEmpty()) return@flatMap listOf("$interfaceName declares no method this rule can read")
      val wireTest = testFiles.singleOrNull { it.name == implName }
        ?: return@flatMap listOf("$implName.kt is missing: it is where $interfaceName is covered at the wire")
      val testNames = testMethodNamesIn(wireTest.code)
      methods.filter { method -> testNames.none { Regex("""^$method\b""").containsMatchIn(it) } }
        .map { "$interfaceName.$it has no @Test in $implName.kt whose name starts with it" }
    }
    report(
      "these outbound calls have no wire-level test",
      offenders,
      "Add a `@Test` to the Http* test whose name starts with the method, driving the method against the fake server.",
    )
    assert(offenders.isEmpty())
  }

  /**
   * The methods an interface leaves to its implementations, suspending or not: the members at its top level whose
   * signature ends without a body. A member with a default body is not an outbound call.
   */
  private fun abstractMethodsOf(interfaceName: String, code: String): List<String> {
    val header = Regex("""\binterface $interfaceName\b[^{]*\{""").find(code) ?: return emptyList()
    val body = code.substring(header.range.last + 1, closingBraceIndex(code, header.range.last))
    return Regex("""^[ \t]*(?:suspend )?fun (\w+)\(""", RegexOption.MULTILINE).findAll(body)
      .filter { match ->
        val afterParameters = body.substring(closingParenthesisIndex(body, match.range.last) + 1)
        val restOfSignature = afterParameters.substringBefore('\n')
        '{' !in restOfSignature && '=' !in restOfSignature
      }
      .map { it.groupValues[1] }
      .toList()
  }

  /** The names of the backticked functions annotated `@Test`, on the line itself or on the lines above it. */
  private fun testMethodNamesIn(code: String): List<String> {
    val lines = code.lines()
    val testFunction = Regex("""^[ \t]*((?:@[\w.]+(?:\([^)]*\))?\s+)*)fun `([^`]+)`""")
    val testAnnotation = Regex("""@(?:org\.junit\.jupiter\.api\.)?Test\b""")
    return lines.withIndex().mapNotNull { (index, line) ->
      val match = testFunction.find(line) ?: return@mapNotNull null
      // Comments are blanked, so a KDoc above the annotations reads as blank lines.
      val annotationsAbove = lines.subList(0, index).asReversed()
        .takeWhile { it.isBlank() || it.trim().startsWith("@") }
      val annotated = testAnnotation.containsMatchIn(match.groupValues[1]) ||
        annotationsAbove.any { testAnnotation.containsMatchIn(it) }
      if (annotated) match.groupValues[2] else null
    }
  }

  private fun closingBraceIndex(code: String, openingIndex: Int): Int = closingIndex(code, openingIndex, '{', '}')

  private fun closingParenthesisIndex(code: String, openingIndex: Int): Int = closingIndex(code, openingIndex, '(', ')')

  /** Where the bracket opened at [openingIndex] closes; string literals are not skipped, which a signature does not need. */
  private fun closingIndex(code: String, openingIndex: Int, open: Char, close: Char): Int {
    var depth = 0
    for (index in openingIndex until code.length) {
      when (code[index]) {
        open -> depth++
        close -> if (--depth == 0) return index
      }
    }
    return code.length
  }
}

/**
 * How many `bodyAsText()` reads the suite is allowed; see the rule that reads it. Raised from 23 for
 * the OAuth callback's failure cases, which answer plain text by design (a merchant's browser reads them),
 * and again from 27 when the callback learned to answer a refused code as a `400` of its own, from 29
 * when the status pages learned to answer every exception on those paths in plain text, and from 31 when the
 * install page learned to name the access scopes a merchant did not grant. Lowered back to 32 when the sync-shipments
 * test stopped substring-matching a body it had already decoded into its DTO, and to 26 when the diagnostics and
 * webhook-subscription tests started decoding their JSON answers.
 */
private const val MAX_RAW_BODY_READS = 26


/** Interface to the test file that has to name each of its methods. */
private val OUTBOUND_INTERFACES = mapOf(
  "MonolithService" to "HttpMonolithServiceTest",
  "ShopifyGraphqlService" to "HttpShopifyGraphqlServiceTest",
  "ShopifyOAuthService" to "HttpShopifyOAuthServiceTest",
  "WarmUpLoopbackService" to "HttpWarmUpLoopbackServiceTest",
)
