package com.example.calltracker

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Privacy rule (CLAUDE.md): never log phone numbers, names or notes. Scans every log statement in the app's code and
 * fails if one interpolates a field that could hold them, or passes a Throwable (whose message could). Release builds
 * additionally strip v/d/i logging with R8 (proguard-rules.pro).
 */
class LogHygieneTest {

    private val logCall = Regex("""\bLog\.(v|d|i|w|e|wtf)\(""")
    private val sensitive = Regex(
        """\$\{?[\w.]*(number|phone|rawNumber|normalized|name|notes|remarks|nextAction|cachedName|contact|payload|body|request)""",
        RegexOption.IGNORE_CASE,
    )
    private val throwableArg = Regex(""",\s*e\s*\)\s*$""")

    @Test
    fun `no log statement can print call data`() {
        val offenders = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "${File.separator}test${File.separator}" !in it.path }
            .flatMap { file -> file.readLines().mapIndexed { i, line -> Triple(file, i + 1, line) } }
            .filter { (_, _, line) -> logCall.containsMatchIn(line) }
            .filter { (file, _, line) ->
                // An exception's class name is the allowed way to say what failed.
                sensitive.containsMatchIn(line.replace("javaClass.simpleName", "")) ||
                    // A Throwable's message may carry data; only key-unwrap failures (crypto, no user data) may pass one.
                    (throwableArg.containsMatchIn(line) && file.name != "DatabaseKeyProvider.kt")
            }
            .map { (file, n, line) -> "${file.name}:$n  ${line.trim()}" }
            .toList()
        assertTrue("Log statements that could leak call data:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
