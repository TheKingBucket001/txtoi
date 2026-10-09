package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSaveQueueTest {
    @Test
    fun verifiedFinalWriteProducesSuccessOnlyAfterItCompletes() = runBlocking {
        withTimeout(3_000) {
            val verified = CompletableDeferred<Boolean>()
            val queue = RuleSaveQueue(this, persist = { _: Int -> verified.await() }, read = { 0 })
            queue.initialize(0)
            queue.submit(1)
            assertTrue(queue.state.value!!.pending)
            assertEquals(0, queue.state.value!!.successVersion)

            verified.complete(true)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(1, queue.state.value!!.successVersion)
            assertEquals(1, queue.state.value!!.value)
        }
    }

    @Test
    fun rapidSavesProduceOneSuccessForTheLatestDraft() = runBlocking {
        withTimeout(3_000) {
            val writes = mutableListOf<Int>()
            val release = Channel<Unit>(Channel.UNLIMITED)
            val queue = RuleSaveQueue(this, persist = { value: Int ->
                writes.add(value)
                release.receive()
                true
            }, read = { 0 })
            queue.initialize(0)
            queue.submit(1)
            queue.submit(2)
            queue.submit(3)
            for (expectedWrites in 2..3) {
                release.send(Unit)
                while (writes.size < expectedWrites) kotlinx.coroutines.yield()
                assertEquals(0, queue.state.value!!.successVersion)
                assertTrue(queue.state.value!!.pending)
            }
            release.send(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(1, 2, 3), writes)
            assertEquals(3, queue.state.value!!.value)
            assertEquals(1, queue.state.value!!.successVersion)

            queue.submit(4)
            release.send(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(2, queue.state.value!!.successVersion)
        }
    }

    @Test
    fun olderSuccessIsNotReportedWhenLatestWriteFails() = runBlocking {
        withTimeout(3_000) {
            val releaseFirst = CompletableDeferred<Unit>()
            val queue = RuleSaveQueue(this, persist = { value: Int ->
                if (value == 1) { releaseFirst.await(); true } else false
            }, read = { 1 })
            queue.initialize(0)
            queue.submit(1)
            queue.submit(2)
            releaseFirst.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(0, queue.state.value!!.successVersion)
            assertEquals(1, queue.state.value!!.failureVersion)
            assertEquals(1, queue.state.value!!.value)
        }
    }

    @Test
    fun initializeAndRefreshPreserveReceiptsWithoutCreatingSuccess() = runBlocking {
        withTimeout(3_000) {
            val queue = RuleSaveQueue(this, persist = { value: Int -> value == 6 }, read = { 6 })
            queue.initialize(0)
            queue.refresh(5, queue.state.value)
            assertEquals(0, queue.state.value!!.successVersion)
            queue.submit(6)
            assertEquals(1, queue.state.value!!.successVersion)
            queue.submit(7)
            assertEquals(1, queue.state.value!!.failureVersion)

            queue.refresh(8, queue.state.value)
            assertEquals(1, queue.state.value!!.successVersion)
            assertEquals(1, queue.state.value!!.failureVersion)
            queue.initialize(9)
            assertEquals(1, queue.state.value!!.successVersion)
            assertEquals(1, queue.state.value!!.failureVersion)
        }
    }

    @Test
    fun pageReloadUsesFreshStorageOnlyIfNoNewEditOrWriteHasStarted() = runBlocking {
        withTimeout(3_000) {
            val release = CompletableDeferred<Unit>()
            val queue = RuleSaveQueue(this, persist = { _: Int -> release.await(); true }, read = { 0 })
            queue.initialize(0)
            val original = queue.state.value
            queue.refresh(5, original)
            assertEquals(5, queue.state.value!!.value)
            val beforeLoad = queue.state.value
            queue.submit(6)
            queue.refresh(0, beforeLoad)
            assertEquals(6, queue.state.value!!.value)
            val whileSaving = queue.state.value
            queue.refresh(0, whileSaving)
            assertEquals(6, queue.state.value!!.value)
            release.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            queue.refresh(0, whileSaving)
            assertEquals(6, queue.state.value!!.value)
        }
    }

    @Test
    fun slowWriteDoesNotBlockOtherEditsOrReplaceTheirDraft() = runBlocking {
        withTimeout(3_000) {
            val writes = mutableListOf<String>()
            val release = Channel<Unit>(Channel.UNLIMITED)
            val queue = RuleSaveQueue(this, persist = { value: String ->
                writes.add(value)
                release.receive()
                true
            }, read = { "disk" })
            queue.initialize("initial")
            queue.submit("hide A")
            queue.submit("hide A+B")
            assertEquals("hide A+B", queue.state.value!!.value)
            assertTrue(queue.state.value!!.pending)
            assertEquals(listOf("hide A"), writes)

            release.send(Unit)
            while (writes.size < 2) kotlinx.coroutines.yield()
            assertEquals("hide A+B", queue.state.value!!.value)
            assertTrue(queue.state.value!!.pending)
            release.send(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf("hide A", "hide A+B"), writes)
            assertEquals("hide A+B", queue.state.value!!.value)
        }
    }

    @Test
    fun rapidToggleAndMixedSortPreserveTheLastCompleteTarget() = runBlocking {
        withTimeout(3_000) {
            val firstWrite = CompletableDeferred<Unit>()
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { value: RuleConfig ->
                writes.add(value)
                if (writes.size == 1) firstWrite.await()
                true
            }, read = { RuleConfig(emptySet(), emptyList()) })
            queue.initialize(RuleConfig(emptySet(), listOf("A", "B")))
            queue.submit(RuleConfig(setOf("A"), listOf("A", "B")))
            queue.submit(RuleConfig(emptySet(), queue.state.value!!.value.orderedComponents))
            queue.submit(RuleConfig(setOf("A"), queue.state.value!!.value.orderedComponents))
            queue.submit(RuleConfig(queue.state.value!!.value.hiddenComponents, listOf("B", "A")))
            assertEquals(setOf("A"), queue.state.value!!.value.hiddenComponents)
            assertEquals(listOf("B", "A"), queue.state.value!!.value.orderedComponents)
            firstWrite.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(4, writes.size)
            assertEquals(setOf("A"), writes.last().hiddenComponents)
            assertEquals(listOf("B", "A"), writes.last().orderedComponents)
        }
    }

    @Test
    fun restoreActionsPreserveTheOtherPendingDimension() = runBlocking {
        withTimeout(3_000) {
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { value: RuleConfig ->
                writes.add(value)
                release.await()
                true
            }, read = { RuleConfig(emptySet(), emptyList()) })
            queue.initialize(RuleConfig(emptySet(), emptyList()))
            queue.submit(RuleConfig(setOf("A", "B"), listOf("B", "A")))
            queue.submit(RuleConfig(queue.state.value!!.value.hiddenComponents, emptyList()))
            assertEquals(setOf("A", "B"), queue.state.value!!.value.hiddenComponents)
            queue.submit(RuleConfig(queue.state.value!!.value.hiddenComponents, listOf("A", "B")))
            queue.submit(RuleConfig(emptySet(), queue.state.value!!.value.orderedComponents))
            release.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertTrue(writes.last().hiddenComponents.isEmpty())
            assertEquals(listOf("A", "B"), writes.last().orderedComponents)
        }
    }

    @Test
    fun failedOlderWriteDoesNotRollBackALaterSuccessfulEdit() = runBlocking {
        withTimeout(3_000) {
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val queue = RuleSaveQueue(this, persist = { value: Int ->
                if (value == 1) { release.await(); false } else true
            }, read = { reads++; 0 })
            queue.initialize(0)
            queue.submit(1)
            queue.submit(2)
            release.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(2, queue.state.value!!.value)
            assertEquals(0, queue.state.value!!.failureVersion)
            assertEquals(0, reads)
        }
    }

    @Test
    fun terminalFailureReadsActualStorageIncludingAPartialWrite() = runBlocking {
        withTimeout(3_000) {
            var disk = 0
            val queue = RuleSaveQueue(this, persist = { value: Int -> disk = value; false }, read = { disk })
            queue.initialize(0)
            queue.submit(1)
            assertFalse(queue.state.value!!.pending)
            assertEquals(1, queue.state.value!!.value)
            assertEquals(1, queue.state.value!!.failureVersion)
            queue.submit(2)
            assertEquals(2, queue.state.value!!.failureVersion)
        }
    }

    @Test
    fun editDuringFailureReadWinsOverTheStaleRead() = runBlocking {
        withTimeout(3_000) {
            val reading = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val queue = RuleSaveQueue(this, persist = { value: Int ->
                writes.add(value)
                value != 1
            }, read = { reading.complete(Unit); releaseRead.await(); 0 })
            queue.initialize(0)
            queue.submit(1)
            reading.await()
            queue.submit(2)
            assertEquals(2, queue.state.value!!.value)
            releaseRead.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(1, 2), writes)
            assertEquals(2, queue.state.value!!.value)
            assertEquals(0, queue.state.value!!.failureVersion)
        }
    }

    @Test
    fun writerExceptionDoesNotLeaveTheQueueStuck() = runBlocking {
        withTimeout(3_000) {
            var errors = 0
            val queue = RuleSaveQueue(this, persist = { value: Int ->
                if (value == 1) throw IllegalStateException("write failed")
                true
            }, read = { 0 }, onError = { errors++ })
            queue.initialize(0)
            queue.submit(1)
            assertEquals(0, queue.state.value!!.value)
            queue.submit(2)
            assertEquals(2, queue.state.value!!.value)
            assertFalse(queue.state.value!!.pending)
            assertEquals(1, errors)
        }
    }
}
