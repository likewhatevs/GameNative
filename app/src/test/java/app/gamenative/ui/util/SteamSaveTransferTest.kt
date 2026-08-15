package app.gamenative.ui.util

import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `recursive` is a boolean in the ufs schema, not a depth, so a rule that omits it covers only
 * the immediate children of its directory.
 */
@RunWith(RobolectricTestRunner::class)
class SteamSaveTransferTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File.createTempFile("steam_save_transfer_", null).also {
            it.delete()
            it.mkdirs()
        }
        File(baseDir, "savegame_0.json").writeText("save")
        File(baseDir, "singleplayer").mkdirs()
        File(baseDir, "singleplayer/savegame_1.json").writeText("save")
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
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
}
