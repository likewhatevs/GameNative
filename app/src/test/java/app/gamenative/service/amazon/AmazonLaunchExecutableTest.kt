package app.gamenative.service.amazon

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pre-launch executable check must answer from the container config when it has one,
 * so it does not walk the install directory on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class AmazonLaunchExecutableTest {

    @Test
    fun `returns the configured executable without touching the install directory`() {
        val container = Container("AMAZON_42").apply { executablePath = "Bin/Game.exe" }

        assertEquals("Bin/Game.exe", AmazonService.getLaunchExecutable("AMAZON_42", container))
    }

    @Test
    fun `returns empty when the container has no executable and the game is not installed`() {
        val container = Container("AMAZON_42")

        assertEquals("", AmazonService.getLaunchExecutable("AMAZON_42", container))
    }
}
