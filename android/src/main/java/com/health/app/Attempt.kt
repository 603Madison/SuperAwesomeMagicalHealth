package com.health.app

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but a cancelled coroutine stays cancelled. When a screen reloads or closes,
 * its earlier work is stopped on purpose: that's never a failure to show ("StandaloneCoroutine was
 * cancelled"), and the work must stop rather than carry on as if it had failed.
 * `CancellationGuardTest` keeps `runCatching` and bare `catch (e: Exception)` out of the app.
 */
inline fun <R> attempt(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** Whether [message] is only a coroutine's cancellation ("StandaloneCoroutine was cancelled", "Job was cancelled"). */
fun isCancellationMessage(message: String?): Boolean =
    message != null && Regex("""^\w*(Coroutine|[Jj]ob)\b.*\b(was cancelled|is cancelling)$""", RegexOption.IGNORE_CASE).matches(message.trim())
