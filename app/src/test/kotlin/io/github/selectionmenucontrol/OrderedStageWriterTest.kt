package io.github.selectionmenucontrol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

class OrderedStageWriterTest {
    private class ReverseDispatcher : CoroutineDispatcher() {
        val tasks = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun last() { tasks.removeAt(tasks.lastIndex).run() }
        fun drain() { while (tasks.isNotEmpty()) last() }
    }

    @Test fun workersStartingInReverseCannotMakeAnOlderDraftTheNewest() = runBlocking {
        val dispatcher = ReverseDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val staging = OrderedStageWriter(scope, dispatcher)
        val writes = mutableListOf<String>()
        try {
            val older = staging.submit { writes.add("older"); true }
            val newest = staging.submit { writes.add("newest"); true }
            dispatcher.last() // The newest worker starts before the older worker.
            assertTrue(writes.isEmpty())
            dispatcher.drain()
            assertTrue(older.await()); assertTrue(newest.await())
            assertEquals(listOf("older", "newest"), writes)
        } finally { scope.cancel() }
    }

    @Test fun oneFailedStageDoesNotPreventTheNextExplicitEdit() = runBlocking {
        val dispatcher = ReverseDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val staging = OrderedStageWriter(scope, dispatcher)
        try {
            val failed = staging.submit { false }
            val next = staging.submit { true }
            dispatcher.drain()
            assertFalse(failed.await()); assertTrue(next.await())
        } finally { scope.cancel() }
    }
}
