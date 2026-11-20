package io.github.midihl.wastickersimporter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseModelsTest {
    @Test
    fun comparesNumericVersionsAndPadsMissingParts() {
        assertTrue(ReleaseVersions.isNewer("v3.2", "3.1.2"))
        assertTrue(ReleaseVersions.isNewer("3.1.3", "3.1.2"))
        assertTrue(ReleaseVersions.isNewer("3.10.0", "3.9.12"))
        assertTrue(ReleaseVersions.isNewer("V4", "3.999.999"))
        assertFalse(ReleaseVersions.isNewer("v3.1", "3.1.0"))
        assertFalse(ReleaseVersions.isNewer("v3.1.2", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("2.0.1", "3.1.2"))
    }

    @Test
    fun ignoresPrereleaseBuildAndMalformedTags() {
        assertFalse(ReleaseVersions.isNewer("v4.0.0-beta.1", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("v4.0.0+ci36", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("release-36", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("v01.2.3", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("3.999999999999999999.9", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("4.0.0.1", "3.1.2"))
        assertFalse(ReleaseVersions.isNewer("4.0.0", "invalid"))
    }

    @Test
    fun preferredAssetFollowsAbiOrderThenUniversal() {
        val release = AppRelease("v3.2.0", "3.2.0", "https://github.com/midi-HL/wastickers-importer/releases/tag/v3.2.0", "", listOf(
            ReleaseAsset("app-universal-release.apk", "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/app-universal-release.apk", 10),
            ReleaseAsset("app-x86_64-release.apk", "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/app-x86_64-release.apk", 11),
            ReleaseAsset("app-arm64-v8a-release.apk", "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/app-arm64-v8a-release.apk", 12),
            ReleaseAsset("app-arm64-v8a-debug.apk", "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/app-arm64-v8a-debug.apk", 13)
        ))
        assertEquals("app-x86_64-release.apk", ReleaseAssets.preferred(release, listOf("x86_64", "arm64-v8a"))?.name)
        assertEquals("app-universal-release.apk", ReleaseAssets.preferred(release, listOf("armeabi-v7a"))?.name)
    }

    @Test
    fun preferredAssetReturnsNullWhenOnlyNonReleaseArtifactsExist() {
        val release = AppRelease("v3.2.0", "3.2.0", "https://github.com/midi-HL/wastickers-importer/releases/tag/v3.2.0", "", listOf(
            ReleaseAsset("app-arm64-v8a-debug.apk", "https://example.test/debug.apk", 10),
            ReleaseAsset("app-arm64-v8a-release-unsigned.apk", "https://example.test/unsigned.apk", 10),
            ReleaseAsset("app-debug-androidTest.apk", "https://example.test/test.apk", 10)
        ))
        assertNull(ReleaseAssets.preferred(release, listOf("arm64-v8a")))
    }

    @Test
    fun x86DeviceDoesNotSelectAnX86_64Apk() {
        val release = releaseWithNames("app-x86_64-release.apk", "app-universal-release.apk")
        assertEquals("app-universal-release.apk", ReleaseAssets.preferred(release, listOf("x86"))?.name)
        assertNull(ReleaseAssets.preferred(releaseWithNames("app-x86_64-release.apk"), listOf("x86")))
    }

    @Test
    fun acceptsBrandedApksButPrefersExplicitReleaseBuilds() {
        val release = releaseWithNames(
            "sticker-studio-3.2.0-arm64-v8a.apk",
            "app-arm64-v8a-release.apk",
            "sticker-studio-3.2.0-universal.apk"
        )
        assertEquals("app-arm64-v8a-release.apk", ReleaseAssets.preferred(release, listOf("arm64-v8a"))?.name)
        assertEquals("sticker-studio-3.2.0-universal.apk", ReleaseAssets.preferred(release, listOf("unknown"))?.name)
    }

    @Test
    fun releaseLinksStayOnTheRepositoryAndSelectedTag() {
        val page = "https://github.com/midi-HL/wastickers-importer/releases/tag/v3.2.0"
        val download = "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/app-arm64-v8a-release.apk"
        assertTrue(ReleaseUrls.isReleasePage(page, "v3.2.0"))
        assertTrue(ReleaseUrls.isReleaseDownload(download, "v3.2.0"))
        assertFalse(ReleaseUrls.isReleasePage(page, "v3.1.2"))
        assertFalse(ReleaseUrls.isReleaseDownload(download, "v3.1.2"))
        for (bad in listOf(
            download.replace("https://", "http://"),
            download.replace("github.com", "github.com.example.test"),
            download.replace("github.com/", "github.com:444/"),
            download.replace("github.com/", "user@github.com/"),
            download.replace("midi-HL", "another-owner"),
            download.replace("app-arm64-v8a-release.apk", "../other.apk"),
            download.replace("app-arm64-v8a-release.apk", "%2e%2e%2fother.apk"),
            download + "?redirect=https://example.test",
            download + "#fragment"
        )) assertFalse(bad, ReleaseUrls.isReleaseDownload(bad, "v3.2.0"))
    }

    private fun releaseWithNames(vararg names: String): AppRelease = AppRelease(
        "v3.2.0", "3.2.0", "https://github.com/midi-HL/wastickers-importer/releases/tag/v3.2.0", "",
        names.map { ReleaseAsset(it, "https://github.com/midi-HL/wastickers-importer/releases/download/v3.2.0/$it", 10) }
    )
}
