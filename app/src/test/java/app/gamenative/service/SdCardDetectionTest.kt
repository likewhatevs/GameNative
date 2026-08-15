package app.gamenative.service

import app.gamenative.enums.Marker
import app.gamenative.utils.MarkerUtils
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SdCardDetectionTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    @After
    fun tearDown() {
        SteamService.invalidateInstallPathCaches()
    }

    private fun createGameDir(base: File, gameName: String, complete: Boolean): File {
        val dir = File(base, gameName)
        dir.mkdirs()
        if (complete) {
            File(dir, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        }
        return dir
    }

    private fun markInProgress(dir: File) {
        File(dir, Marker.DOWNLOAD_IN_PROGRESS_MARKER.fileName).createNewFile()
    }

    private fun writePersistedProgress(dir: File) {
        val infoDir = File(dir, ".DownloadInfo")
        infoDir.mkdirs()
        File(infoDir, "bytes_downloaded.txt").writeText("1024")
    }

    @Test
    fun `completed install on second volume preferred over partial on first`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = true)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(sdcard, "MyGame").absolutePath, result)
    }

    @Test
    fun `falls back to first existing when none are complete`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = false)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(internal, "MyGame").absolutePath, result)
    }

    @Test
    fun `returns null when no directory exists`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertNull(result)
    }

    @Test
    fun `empty name is skipped — never returns install root`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf(""))

        assertNull(result)
    }

    @Test
    fun `completed install on a later root beats a resumable partial on an earlier one`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        // a download killed mid-install leaves its marker behind with nothing to clear it;
        // internal storage is probed first, so this must not shadow the real install
        markInProgress(createGameDir(internal, "MyGame", complete = false))
        createGameDir(sdcard, "MyGame", complete = true)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(sdcard, "MyGame").absolutePath, result)
    }

    @Test
    fun `completed install on a later root beats persisted byte progress on an earlier one`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        // the byte counter appears early in any download and no code path removes it on a
        // kill, so it is an even weaker signal than the marker
        writePersistedProgress(createGameDir(internal, "MyGame", complete = false))
        createGameDir(sdcard, "MyGame", complete = true)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(sdcard, "MyGame").absolutePath, result)
    }

    @Test
    fun `completed install beats a resumable partial found earlier in the walk`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = true)
        markInProgress(createGameDir(sdcard, "MyGame", complete = false))

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(internal, "MyGame").absolutePath, result)
    }

    @Test
    fun `resumable partial beats a bare leftover directory found earlier`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        val resumable = createGameDir(sdcard, "MyGame", complete = false)
        markInProgress(resumable)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(resumable.absolutePath, result)
    }

    @Test
    fun `persisted byte progress counts as resumable`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        val resumable = createGameDir(sdcard, "MyGame", complete = false)
        writePersistedProgress(resumable)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(resumable.absolutePath, result)
    }

    @Test
    fun `completion marker is the only early exit`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        markInProgress(createGameDir(internal, "MyGame", complete = false))
        val later = createGameDir(sdcard, "MyGame", complete = false)

        mockkObject(MarkerUtils)
        try {
            val paths = listOf(internal.absolutePath, sdcard.absolutePath)
            SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

            // a partial match must not stop the walk, or a completed install sitting on a
            // later root would never be seen
            verify { MarkerUtils.hasMarker(later.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER) }
        } finally {
            unmockkObject(MarkerUtils)
        }
    }

    @Test
    fun `a directory holding both markers resolves as completed`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")

        val dir = createGameDir(internal, "MyGame", complete = true)
        markInProgress(dir)

        val result = SteamService.resolveExistingAppDir(listOf(internal.absolutePath), listOf("MyGame"))

        assertEquals(dir.absolutePath, result)
    }

    /* ---------------------------------------------------------------------- */
    /* getAppDirPath caching                                                  */
    /* ---------------------------------------------------------------------- */

    private fun stubAppResolution(installPaths: List<String>) {
        every { SteamService.getAppInfoOf(APP_ID) } returns null
        every { SteamService.getInstalledApp(APP_ID) } returns null
        every { SteamService.getAppDirName(any()) } returns "MyGame"
        every { SteamService.allInstallPaths } returns installPaths
    }

    @Test
    fun `getAppDirPath reuses its resolution until the app cache is invalidated`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")
        createGameDir(internal, "MyGame", complete = false)

        mockkObject(SteamService.Companion)
        try {
            stubAppResolution(listOf(internal.absolutePath, sdcard.absolutePath))
            SteamService.invalidateInstallPathCaches()

            assertEquals(File(internal, "MyGame").absolutePath, SteamService.getAppDirPath(APP_ID))

            // disk changes underneath: a completed copy shows up on the card
            createGameDir(sdcard, "MyGame", complete = true)
            assertEquals(
                "cached resolution must be reused until something invalidates it",
                File(internal, "MyGame").absolutePath,
                SteamService.getAppDirPath(APP_ID),
            )

            // install start / completion / uninstall / game move all go through this
            SteamService.invalidateAppDirCache(APP_ID)
            assertEquals(File(sdcard, "MyGame").absolutePath, SteamService.getAppDirPath(APP_ID))
        } finally {
            SteamService.invalidateInstallPathCaches()
            unmockkObject(SteamService.Companion)
        }
    }

    @Test
    fun `invalidateInstallPathCaches drops per-app resolutions too`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")
        createGameDir(internal, "MyGame", complete = false)

        mockkObject(SteamService.Companion)
        try {
            stubAppResolution(listOf(internal.absolutePath, sdcard.absolutePath))
            SteamService.invalidateInstallPathCaches()

            assertEquals(File(internal, "MyGame").absolutePath, SteamService.getAppDirPath(APP_ID))

            createGameDir(sdcard, "MyGame", complete = true)
            // this is what an external-storage preference write triggers
            SteamService.invalidateInstallPathCaches()

            assertEquals(File(sdcard, "MyGame").absolutePath, SteamService.getAppDirPath(APP_ID))
        } finally {
            SteamService.invalidateInstallPathCaches()
            unmockkObject(SteamService.Companion)
        }
    }

    @Test
    fun `peekAppInstalled is empty until resolved and is cleared by invalidation`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        createGameDir(internal, "MyGame", complete = true)

        mockkObject(SteamService.Companion)
        try {
            stubAppResolution(listOf(internal.absolutePath))
            SteamService.invalidateInstallPathCaches()

            assertNull(SteamService.peekAppInstalled(APP_ID))

            assertTrue(SteamService.isAppInstalled(APP_ID))
            assertEquals(true, SteamService.peekAppInstalled(APP_ID))

            SteamService.invalidateAppDirCache(APP_ID)
            assertNull(SteamService.peekAppInstalled(APP_ID))
        } finally {
            SteamService.invalidateInstallPathCaches()
            unmockkObject(SteamService.Companion)
        }
    }

    private companion object {
        const val APP_ID = 736260
    }
}
