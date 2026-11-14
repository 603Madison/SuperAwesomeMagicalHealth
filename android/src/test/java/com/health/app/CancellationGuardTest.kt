package com.health.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "StandaloneCoroutine was cancelled" must never pop up: work a screen stops on purpose (reloading,
 * closing) isn't a failure. The app uses [attempt] rather than runCatching, and rethrows
 * cancellation before any catch-all; this keeps it that way.
 */
class CancellationGuardTest {
    private val sources = listOf(File("src/main/java"), File("android/src/main/java")).first { it.isDirectory }

    @Test
    fun `no runCatching or bare catch-all in the app`() {
        val offences = sources.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Attempt.kt" }.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { i, line ->
                val where = "${file.relativeTo(sources)}:${i + 1}"
                // Cancellation rethrown just before: on the same line, or on lines of its own.
                val guarded = line.contains("catch (e: CancellationException) { throw e } catch") ||
                    lines.subList(maxOf(0, i - 2), i).joinToString(" ").let { it.contains("catch (e: CancellationException)") && it.contains("throw e") }
                when {
                    Regex("""\brunCatching\b""").containsMatchIn(line) -> "$where uses runCatching: use attempt"
                    Regex("""catch \((e|_): (Exception|Throwable)\)""").containsMatchIn(line) && !guarded ->
                        "$where catches everything: rethrow CancellationException first"
                    else -> null
                }
            }
        }.toList()
        assertTrue(offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun `attempt lets a cancellation through and catches the rest`() = runBlocking {
        var failure: Throwable? = null
        var reachedAfter = false
        val job: Job = launch {
            attempt { delay(10_000) }.onFailure { failure = it }
            reachedAfter = true
        }
        delay(50)
        job.cancelAndJoin()
        assertEquals(null, failure)
        assertFalse(reachedAfter)
        assertTrue(attempt { error("no") }.isFailure)
        assertEquals(3, attempt { 3 }.getOrNull())
    }

    @Test
    fun `cancellation messages are never told`() {
        assertTrue(isCancellationMessage("StandaloneCoroutine was cancelled"))
        assertTrue(isCancellationMessage("Job was cancelled"))
        assertTrue(isCancellationMessage("Parent job is Cancelling"))
        assertTrue(isCancellationMessage(CancellationException("Job was cancelled").message))
        assertFalse(isCancellationMessage("Couldn't save that"))
        assertFalse(isCancellationMessage("Your appointment was cancelled"))
        assertFalse(isCancellationMessage(null))
    }
}
