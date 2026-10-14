package io.github.selectionmenucontrol

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Publish a verified result before cleanup, while keeping the session owned until cleanup ends. */
internal suspend fun <S, R> runProbeSession(
    prepare: suspend () -> S,
    evaluate: suspend (S) -> R,
    publish: suspend (R) -> Unit,
    cleanup: suspend (S) -> Unit,
): R {
    val session = prepare()
    try {
        val result = evaluate(session)
        currentCoroutineContext().ensureActive()
        publish(result)
        return result
    } finally {
        cleanup(session)
    }
}
