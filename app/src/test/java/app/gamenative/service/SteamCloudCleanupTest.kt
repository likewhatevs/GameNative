package app.gamenative.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.BranchInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.service.SteamCloudCleanup.CloudFileKind
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamCloudCleanupTest {

    private lateinit var context: Context
    private lateinit var mockSteamService: SteamService
    private lateinit var mockSteamCloud: SteamCloud

    private val steamAppId = 123456
    private val clientId = 1L

    /** Dome Keeper's rule: the whole Godot user directory, recursively. */
    private val godotPattern = SaveFilePattern(
        root = PathType.WinAppDataRoaming,
        path = "Godot/app_userdata/Dome Keeper",
        pattern = "*",
        recursive = 1,
    )

    private val godotPrefix = "%WinAppDataRoaming%Godot/app_userdata/Dome Keeper"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        PrefManager.setLongBlocking(SteamAutoCloud.openUploadBatchKey(steamAppId), 0L)

        mockSteamService = mock()
        mockSteamCloud = mockk(relaxed = true)

        every { mockSteamCloud.completeAppUploadBatch(any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(Unit)
    }

    // ── Classification ──

    @Test
    fun engineCacheFilesAreSelectedAndSavesAreNot() {
        val classified = classifyGodotFiles(
            "shader_cache/CanvasOcclusion/9f2c/1a4b.vulkan.cache",
            "vulkan/pipelines.forward_plus.adreno.cache",
            ".godot/imported/icon.png",
            "logs/godot.log",
            "crashes/2024-01-01.dmp",
            "material.cache",
            "savegame_0.json",
            "savegame_0.sav",
            "save",
            "settings.cfg",
            "saves/slot1.sav",
            "cached_saves/slot1.sav",
            "shader_cache_backup.json",
        )

        listOf(
            "shader_cache/CanvasOcclusion/9f2c/1a4b.vulkan.cache",
            "vulkan/pipelines.forward_plus.adreno.cache",
            ".godot/imported/icon.png",
            "logs/godot.log",
            "crashes/2024-01-01.dmp",
            "material.cache",
        ).forEach { relative ->
            assertEquals(
                "$relative is regenerable engine data",
                CloudFileKind.EngineCache,
                classified.getValue("$godotPrefix/$relative").kind,
            )
        }

        listOf(
            // Saves sitting alongside the cache directories, and directories that merely read
            // like a cache without being one.
            "savegame_0.json",
            "savegame_0.sav",
            "save",
            "settings.cfg",
            "saves/slot1.sav",
            "cached_saves/slot1.sav",
            "shader_cache_backup.json",
        ).forEach { relative ->
            assertEquals(
                "$relative may be save data and must be kept",
                CloudFileKind.SaveFile,
                classified.getValue("$godotPrefix/$relative").kind,
            )
        }
    }

    @Test
    fun filesUnderNoRuleOfThisDeviceAreNeverSelected() {
        val rulePrefixes = SteamCloudCleanup.cloudRulePrefixes(UFS(saveFilePatterns = listOf(godotPattern)))

        val classified = SteamCloudCleanup.classify(
            rulePrefixes,
            listOf(
                // A save from a macOS install: a root this container does not resolve.
                remoteFile("%MacHome%Library/Application Support/Dome Keeper", "savegame_0.json"),
                // Engine cache, but under a rule this device knows nothing about. Deleting by
                // name alone would mean trusting a path we cannot account for.
                remoteFile("%LinuxHome%.local/share/godot", "shader_cache/1a4b.cache"),
                // No prefix at all.
                remoteFile("", "stray.dat"),
            ),
        )

        assertTrue(
            "nothing outside a known rule may be selected: $classified",
            classified.all { it.kind == CloudFileKind.Unrecognised },
        )
    }

    @Test
    fun theSteamUserDataRootIsAlwaysCovered() {
        // The local scan walks this root whether or not the app declares a rule for it.
        val classified = SteamCloudCleanup.classify(
            SteamCloudCleanup.cloudRulePrefixes(UFS()),
            listOf(
                remoteFile("%SteamUserData%", "cache/pipeline.bin"),
                remoteFile("%SteamUserData%", "remote.sav"),
            ),
        )

        assertEquals(CloudFileKind.EngineCache, classified[0].kind)
        assertEquals(CloudFileKind.SaveFile, classified[1].kind)
    }

    @Test
    fun aRuleWhoseCloudPrefixOmitsTheSeparatorStillMatches() {
        // Steam hands back prefixes both with and without a slash after the placeholder.
        val classified = SteamCloudCleanup.classify(
            SteamCloudCleanup.cloudRulePrefixes(UFS(saveFilePatterns = listOf(godotPattern))),
            listOf(remoteFile("%WinAppDataRoaming%/Godot/app_userdata/Dome Keeper/", "logs/godot.log")),
        )

        assertEquals(CloudFileKind.EngineCache, classified.single().kind)
    }

    // ── Enumeration ──

    @Test
    fun aFailedListingProducesNoDeletionSet() = runBlocking {
        val failed = CompletableFuture<AppFileChangeList>()
        failed.completeExceptionally(IllegalStateException("no route to host"))

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns failed

        val result = SteamCloudCleanup.scan(steamApp(), mockSteamCloud)

        assertTrue("a failed listing must not become a deletion set: $result", result is SteamCloudCleanup.ScanResult.Failed)
        verify(exactly = 0) { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun anEmptyListingProducesNoDeletionSet() = runBlocking {
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(fileChangeList(emptyList(), emptyList()))

        val result = SteamCloudCleanup.scan(steamApp(), mockSteamCloud)

        assertTrue("an empty listing must not become a deletion set: $result", result is SteamCloudCleanup.ScanResult.Failed)
        verify(exactly = 0) { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun aPartialListingProducesNoDeletionSet() = runBlocking {
        val fileList = fileChangeList(
            listOf(godotPrefix),
            listOf(cloudFile("shader_cache/1a4b.cache", prefixIndex = 0)),
        )
        whenever(fileList.isOnlyDelta).thenReturn(true)

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(fileList)

        val result = SteamCloudCleanup.scan(steamApp(), mockSteamCloud)

        assertTrue("a delta cannot be reconciled against: $result", result is SteamCloudCleanup.ScanResult.Failed)
        verify(exactly = 0) { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun aFullListingSelectsOnlyTheEngineCache() = runBlocking {
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(
                fileChangeList(
                    listOf(godotPrefix),
                    listOf(
                        cloudFile("shader_cache/1a4b.cache", prefixIndex = 0),
                        cloudFile("savegame_0.json", prefixIndex = 0),
                    ),
                ),
            )

        val result = SteamCloudCleanup.scan(steamApp(), mockSteamCloud)

        assertTrue(result is SteamCloudCleanup.ScanResult.Ready)
        val ready = result as SteamCloudCleanup.ScanResult.Ready
        assertEquals(listOf("$godotPrefix/shader_cache/1a4b.cache"), ready.removable.map { it.path })
        assertEquals(listOf("$godotPrefix/savegame_0.json"), ready.kept.map { it.path })
    }

    // ── Deletion ──

    @Test
    fun anEmptySetOpensNoBatch() = runBlocking {
        val result = SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = emptyList(),
        )

        assertEquals(0, result.deleted)
        verify(exactly = 0) { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun everyConfirmedPathIsDeletedExactlyOnceAcrossBatches() = runBlocking {
        val paths = (1..(SteamCloudCleanup.MAX_DELETES_PER_BATCH * 2 + 25)).map { "$godotPrefix/shader_cache/$it.cache" }
        val batches = recordBatches()

        val result = SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = paths,
        )

        assertEquals(3, batches.size)
        assertTrue(
            "no batch may exceed the chunk size: ${batches.map { it.size }}",
            batches.all { it.size <= SteamCloudCleanup.MAX_DELETES_PER_BATCH },
        )

        val sent = batches.flatten()
        assertEquals("no path may be sent twice", sent.size, sent.distinct().size)
        assertEquals("the batches must cover exactly the confirmed set", paths, sent)
        assertEquals(paths.size, result.deleted)
        assertTrue(result.failedPaths.isEmpty())
    }

    @Test
    fun nothingIsUploadedWhileDeleting() = runBlocking {
        val uploads = mutableListOf<List<String>>()
        every { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            uploads += args[2] as List<String>
            CompletableFuture.completedFuture(uploadBatchResponse(batchId = 1L))
        }

        SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = listOf("$godotPrefix/shader_cache/1.cache"),
        )

        assertEquals(listOf(emptyList<String>()), uploads)
    }

    @Test
    fun aBatchThatFailsIsClosedWithAFailureResult() = runBlocking {
        val batches = recordBatches()

        val paths = (1..(SteamCloudCleanup.MAX_DELETES_PER_BATCH + 5)).map { "$godotPrefix/shader_cache/$it.cache" }
        var progressCalls = 0

        val result = SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = paths,
            onProgress = { _, _ ->
                // Stands in for anything that throws while a batch is open, cancellation included.
                if (progressCalls++ == 0) throw IllegalStateException("interrupted")
            },
        )

        // Failing one batch must not take the rest of the set down with it.
        assertEquals(2, batches.size)
        assertEquals(batches[0], result.failedPaths)
        assertEquals(batches[1].size, result.deleted)

        verify { mockSteamCloud.completeAppUploadBatch(steamAppId, 1L, EResult.Fail, any()) }
        verify { mockSteamCloud.completeAppUploadBatch(steamAppId, 2L, EResult.OK, any()) }
        assertEquals(
            "no batch may be left recorded as open",
            0L,
            PrefManager.getLong(SteamAutoCloud.openUploadBatchKey(steamAppId), -1L),
        )
    }

    @Test
    fun aRefusedBatchIsReportedAndNotClosed() = runBlocking {
        val paths = (1..(SteamCloudCleanup.MAX_DELETES_PER_BATCH + 5)).map { "$godotPrefix/shader_cache/$it.cache" }
        var batchId = 0L
        val batches = mutableListOf<List<String>>()

        every { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            batches += args[3] as List<String>
            // Steam refuses the first batch: an unset id is the only signal it gives.
            CompletableFuture.completedFuture(uploadBatchResponse(batchId = if (++batchId == 1L) 0L else batchId))
        }

        val result = SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = paths,
        )

        assertEquals(batches[0], result.failedPaths)
        assertEquals(batches[1].size, result.deleted)
        verify(exactly = 1) { mockSteamCloud.completeAppUploadBatch(any(), any(), any(), any()) }
        verify { mockSteamCloud.completeAppUploadBatch(steamAppId, 2L, EResult.OK, any()) }
    }

    @Test
    fun aBatchLeftOpenByAnEarlierSessionIsClosedFirst() = runBlocking {
        PrefManager.setLongBlocking(SteamAutoCloud.openUploadBatchKey(steamAppId), 99L)
        recordBatches()

        SteamCloudCleanup.deleteFiles(
            appInfo = steamApp(),
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            paths = listOf("$godotPrefix/shader_cache/1.cache"),
        )

        verify { mockSteamCloud.completeAppUploadBatch(steamAppId, 99L, EResult.Fail, any()) }
    }

    // ── Helpers ──

    private fun classifyGodotFiles(vararg relativePaths: String): Map<String, SteamCloudCleanup.CloudFile> =
        SteamCloudCleanup.classify(
            SteamCloudCleanup.cloudRulePrefixes(UFS(saveFilePatterns = listOf(godotPattern))),
            relativePaths.map { remoteFile(godotPrefix, it) },
        ).associateBy { it.path }

    private fun remoteFile(prefix: String, filename: String) =
        SteamCloudCleanup.RemoteFile(prefix = prefix, filename = filename, sizeBytes = 1L)

    private fun steamApp() = SteamApp(
        id = steamAppId,
        name = "Dome Keeper",
        branches = mapOf(
            "public" to BranchInfo(name = "public", buildId = 42, pwdRequired = false, timeUpdated = Date(0)),
        ),
        ufs = UFS(saveFilePatterns = listOf(godotPattern)),
    )

    private fun fileChangeList(prefixes: List<String>, files: List<AppFileInfo>): AppFileChangeList {
        val fileList = mock<AppFileChangeList>()
        whenever(fileList.currentChangeNumber).thenReturn(7L)
        whenever(fileList.isOnlyDelta).thenReturn(false)
        whenever(fileList.pathPrefixes).thenReturn(prefixes)
        whenever(fileList.files).thenReturn(files)
        return fileList
    }

    private fun cloudFile(filename: String, prefixIndex: Int): AppFileInfo {
        val file = mock<AppFileInfo>()
        whenever(file.filename).thenReturn(filename)
        whenever(file.hasPathPrefixIndex).thenReturn(true)
        whenever(file.pathPrefixIndex).thenReturn(prefixIndex)
        whenever(file.rawFileSize).thenReturn(1)
        return file
    }

    private fun uploadBatchResponse(batchId: Long): AppUploadBatchResponse {
        val response = mock<AppUploadBatchResponse>()
        whenever(response.batchID).thenReturn(batchId)
        whenever(response.appChangeNumber).thenReturn(8L)
        return response
    }

    /** Records the delete list of every batch, handing each one a distinct id. */
    private fun recordBatches(): MutableList<List<String>> {
        val batches = mutableListOf<List<String>>()
        var batchId = 0L

        every { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            batches += args[3] as List<String>
            CompletableFuture.completedFuture(uploadBatchResponse(batchId = ++batchId))
        }

        return batches
    }
}
