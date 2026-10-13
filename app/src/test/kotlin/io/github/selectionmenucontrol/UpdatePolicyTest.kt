package io.github.selectionmenucontrol

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun versionsCompareNumericallyWithoutOverflowOrPreReleasePromotion() {
        assertTrue(UpdatePolicy.isVersionNewer("v0.10.0", "0.9.9"))
        assertTrue(UpdatePolicy.isVersionNewer("0.7", "v0.6.2"))
        assertFalse(UpdatePolicy.isVersionNewer("0.7.0.0", "0.7"))
        assertFalse(UpdatePolicy.isVersionNewer("v0.7.0-beta.1", "0.6.2"))
        assertFalse(UpdatePolicy.isVersionNewer("2147483648.0", "0.6.2"))
        listOf(" 0.7", "0.7 ", "+0.7", "-1.7", "0..7", "0.07", "0.7.0.0.1", "0.7+build")
            .forEach { assertNull(it, UpdatePolicy.parseVersion(it)) }
    }

    @Test fun releaseLinksOnlyAcceptHttpsStableTagsInOurRepository() {
        val expected = "https://github.com/TheKingBucket001/txtoi/releases/tag/v0.7.0"
        assertEquals(UpdateInfo("v0.7.0", expected), UpdatePolicy.release(expected))
        assertEquals(expected, UpdatePolicy.release("https://GITHUB.COM:443/thekingbucket001/TXTOI/releases/tag/v0.7.0")?.releaseUrl)
        listOf(
            "http://github.com/TheKingBucket001/txtoi/releases/tag/v0.7.0",
            "https://github.com.evil.example/TheKingBucket001/txtoi/releases/tag/v0.7.0",
            "https://github.com@evil.example/TheKingBucket001/txtoi/releases/tag/v0.7.0",
            "https://user@github.com/TheKingBucket001/txtoi/releases/tag/v0.7.0",
            "https://github.com:444/TheKingBucket001/txtoi/releases/tag/v0.7.0",
            "https://github.com/other/txtoi/releases/tag/v0.7.0",
            "$expected/extra", "$expected?return=https://example.com", "$expected#fragment",
            "https://github.com/TheKingBucket001/txtoi/releases/tag/%760.7.0",
            "https://github.com/TheKingBucket001/txtoi/releases/tag/v0.7.0-rc.1",
            UpdatePolicy.LATEST_RELEASE_URL,
        ).forEach { assertNull(it, UpdatePolicy.release(it)) }
        assertTrue(UpdatePolicy.allowedEndpoint(UpdatePolicy.LATEST_RELEASE_URL, allowLatest = true))
    }

    @Test fun monotonicCacheExpiresAtBoundaryAndRejectsReversedClock() {
        assertTrue(UpdatePolicy.fresh(100, 100, 15))
        assertTrue(UpdatePolicy.fresh(100, 114, 15))
        assertFalse(UpdatePolicy.fresh(100, 115, 15))
        assertFalse(UpdatePolicy.fresh(100, 99, 15))
        assertFalse(UpdatePolicy.fresh(-1, 100, 15))
    }
}
