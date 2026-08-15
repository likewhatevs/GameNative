package app.gamenative

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Covers the guarantees the rest of the app relies on when reading preferences:
 * reads never block on a shared coroutine pool, and a written value is visible
 * to the very next read even though it is persisted asynchronously.
 */
class PrefManagerTest {

    private lateinit var filesDir: File
    private lateinit var context: Context

    /** Restored after every test: PrefManager is a singleton shared with the rest of the suite. */
    private var storedSyncDir: String = ""

    @Before
    fun setUp() {
        filesDir = createTempDirectory("prefmanager_test").toFile()
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        Mockito.`when`(context.applicationContext).thenReturn(context)

        PrefManager.init(context)
        storedSyncDir = PrefManager.frontendSyncDirCustom
    }

    @After
    fun tearDown() {
        // A test may have torn down the initialised state to check pre-init behaviour.
        PrefManager.init(context)
        PrefManager.frontendSyncDirCustom = storedSyncDir

        // Leave the store agreeing with the cache: tests that drop the cache read it back.
        assertTrue("timed out restoring the stored value", PrefManager.awaitPendingWrites(TIMEOUT_MS))

        // Only ours to delete if the shared DataStore singleton did not settle on it.
        if (!File(filesDir, "datastore").exists()) {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `many concurrent readers on the IO dispatcher all complete`() {
        val readers = 256

        val values = runBlocking {
            withTimeout(TIMEOUT_MS) {
                (0 until readers).map { index ->
                    async(Dispatchers.IO) {
                        PrefManager.frontendSyncDirCustom to
                            PrefManager.getString("prefmanager_test_absent_$index", "fallback")
                    }
                }.awaitAll()
            }
        }

        assertEquals(readers, values.size)
        values.forEach { (syncDir, absent) ->
            assertEquals(storedSyncDir, syncDir)
            assertEquals("fallback", absent)
        }
    }

    @Test
    fun `a written value is visible to the next read`() {
        PrefManager.setFloat("prefmanager_test_float", 1.5f)
        assertEquals(1.5f, PrefManager.getFloat("prefmanager_test_float", 0f), 0f)

        PrefManager.frontendSyncDirCustom = "/storage/frontend"
        assertEquals("/storage/frontend", PrefManager.frontendSyncDirCustom)
    }

    @Test
    fun `preferences stay usable while the IO dispatcher is saturated`() {
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        // kotlinx defaults Dispatchers.IO to max(64, availableProcessors) threads.
        val saturation = max(64, Runtime.getRuntime().availableProcessors())

        runBlocking {
            val blockers = (0 until saturation * 2).map {
                launch(Dispatchers.IO) {
                    started.incrementAndGet()
                    release.await()
                }
            }
            try {
                awaitSaturation(started, saturation)

                var outcome: Result<Unit>? = null
                val worker = Thread {
                    outcome = runCatching {
                        PrefManager.setFloat("prefmanager_test_saturated", 7.25f)
                        assertEquals(7.25f, PrefManager.getFloat("prefmanager_test_saturated", 0f), 0f)

                        PrefManager.frontendSyncDirCustom = "/storage/saturated"
                        assertEquals("/storage/saturated", PrefManager.frontendSyncDirCustom)
                    }
                }
                worker.start()
                worker.join(TIMEOUT_MS)

                assertFalse("preference access blocked while the IO dispatcher was saturated", worker.isAlive)
                outcome!!.getOrThrow()
            } finally {
                release.countDown()
                blockers.joinAll()
            }
        }
    }

    @Test
    fun `init loads stored values before it returns`() {
        PrefManager.frontendSyncDirCustom = "/storage/persisted"
        assertTrue("timed out persisting the write", PrefManager.awaitPendingWrites(TIMEOUT_MS))

        // Drop the cached state so init has to read the committed value back from the store.
        setPrefManagerField("snapshot", emptyPreferences())
        setPrefManagerField("load", null)

        PrefManager.init(context)

        assertEquals("/storage/persisted", PrefManager.frontendSyncDirCustom)
    }

    @Test
    fun `access before init fails instead of reporting defaults`() {
        setPrefManagerField("dataStore", null)
        setPrefManagerField("load", null)
        setPrefManagerField("snapshot", emptyPreferences())

        assertThrows(IllegalStateException::class.java) { PrefManager.frontendSyncDirCustom }
        assertThrows(IllegalStateException::class.java) { PrefManager.frontendSyncDirCustom = "/storage/too-early" }

        PrefManager.init(context)
        assertEquals(storedSyncDir, PrefManager.frontendSyncDirCustom)
    }

    private suspend fun awaitSaturation(started: AtomicInteger, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (started.get() < target && System.nanoTime() < deadline) {
            delay(10)
        }
        assertTrue("the IO dispatcher never filled up: ${started.get()} of $target", started.get() >= target)
    }

    private fun setPrefManagerField(name: String, value: Any?) {
        PrefManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(PrefManager, value)
    }

    private companion object {
        /** Bounds every wait so a regression fails the test instead of hanging the suite. */
        const val TIMEOUT_MS = 60_000L
    }
}
