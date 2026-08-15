package app.gamenative.ui.util

import `in`.dragonbra.javasteam.types.SteamID
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

/**
 * `recursive` is a boolean in the ufs schema, not a depth, so a rule that omits it covers only
 * the immediate children of its directory.
 *
 * The round-trip cases follow Dome Keeper (appid 1637320), a Godot game whose ufs rules are all
 * non-recursive over one user directory and its children, with the engine's caches written into
 * subdirectories of that same directory.
 */
@RunWith(RobolectricTestRunner::class)
class SteamSaveTransferTest {

    private lateinit var baseDir: File
    private lateinit var scratch: File

    /** Both transfers report failures through Timber, so keep them for the assertion messages. */
    private val logs = mutableListOf<String>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    logs += "$message: ${t?.toString().orEmpty()}"
                }
            },
        )
        baseDir = createTempDir("steam_save_transfer_")
        scratch = createTempDir("steam_save_roundtrip_")
        File(baseDir, "savegame_0.json").writeText("save")
        File(baseDir, "singleplayer").mkdirs()
        File(baseDir, "singleplayer/savegame_1.json").writeText("save")
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        logs.clear()
        baseDir.deleteRecursively()
        scratch.deleteRecursively()
        unmockkObject(SteamService.Companion)
    }

    private fun createTempDir(prefix: String): File =
        File.createTempFile(prefix, null).also {
            it.delete()
            it.mkdirs()
        }

    private fun pattern(recursive: Int) = SaveFilePattern(
        root = PathType.WinAppDataRoaming,
        path = "Godot/app_userdata/Dome Keeper",
        pattern = "*.*",
        recursive = recursive,
    )

    private fun names(recursive: Int) =
        SteamSaveTransfer.findPatternFiles(baseDir.toPath(), pattern(recursive))
            .map { baseDir.toPath().relativize(it).toString() }
            .toSet()

    @Test
    fun nonRecursiveRuleCollectsOnlyTheTopLevel() {
        assertEquals(setOf("savegame_0.json"), names(recursive = 0))
    }

    @Test
    fun recursiveRuleCollectsSubdirectories() {
        assertEquals(
            setOf("savegame_0.json", File("singleplayer/savegame_1.json").path),
            names(recursive = 1),
        )
    }

    // ── Round trip ────────────────────────────────────────────────────────────────────────

    private val domeKeeperRules = listOf(
        SaveFilePattern(PathType.WinAppDataRoaming, "Godot/app_userdata/Dome Keeper", "*.*"),
        SaveFilePattern(PathType.WinAppDataRoaming, "Godot/app_userdata/Dome Keeper/singleplayer", "*.*"),
        SaveFilePattern(PathType.WinAppDataRoaming, "Godot/app_userdata/Dome Keeper/multiplayer", "*.*"),
    )

    private fun stubApp(rules: List<SaveFilePattern>) {
        mockkObject(SteamService.Companion)
        every { SteamService.userSteamId } returns SteamID(STEAM_ID_64)
        every { SteamService.getAppInfoOf(DOME_KEEPER) } returns SteamApp(
            id = DOME_KEEPER,
            name = "Dome Keeper",
            ufs = UFS(saveFilePatterns = rules),
        )
    }

    private fun container(name: String): Container =
        Container("STEAM_$DOME_KEEPER").apply { setRootDir(File(scratch, name).apply { mkdirs() }) }

    private fun godotDir(container: Container, game: String = "Dome Keeper"): File = File(
        container.rootDir,
        ".wine/drive_c/users/${ImageFs.USER}/AppData/Roaming/Godot/app_userdata/$game",
    )

    /** Saves beside the caches and logs Godot writes below them. */
    private fun writeDomeKeeperSaves(dir: File) {
        listOf(
            "savegame_0.json",
            "options.txt",
            "singleplayer/savegame_1.json",
            "multiplayer/savegame_2.json",
            "shader_cache/CanvasOcclusion/9f2c/1a4b.vulkan.cache",
            "vulkan/pipelines.forward_plus.adreno.cache",
            "logs/godot.log",
        ).forEach { relative ->
            File(dir, relative).apply { parentFile?.mkdirs() }.writeText(relative)
        }
    }

    private val expectedSaves = mapOf(
        "savegame_0.json" to "savegame_0.json",
        "options.txt" to "options.txt",
        File("singleplayer/savegame_1.json").path to "singleplayer/savegame_1.json",
        File("multiplayer/savegame_2.json").path to "multiplayer/savegame_2.json",
    )

    private fun treeOf(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    private fun zipNames(archive: File): List<String> =
        ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }

    private fun export(source: Container, archive: File) {
        val exported = runBlocking {
            SteamSaveTransfer.exportSaves(context, source, DOME_KEEPER, Uri.fromFile(archive))
        }
        assertTrue("export must succeed: $logs", exported)
    }

    private fun import(target: Container, archive: File) {
        val imported = runBlocking {
            SteamSaveTransfer.importSaves(context, target, DOME_KEEPER, Uri.fromFile(archive))
        }
        assertTrue("import must accept the archive this export produced: $logs", imported)
    }

    @Test
    fun exportLeavesTheGodotCachesOutOfTheArchive() {
        stubApp(domeKeeperRules)
        val source = container("source")
        writeDomeKeeperSaves(godotDir(source))
        val archive = File(scratch, "saves.zip")

        export(source, archive)

        val entries = zipNames(archive)
        assertFalse(
            "engine caches must not reach the archive: $entries",
            entries.any { it.contains("shader_cache") || it.contains("vulkan/") || it.contains("logs/") },
        )
        assertEquals("the manifest plus the four saves", 5, entries.size)
    }

    @Test
    fun archiveRoundTripsIntoAFreshContainer() {
        stubApp(domeKeeperRules)
        val source = container("source")
        writeDomeKeeperSaves(godotDir(source))
        val archive = File(scratch, "saves.zip")
        export(source, archive)

        val target = container("target")
        import(target, archive)

        assertEquals(expectedSaves, treeOf(godotDir(target)))
    }

    /**
     * A root id folds in the rule's own path, so a ufs rule that moves between export and import
     * leaves the archive's ids unmatched. The files still belong in this container.
     */
    @Test
    fun archiveImportsAfterTheUfsRuleMoves() {
        stubApp(domeKeeperRules)
        val source = container("source")
        writeDomeKeeperSaves(godotDir(source))
        val archive = File(scratch, "saves.zip")
        export(source, archive)

        stubApp(domeKeeperRules.map { it.copy(path = it.path.replace("Dome Keeper", "DomeKeeper")) })
        val target = container("target")
        import(target, archive)

        assertEquals(expectedSaves, treeOf(godotDir(target)))
    }

    /**
     * Several ufs rules may name one directory with different masks; a file both masks match must
     * not become a duplicate zip entry.
     */
    @Test
    fun overlappingRulesOverOneDirectoryRoundTrip() {
        stubApp(
            listOf(
                SaveFilePattern(PathType.WinAppDataRoaming, "Godot/app_userdata/Dome Keeper", "*.*"),
                SaveFilePattern(PathType.WinAppDataRoaming, "Godot/app_userdata/Dome Keeper", "*.json"),
            ),
        )
        val source = container("source")
        writeDomeKeeperSaves(godotDir(source))
        val archive = File(scratch, "saves.zip")
        export(source, archive)

        val entries = zipNames(archive)
        assertEquals("each file is archived once", entries.size, entries.toSet().size)

        val target = container("target")
        import(target, archive)
        assertEquals(
            mapOf("savegame_0.json" to "savegame_0.json", "options.txt" to "options.txt"),
            treeOf(godotDir(target)),
        )
    }

    private companion object {
        const val DOME_KEEPER = 1637320
        const val STEAM_ID_64 = 76561198025127569L
    }
}
