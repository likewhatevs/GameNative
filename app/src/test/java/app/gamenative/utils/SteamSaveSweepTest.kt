package app.gamenative.utils

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the file selection a Steam Auto-Cloud `ufs.savefiles` rule describes: `pattern` filters
 * by file name, `recursive` decides whether subdirectories are candidates at all.
 *
 * The directory layouts here follow Dome Keeper (appid 1637320), whose three rules are all
 * non-recursive with pattern `*.*` while Godot keeps its caches in subdirectories of the same
 * save directory.
 */
@RunWith(RobolectricTestRunner::class)
class SteamSaveSweepTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File.createTempFile("steam_save_sweep_", null).also {
            it.delete()
            it.mkdirs()
        }
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun write(relativePath: String): File {
        val file = File(baseDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(relativePath)
        return file
    }

    private fun sweep(pattern: String, recursive: Boolean): Set<String> =
        SteamSaveSweep.findSaveFiles(baseDir.toPath(), pattern, recursive)
            .map { baseDir.toPath().relativize(it).toString() }
            .toSet()

    /** Dome Keeper's layout: saves in the rule's own directory, Godot's caches below it. */
    private fun writeDomeKeeperLayout() {
        write("savegame_0.json")
        write("options.txt")
        write("singleplayer/savegame_1.json")
        write("shader_cache/CanvasOcclusion/9f2c/1a4b.vulkan.cache")
        write("vulkan/pipelines.forward_plus.adreno.cache")
        write("logs/godot.log")
    }

    // ── pattern matching ──────────────────────────────────────────────────────────────────

    @Test
    fun starMatchesEveryFileName() {
        assertTrue(SteamSaveSweep.matchesPattern("savegame_0.json", "*"))
        assertTrue(SteamSaveSweep.matchesPattern("no_extension", "*"))
    }

    @Test
    fun starDotStarMatchesEveryFileName() {
        assertTrue(SteamSaveSweep.matchesPattern("savegame_0.json", "*.*"))
        assertTrue(SteamSaveSweep.matchesPattern("no_extension", "*.*"))
    }

    @Test
    fun extensionPatternMatchesOnlyThatExtension() {
        assertTrue(SteamSaveSweep.matchesPattern("a.sav", "*.sav"))
        assertFalse("*.sav must be anchored at the end", SteamSaveSweep.matchesPattern("foo.savedata", "*.sav"))
        assertFalse(SteamSaveSweep.matchesPattern("sav", "*.sav"))
    }

    @Test
    fun patternMatchingIsCaseInsensitive() {
        assertTrue(SteamSaveSweep.matchesPattern("a.sav", "*.SAV"))
        assertTrue(SteamSaveSweep.matchesPattern("A.SAV", "*.sav"))
        assertTrue(SteamSaveSweep.matchesPattern("systemdata_0.sav", "SystemData_0.sav"))
    }

    @Test
    fun wildcardDoesNotCrossADirectorySeparator() {
        assertFalse(SteamSaveSweep.matchesPattern("subdir/a.sav", "*.sav"))
        assertFalse(SteamSaveSweep.matchesPattern("subdir\\a.sav", "*.sav"))
    }

    @Test
    fun literalPatternMatchesOnlyThatName() {
        assertTrue(SteamSaveSweep.matchesPattern("save.dat", "save.dat"))
        assertFalse(SteamSaveSweep.matchesPattern("autosave.dat", "save.dat"))
        assertFalse(SteamSaveSweep.matchesPattern("save.dat.bak", "save.dat"))
    }

    @Test
    fun embeddedWildcardMatchesAroundTheLiteral() {
        assertTrue(SteamSaveSweep.matchesPattern("AutoSaveData.sav", "*SaveData*.sav"))
        assertTrue(SteamSaveSweep.matchesPattern("SaveData_0.sav", "*SaveData*.sav"))
        assertFalse(SteamSaveSweep.matchesPattern("SystemData_0.sav", "*SaveData*.sav"))
    }

    @Test
    fun emptyPatternMatchesNothing() {
        assertFalse(SteamSaveSweep.matchesPattern("a.sav", ""))
    }

    // ── engine cache deny-list ────────────────────────────────────────────────────────────

    @Test
    fun engineArtefactsAreDenied() {
        assertTrue(SteamSaveSweep.isEngineCache("shader_cache/CanvasOcclusion/9f2c/1a4b.vulkan.cache"))
        assertTrue(SteamSaveSweep.isEngineCache("vulkan/pipelines.forward_plus.adreno.cache"))
        assertTrue(SteamSaveSweep.isEngineCache("logs/godot.log"))
        assertTrue(SteamSaveSweep.isEngineCache("Saved\\Logs\\game.log"))
        assertTrue(SteamSaveSweep.isEngineCache("Saved/Crashes/UECC-1/report.txt"))
        assertTrue(SteamSaveSweep.isEngineCache(".godot/uid_cache.bin"))
        assertTrue(SteamSaveSweep.isEngineCache("Cache/atlas.bin"))
        assertTrue(SteamSaveSweep.isEngineCache("pipelines.cache"))
    }

    @Test
    fun plausibleSaveFilesAreNotDenied() {
        assertFalse(SteamSaveSweep.isEngineCache("savegame_0.json"))
        assertFalse(SteamSaveSweep.isEngineCache("options.txt"))
        assertFalse(SteamSaveSweep.isEngineCache("singleplayer/savegame_1.json"))
        assertFalse(SteamSaveSweep.isEngineCache("multiplayer/logbook.sav"))
        assertFalse(SteamSaveSweep.isEngineCache("DailyChallengesCache.dat"))
        assertFalse(SteamSaveSweep.isEngineCache("playerachievementcache.dat"))
        assertFalse(SteamSaveSweep.isEngineCache("player.log"))
        assertFalse("only the path below the rule's directory is inspected", SteamSaveSweep.isEngineCache("godot.log"))
    }

    // ── sweep ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun nonRecursiveRuleIgnoresSubdirectories() {
        writeDomeKeeperLayout()

        assertEquals(setOf("savegame_0.json", "options.txt"), sweep(pattern = "*.*", recursive = false))
    }

    @Test
    fun recursiveRuleDescendsButStillDropsEngineCaches() {
        writeDomeKeeperLayout()

        assertEquals(
            setOf("savegame_0.json", "options.txt", File("singleplayer/savegame_1.json").path),
            sweep(pattern = "*.*", recursive = true),
        )
    }

    @Test
    fun recursiveRuleMatchesTheFileNameNotTheRelativePath() {
        write("saves/quicksave.sav")
        write("saves/notes.txt")

        assertEquals(setOf(File("saves/quicksave.sav").path), sweep(pattern = "*.sav", recursive = true))
    }

    @Test
    fun missingDirectoryYieldsNoFiles() {
        assertEquals(emptyList<Any>(), SteamSaveSweep.findSaveFiles(File(baseDir, "absent").toPath(), "*", false))
    }
}
