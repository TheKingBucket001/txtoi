package io.github.selectionmenucontrol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Called on the UI dispatcher; worker scheduling cannot reverse accepted durable drafts. */
internal class OrderedStageWriter(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private var head: Deferred<Boolean>? = null
    fun submit(write: suspend () -> Boolean): Deferred<Boolean> {
        val previous = head
        return scope.async(dispatcher) {
            previous?.join()
            write()
        }.also { head = it }
    }
}
