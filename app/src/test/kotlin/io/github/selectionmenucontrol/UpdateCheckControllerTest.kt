package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckControllerTest {
    @Test fun unconsumedManualNoticeSurvivesAutomaticRequestsAndIsConsumedOnce() = runBlocking {
        withTimeout(3_000) {
            var result: UpdateCheckResult = UpdateCheckResult.Failed
            val controller = UpdateCheckController(this) { result }
            controller.check(manual = true)
            controller.state.first { !it.checking }
            assertEquals("检查更新失败，请稍后重试", controller.state.value.notice)

            result = UpdateCheckResult.UpToDate
            controller.check(manual = false)
            assertEquals("检查更新失败，请稍后重试", controller.state.value.notice)
            controller.state.first { !it.checking }
            assertEquals("检查更新失败，请稍后重试", controller.consumeNotice())
            assertNull(controller.state.value.notice)
            assertNull(controller.consumeNotice())

            result = UpdateCheckResult.Failed
            controller.check(manual = false)
            controller.state.first { !it.checking }
            assertNull(controller.consumeNotice())
            assertNull(controller.state.value.notice)
        }
    }

    @Test fun aNewManualRequestReplacesAnOlderUnconsumedManualResult() = runBlocking {
        withTimeout(3_000) {
            var result: UpdateCheckResult = UpdateCheckResult.Failed
            val controller = UpdateCheckController(this) { result }
            controller.check(manual = true)
            controller.state.first { !it.checking }

            result = UpdateCheckResult.UpToDate
            controller.check(manual = true)
            assertNull(controller.state.value.notice)
            controller.state.first { !it.checking }
            assertEquals("暂无更新", controller.consumeNotice())
            assertNull(controller.consumeNotice())
        }
    }

    @Test fun manualRequestJoinsAnAutomaticRequestAndReportsOneResult() = runBlocking {
        withTimeout(3_000) {
            val result = CompletableDeferred<UpdateCheckResult>()
            var requests = 0
            val controller = UpdateCheckController(this) { requests++; result.await() }
            controller.check(manual = false)
            controller.check(manual = true)
            yield()
            assertEquals(1, requests)
            result.complete(UpdateCheckResult.UpToDate)
            controller.state.first { !it.checking }
            assertEquals("暂无更新", controller.consumeNotice())
            assertNull(controller.consumeNotice())
        }
    }

    @Test fun automaticFailuresAndNoUpdatesStaySilent() = runBlocking {
        withTimeout(3_000) {
            var result: UpdateCheckResult = UpdateCheckResult.Failed
            val controller = UpdateCheckController(this) { result }
            controller.check(manual = false)
            controller.state.first { !it.checking }
            assertNull(controller.consumeNotice())
            result = UpdateCheckResult.UpToDate
            controller.check(manual = false)
            controller.state.first { !it.checking }
            assertNull(controller.consumeNotice())
            result = UpdateCheckResult.Failed
            controller.check(manual = true)
            controller.state.first { !it.checking }
            assertEquals("检查更新失败，请稍后重试", controller.consumeNotice())
        }
    }

    @Test fun lateCompletionAfterCancellationCannotHideOrOverwriteANewerRequest() = runBlocking {
        withTimeout(3_000) {
            val oldResult = CompletableDeferred<UpdateCheckResult>()
            val newResult = CompletableDeferred<UpdateCheckResult>()
            var calls = 0
            val controller = UpdateCheckController(this) {
                if (++calls == 1) withContext(NonCancellable) { oldResult.await() } else newResult.await()
            }
            controller.check(manual = false)
            yield()
            controller.cancel()
            controller.check(manual = true)
            yield()
            oldResult.complete(UpdateCheckResult.Failed)
            repeat(3) { yield() }
            assertTrue(controller.state.value.checking)
            assertNull(controller.consumeNotice())
            newResult.complete(UpdateCheckResult.UpToDate)
            controller.state.first { !it.checking }
            assertEquals("暂无更新", controller.consumeNotice())
        }
    }

    @Test fun disablingAutomaticChecksKeepsAnExplicitManualRequestAndDismissalDoesNotNag() = runBlocking {
        withTimeout(3_000) {
            val release = UpdateInfo("v0.7.0", "https://github.com/TheKingBucket001/txtoi/releases/tag/v0.7.0")
            val pending = CompletableDeferred<UpdateCheckResult>()
            var calls = 0
            val controller = UpdateCheckController(this) { if (++calls == 1) pending.await() else UpdateCheckResult.Available(release) }
            controller.check(manual = true)
            yield()
            controller.cancel(automaticOnly = true)
            assertTrue(controller.state.value.checking)
            pending.complete(UpdateCheckResult.Available(release))
            controller.state.first { !it.checking }
            assertEquals(release, controller.state.value.available)
            controller.dismissUpdate()
            controller.check(manual = false)
            controller.state.first { !it.checking }
            assertNull(controller.state.value.available)
            controller.check(manual = true)
            controller.state.first { !it.checking }
            assertEquals(release, controller.state.value.available)
        }
    }
}
