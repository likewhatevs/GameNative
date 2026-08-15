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

    // ── sweep ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun nonRecursiveRuleIgnoresSubdirectories() {
        write("savegame_0.json")
        write("options.txt")
        write("singleplayer/savegame_1.json")

        assertEquals(setOf("savegame_0.json", "options.txt"), sweep(pattern = "*.*", recursive = false))
    }

    @Test
    fun recursiveRuleDescendsIntoSubdirectories() {
        write("savegame_0.json")
        write("singleplayer/savegame_1.json")

        assertEquals(
            setOf("savegame_0.json", File("singleplayer/savegame_1.json").path),
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
