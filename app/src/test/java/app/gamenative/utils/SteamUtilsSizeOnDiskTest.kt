package app.gamenative.utils

import app.gamenative.data.AppInfo
import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.EnumSet

/**
 * Covers the ACF `SizeOnDisk` source selection: depot metadata first, then the recovered
 * size cached on the app row, and only then a walk of the install directory.
 */
class SteamUtilsSizeOnDiskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun manifest(gid: Long = 1L, size: Long) =
        ManifestInfo(name = "manifest", gid = gid, size = size, download = size / 2)

    private fun depot(depotId: Int, manifests: Map<String, ManifestInfo>) = DepotInfo(
        depotId = depotId,
        dlcAppId = SteamService.INVALID_APP_ID,
        depotFromApp = 0,
        sharedInstall = false,
        osList = EnumSet.of(OS.windows),
        osArch = OSArch.Arch64,
        manifests = manifests,
        encryptedManifests = emptyMap(),
    )

    private fun appInfo(
        downloadedDepots: List<Int> = emptyList(),
        branch: String = "public",
        recoveredInstallSizeBytes: Long = 0L,
    ) = AppInfo(
        id = 123456,
        isDownloaded = true,
        downloadedDepots = downloadedDepots,
        branch = branch,
        recoveredInstallSizeBytes = recoveredInstallSizeBytes,
    )

    private fun dirWithFile(sizeBytes: Int): File {
        val dir = tempFolder.newFolder()
        File(dir, "payload.bin").writeBytes(ByteArray(sizeBytes))
        return dir
    }

    @Test
    fun `sums manifest sizes of the downloaded depots only`() {
        val depots = mapOf(
            1 to depot(1, mapOf("public" to manifest(size = 1000L))),
            2 to depot(2, mapOf("public" to manifest(size = 2000L))),
            3 to depot(3, mapOf("public" to manifest(size = 4000L))),
        )

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(downloadedDepots = listOf(1, 3)),
            installedBranch = "public",
            depots = depots,
            gameDir = dirWithFile(64),
        )

        assertEquals(5000L, size)
    }

    @Test
    fun `uses the installed branch manifest when the depot has one`() {
        val depots = mapOf(
            1 to depot(
                1,
                mapOf(
                    "public" to manifest(size = 1000L),
                    "beta" to manifest(gid = 2L, size = 7000L),
                ),
            ),
        )

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(downloadedDepots = listOf(1), branch = "beta"),
            installedBranch = "beta",
            depots = depots,
            gameDir = dirWithFile(64),
        )

        assertEquals(7000L, size)
    }

    @Test
    fun `falls back to the public manifest when the branch has none`() {
        val depots = mapOf(1 to depot(1, mapOf("public" to manifest(size = 1000L))))

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(downloadedDepots = listOf(1), branch = "beta"),
            installedBranch = "beta",
            depots = depots,
            gameDir = dirWithFile(64),
        )

        assertEquals(1000L, size)
    }

    @Test
    fun `falls back to the recovered size when no downloaded depot is known`() {
        val depots = mapOf(1 to depot(1, mapOf("public" to manifest(size = 1000L))))

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(downloadedDepots = emptyList(), recoveredInstallSizeBytes = 4242L),
            installedBranch = "public",
            depots = depots,
            gameDir = dirWithFile(64),
        )

        assertEquals(4242L, size)
    }

    @Test
    fun `prefers depot metadata over the recovered size`() {
        val depots = mapOf(1 to depot(1, mapOf("public" to manifest(size = 1000L))))

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(downloadedDepots = listOf(1), recoveredInstallSizeBytes = 4242L),
            installedBranch = "public",
            depots = depots,
            gameDir = dirWithFile(64),
        )

        assertEquals(1000L, size)
    }

    @Test
    fun `walks the install directory when no metadata is available`() {
        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = appInfo(),
            installedBranch = "public",
            depots = emptyMap(),
            gameDir = dirWithFile(2048),
        )

        assertEquals(2048L, size)
    }

    @Test
    fun `walks the install directory when the app is not in the database`() {
        val depots = mapOf(1 to depot(1, mapOf("public" to manifest(size = 1000L))))

        val size = SteamUtils.resolveSizeOnDisk(
            installedApp = null,
            installedBranch = "public",
            depots = depots,
            gameDir = dirWithFile(512),
        )

        assertEquals(512L, size)
    }

    @Test
    fun `resolveManifest prefers branch then public then any`() {
        val branchOnly = depot(1, mapOf("beta" to manifest(gid = 9L, size = 1L)))
        assertEquals(9L, SteamUtils.resolveManifest(branchOnly, "beta")?.gid)
        assertEquals(9L, SteamUtils.resolveManifest(branchOnly, "missing")?.gid)

        val bothBranches = depot(
            2,
            mapOf("public" to manifest(gid = 1L, size = 1L), "beta" to manifest(gid = 2L, size = 1L)),
        )
        assertEquals(2L, SteamUtils.resolveManifest(bothBranches, "beta")?.gid)
        assertEquals(1L, SteamUtils.resolveManifest(bothBranches, "missing")?.gid)

        assertNull(SteamUtils.resolveManifest(depot(3, emptyMap()), "public"))
    }
}
