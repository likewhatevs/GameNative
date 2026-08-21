package app.gamenative.service

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object DownloadService {
    @Volatile private var lastUpdateTime: Long = 0
    @Volatile private var downloadDirectoryApps: MutableList<String>? = null
    var baseDataDirPath: String = ""
        private set(value) {
            field = value
        }
    var baseCacheDirPath: String = ""
        private set(value) {
            field = value
        }
    // Base path to the app-specific external storage directory (Android/data/<package>)
    var baseExternalAppDirPath: String = ""
        private set(value) {
            field = value
        }

    // all mounted non-primary external volumes (SD cards, USB), discovered at init
    @Volatile
    var externalVolumePaths: List<String> = emptyList()
        private set

    fun populateDownloadService(context: Context) {
        baseDataDirPath = context.dataDir.path
        baseCacheDirPath = context.cacheDir.path
        // Prefer the parent of external files dir (Android/data/<package>) so we can create siblings of /files
        val extFiles = context.getExternalFilesDir(null)
        baseExternalAppDirPath = extFiles?.parentFile?.path ?: ""

        // the base dirs feed SteamService.allInstallPaths
        SteamService.invalidateInstallPathCaches()
    }

    /**
     * Discovers the mounted non-primary volumes (SD cards, USB) that can hold installs.
     *
     * Split out of [populateDownloadService], which stays on the main thread so the three base
     * dir paths are published before anything can read them. This half has no such constraint
     * and is the expensive half: [StorageUtils.getAllExternalFilesDirs] creates
     * Android/data/<pkg>/files on every removable volume, which on an SD card is a chain of
     * FUSE directory creations, and each volume then costs two StorageManager binder calls.
     *
     * The only readers are [SteamService.allInstallPaths] — recomputed on demand and dropped by
     * the [SteamService.invalidateInstallPathCaches] call below — plus ContainerStorageManager
     * and CustomGameScanner, neither of which runs before there is an Activity and a login. So
     * the window where this list is still empty cannot leave a stale resolution behind.
     */
    suspend fun discoverExternalVolumes(context: Context) = withContext(Dispatchers.IO) {
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val appFilesDirs = StorageUtils.getAllExternalFilesDirs(context)
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .filter { sm?.getStorageVolume(it)?.isPrimary != true }
        // both layouts per volume: legacy Android/data (existing installs) + public root (new installs)
        externalVolumePaths = appFilesDirs
            .flatMap { dir -> listOfNotNull(dir.absolutePath, StorageUtils.publicInstallRoot(dir)?.absolutePath) }
            .distinct()

        SteamService.invalidateInstallPathCaches()
    }

    // Android/data paths pay a ~1000x FUSE metadata penalty (MediaProvider disables kernel
    // caching there); repoint the install pref at the public root so new installs avoid it.
    //
    // Deliberately not part of [populateDownloadService]: this creates a directory and a
    // .nomedia file on the install volume, and on an SD card those are FUSE writes, while
    // populate runs on the main thread during Application.onCreate. Running it off that
    // thread means a reader can now observe the pre-migration path; that is safe because
    // the setter invalidates the resolved-path caches when its write commits, so anything
    // that resolved against the old root is recomputed rather than left stale.
    suspend fun migrateExternalStoragePath() = withContext(Dispatchers.IO) {
        val pref = PrefManager.externalStoragePath
        if (pref.isBlank() || !pref.contains("/Android/data/")) return@withContext
        val public = StorageUtils.publicInstallRoot(File(pref)) ?: return@withContext
        if (StorageUtils.ensureInstallRoot(public)) {
            // Volume discovery and directory creation can be slow enough for the user to choose
            // another target meanwhile. Never overwrite a preference that changed in flight.
            if (PrefManager.compareAndSetExternalStoragePath(pref, public.absolutePath)) {
                Timber.i("Migrating external install root from $pref to ${public.absolutePath}")
            } else {
                Timber.i("External install root changed while migration was running; leaving it unchanged")
            }
        }
    }

    @Synchronized
    fun invalidateCache() {
        lastUpdateTime = 0
        // callers invalidate here after installing/uninstalling/moving a game, which is
        // exactly when a cached per-app directory resolution can have gone stale
        SteamService.invalidateInstallPathCaches()
    }

    @Synchronized
    fun getDownloadDirectoryApps (): MutableList<String> {
        // What apps have folders in the download area?
        // Isn't checking for "complete" marker - incomplete is accepted

        // Only update if cache is over N milliseconds old
        val time = System.currentTimeMillis()
        if (lastUpdateTime < (time - 5 * 1000) || lastUpdateTime > time) {
            lastUpdateTime = time

            // scan all install paths, deduplicate across volumes
            val dirs = mutableSetOf<String>()
            for (installPath in SteamService.allInstallPaths) {
                dirs += getSubdirectories(installPath)
            }

            downloadDirectoryApps = dirs.toMutableList()
        }

        return downloadDirectoryApps ?: mutableListOf()
    }

    private fun getSubdirectories (path: String): MutableList<String> {
        // Names of immediate subdirectories
        val subDir = File(path).list() { dir, name -> File(dir, name).isDirectory}
        if (subDir == null) {
            return emptyList<String>().toMutableList()
        }
        return subDir.toMutableList()
    }

    fun getSizeFromStoreDisplay (appId: Int, branch: String = "public"): String {
        val depots = SteamService.getDownloadableDepots(appId)
        val installBytes = depots.values.sumOf { (it.manifests[branch] ?: it.manifests["public"])?.size ?: 0L }
        return StorageUtils.formatBinarySize(installBytes)
    }

    suspend fun getSizeOnDiskDisplay (appId: Int, setResult: (String) -> Unit) {
        // Outputs "3.76GiB" etc to the result lambda without locking up the main thread
        withContext(Dispatchers.IO) {
            // Do it async
            if (SteamService.isAppInstalled(appId)) {
                val appSizeText = StorageUtils.formatBinarySize(
                    StorageUtils.getFolderSize(SteamService.getAppDirPath(appId))
                )

                Timber.d("Finding $appId size on disk $appSizeText")
                setResult(appSizeText)
            }
        }
    }
}
