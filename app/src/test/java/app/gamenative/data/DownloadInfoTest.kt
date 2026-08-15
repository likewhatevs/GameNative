package app.gamenative.data

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadInfoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `post install sync state is tracked independently`() {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        assertFalse(info.isPostInstallSyncing())

        info.setPostInstallSyncing(true)

        assertTrue(info.isPostInstallSyncing())
    }

    @Test
    fun `cancel clears post install sync state`() {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        info.setPostInstallSyncing(true)
        info.cancel()

        assertFalse(info.isPostInstallSyncing())
        assertFalse(info.isActive())
    }

    @Test
    fun `rapid progress snapshots are throttled to few disk writes`() {
        val appDir = temporaryFolder.newFolder("throttle")
        val clock = FakeClock()
        val info = newDownloadInfo(appDir, clock)

        // First call always writes; the following 999 land inside the same throttle window.
        var writes = 0
        repeat(1000) {
            info.updateBytesDownloaded(1024L, clock.nowMs, trackSpeed = false)
            info.persistProgressSnapshot()
            if (consumeWrite(appDir)) writes++
        }
        assertEquals(1, writes)

        // Crossing the time window allows exactly one more write.
        clock.nowMs += 10_000L
        info.updateBytesDownloaded(1024L, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        assertTrue(consumeWrite(appDir))

        info.updateBytesDownloaded(1024L, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        assertFalse(consumeWrite(appDir))
    }

    @Test
    fun `large byte delta persists without waiting for the time window`() {
        val appDir = temporaryFolder.newFolder("delta")
        val clock = FakeClock()
        val info = newDownloadInfo(appDir, clock)

        info.persistProgressSnapshot()
        assertTrue(consumeWrite(appDir))

        info.updateBytesDownloaded(64L * 1024 * 1024, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        assertTrue(consumeWrite(appDir))
    }

    @Test
    fun `forced snapshot writes the exact byte count after a suppressed call`() {
        val appDir = temporaryFolder.newFolder("forced")
        val clock = FakeClock()
        val info = newDownloadInfo(appDir, clock)

        info.updateBytesDownloaded(4096L, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        assertTrue(consumeWrite(appDir))

        info.updateBytesDownloaded(2048L, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        assertFalse("throttled call must not write", consumeWrite(appDir))

        info.persistProgressSnapshot(force = true)
        assertEquals("6144", persistedFile(appDir).readText())
    }

    @Test
    fun `cancel persists the final byte count`() {
        val appDir = temporaryFolder.newFolder("cancel")
        val clock = FakeClock()
        val info = newDownloadInfo(appDir, clock)

        info.updateBytesDownloaded(4096L, clock.nowMs, trackSpeed = false)
        info.persistProgressSnapshot()
        consumeWrite(appDir)
        info.updateBytesDownloaded(1L, clock.nowMs, trackSpeed = false)

        info.cancel()

        assertEquals("4097", persistedFile(appDir).readText())
    }

    private class FakeClock(var nowMs: Long = 1_000L)

    private fun newDownloadInfo(appDir: File, clock: FakeClock): DownloadInfo =
        DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        ).also {
            it.setTimeSource { clock.nowMs }
            it.setPersistencePath(appDir.absolutePath)
        }

    private fun persistedFile(appDir: File): File = File(File(appDir, ".DownloadInfo"), "bytes_downloaded.txt")

    /**
     * Returns whether a write happened since the last check, removing the file so the next
     * check starts from a clean slate. Deleting it does not disturb the throttle state.
     */
    private fun consumeWrite(appDir: File): Boolean {
        val file = persistedFile(appDir)
        if (!file.exists()) return false
        file.delete()
        return true
    }
}
