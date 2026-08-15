package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search field runs with the soft keyboard up, so anything it swallows is a key the
 * IME and the field itself never get to see. Only the deliberate way out is consumed.
 */
class LibrarySearchBarKeysTest {

    @Test
    fun `d-pad down leaves the field`() {
        assertTrue(consumesSearchFieldKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `key up is never consumed`() {
        assertFalse(consumesSearchFieldKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `other d-pad directions fall through to the field`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        ).forEach { keyCode ->
            assertFalse(
                "d-pad key $keyCode must reach the field",
                consumesSearchFieldKey(KeyEvent.ACTION_DOWN, keyCode),
            )
        }
    }

    @Test
    fun `typing and editing keys fall through to the field`() {
        listOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ENTER,
        ).forEach { keyCode ->
            assertFalse(
                "key $keyCode must reach the field",
                consumesSearchFieldKey(KeyEvent.ACTION_DOWN, keyCode),
            )
        }
    }

    @Test
    fun `controller buttons fall through so the library can act on them`() {
        listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BACK,
        ).forEach { keyCode ->
            assertFalse(
                "button $keyCode must reach the library",
                consumesSearchFieldKey(KeyEvent.ACTION_DOWN, keyCode),
            )
        }
    }
}
