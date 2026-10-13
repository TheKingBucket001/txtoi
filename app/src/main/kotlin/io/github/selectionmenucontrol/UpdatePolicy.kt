package io.github.selectionmenucontrol

import java.net.URI

internal data class UpdateInfo(val version: String, val releaseUrl: String)

/** Only stable releases in this repository are valid update destinations. */
internal object UpdatePolicy {
    const val LATEST_RELEASE_URL = "https://github.com/TheKingBucket001/txtoi/releases/latest"
    private const val RELEASE_PREFIX = "/TheKingBucket001/txtoi/releases/"
    private val versionPattern = Regex("[vV]?(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){1,3}")

    fun parseVersion(value: String): List<Int>? {
        if (!versionPattern.matches(value)) return null
        return value.removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: return null }
    }

    fun isVersionNewer(remote: String, local: String): Boolean {
        val remoteParts = parseVersion(remote) ?: return false
        val localParts = parseVersion(local) ?: return false
        for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
            val comparison = remoteParts.getOrElse(index) { 0 }.compareTo(localParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun allowedEndpoint(value: String, allowLatest: Boolean): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || !uri.host.orEmpty().equals("github.com", ignoreCase = true)
            || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)
            || uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath.orEmpty()
        if (allowLatest && path.equals("${RELEASE_PREFIX}latest", ignoreCase = true)) return true
        if (!path.startsWith("${RELEASE_PREFIX}tag/", ignoreCase = true)) return false
        return parseVersion(path.substring(RELEASE_PREFIX.length + 4)) != null
    }

    fun release(value: String): UpdateInfo? {
        if (!allowedEndpoint(value, allowLatest = false)) return null
        val version = URI(value).rawPath.substringAfterLast('/')
        return UpdateInfo(version, "https://github.com${RELEASE_PREFIX}tag/$version")
    }

    fun fresh(cachedAt: Long, now: Long, validity: Long): Boolean =
        cachedAt >= 0L && now >= cachedAt && now - cachedAt < validity
}
