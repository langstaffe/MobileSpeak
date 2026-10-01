package dev.mobilespeak.mobilespeak

import android.app.DownloadManager
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun version(value: String) = requireNotNull(AppVersion.parse(value))
    private fun asset(tag: String = "v0.4.0", suffix: String = "universal", uploaded: Boolean = true) = ReleaseAsset(
        "MobileSpeak-$tag-$suffix.apk", "https://github.com/langstaffe/MobileSpeak/releases/download/$tag/MobileSpeak-$tag-$suffix.apk", 100, null, uploaded)
    private fun release(tag: String = "v0.4.0", assets: List<ReleaseAsset> = listOf(asset(tag))) = AppRelease(tag, version(tag), assets)

    @Test fun numericVersionsRejectAmbiguityAndNeverDowngrade() {
        assertTrue(version("v0.10.0") > version("0.9.0"))
        assertEquals(version("v0.3.0"), version("0.3.0"))
        for (value in listOf("", "v1", "1.2", "1.2.3-beta", "01.2.3", " 1.2.3", "1.2.3.4", "999999999999.0.0")) assertNull(value, AppVersion.parse(value))
        for (tag in listOf("v0.3.0", "v0.2.0")) assertEquals(R.string.update_current, UpdatePolicy.classify(release(tag), version("0.3.0"), listOf("arm64-v8a")).message)
        assertEquals(R.string.update_available, UpdatePolicy.classify(release(), version("0.3.0"), listOf("arm64-v8a")).message)
    }
    @Test fun missingReleaseAndMissingApkAreNotLatest() {
        assertEquals(R.string.update_no_release, UpdatePolicy.classify(null, version("0.3.0"), emptyList()).message)
        assertEquals(R.string.update_no_apk, UpdatePolicy.classify(release(assets = emptyList()), version("0.3.0"), emptyList()).message)
    }
    @Test fun onlyInstalledOrOlderDownloadsAreObsolete() {
        assertTrue(UpdatePolicy.obsoleteDownload("v0.3.2", "0.3.2"))
        assertTrue(UpdatePolicy.obsoleteDownload("v0.3.1", "0.3.2"))
        assertTrue(UpdatePolicy.obsoleteDownload("v0.9.0", "0.10.0"))
        assertFalse(UpdatePolicy.obsoleteDownload("v0.3.3", "0.3.2"))
        assertFalse(UpdatePolicy.obsoleteDownload("v0.10.0", "0.9.0"))
        assertFalse(UpdatePolicy.obsoleteDownload("invalid", "0.3.2"))
        assertFalse(UpdatePolicy.obsoleteDownload("v0.3.2", "invalid"))
    }
    @Test fun assetsArePinnedUploadedAndChosenByDeviceCompatibility() {
        val universal = asset()
        val arm = asset(suffix = "arm64-v8a")
        val x86 = asset(suffix = "x86_64")
        assertEquals(universal, UpdatePolicy.asset(release(assets = listOf(x86, arm, universal)), listOf("arm64-v8a")))
        assertEquals(arm, UpdatePolicy.asset(release(assets = listOf(x86, arm)), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.asset(release(assets = listOf(x86)), listOf("arm64-v8a")))
        assertNull(UpdatePolicy.asset(release(assets = listOf(arm, arm)), listOf("arm64-v8a")))
        for (bad in listOf(universal.copy(uploaded = false), universal.copy(size = 0), universal.copy(digest = "sha256:bad"),
            universal.copy(url = universal.url.replace("github.com", "evil.invalid")), universal.copy(url = universal.url.replace("https:", "http:")),
            universal.copy(url = universal.url.replace("v0.4.0/", "v0.5.0/")), universal.copy(url = universal.url + "?redirect=1"), universal.copy(name = "debug.apk"))) {
            assertFalse(bad.toString(), UpdatePolicy.validAsset(bad, "v0.4.0"))
        }
        assertTrue(UpdatePolicy.validAsset(universal.copy(digest = "sha256:" + "a".repeat(64)), "v0.4.0"))
    }
    @Test fun signaturesRejectDifferentAndMissingSignersAndAllowVerifiedRotation() {
        assertTrue(UpdatePolicy.signaturesCompatible(setOf("old"), setOf("old"), setOf("old")))
        assertFalse(UpdatePolicy.signaturesCompatible(setOf("old"), setOf("other"), setOf("other")))
        assertFalse(UpdatePolicy.signaturesCompatible(emptySet(), emptySet(), emptySet()))
        assertFalse(UpdatePolicy.signaturesCompatible(setOf("a", "b"), setOf("a"), setOf("a", "b")))
        assertTrue(UpdatePolicy.signaturesCompatible(setOf("a", "b"), setOf("b", "a"), emptySet()))
        assertTrue(UpdatePolicy.signaturesCompatible(setOf("old"), setOf("new"), setOf("old", "new")))
    }
    @Test fun dailyAttemptAndInFlightRequestBlockRepeatedAutomaticChecks() {
        assertTrue(UpdatePolicy.automaticAllowed("2026-9-27", null, false))
        assertFalse(UpdatePolicy.automaticAllowed("2026-9-27", "2026-9-27", false))
        assertFalse(UpdatePolicy.automaticAllowed("2026-9-28", "2026-9-27", true))
        assertTrue(UpdatePolicy.automaticAllowed("2026-9-28", "2026-9-27", false))
    }
    @Test fun downloadStateHandlesCompletionFailureAndDuplicateClicks() {
        for (status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED))
            assertEquals(DownloadStatus.DOWNLOADING, UpdatePolicy.downloadStatus(status))
        assertEquals(DownloadStatus.VERIFYING, UpdatePolicy.downloadStatus(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(DownloadStatus.FAILED, UpdatePolicy.downloadStatus(DownloadManager.STATUS_FAILED))
        assertEquals(DownloadStatus.FAILED, UpdatePolicy.downloadStatus(-1))
        for (status in listOf(DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING, DownloadStatus.READY)) assertFalse(UpdatePolicy.downloadAllowed(status))
        for (status in listOf(DownloadStatus.NONE, DownloadStatus.FAILED)) assertTrue(UpdatePolicy.downloadAllowed(status))
        assertFalse(UpdatePolicy.downloadAllowed(DownloadStatus.READY, "0.4.0", "0.4.0"))
        assertTrue(UpdatePolicy.downloadAllowed(DownloadStatus.READY, "0.4.0", "0.5.0"))
    }
}
