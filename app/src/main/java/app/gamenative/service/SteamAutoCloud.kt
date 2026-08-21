package app.gamenative.service

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.withTransaction
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.FileChangeLists
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamFileHashCache
import app.gamenative.data.UFS
import app.gamenative.data.UserFileInfo
import app.gamenative.data.UserFilesDownloadResult
import app.gamenative.data.UserFilesUploadResult
import app.gamenative.db.dao.SteamFileHashCacheDao
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.service.SteamService.Companion.FileChanges
import app.gamenative.service.SteamService.Companion.getAppDirPath
import app.gamenative.utils.CURRENT_UFS_PARSE_VERSION
import app.gamenative.utils.Net
import app.gamenative.utils.SteamSaveSweep
import app.gamenative.utils.SteamUtils
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.Enums.ECloudStoragePersistState
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicLong

/**
 * [Steam Auto Cloud](https://partner.steamgames.com/doc/features/cloud#steam_auto-cloud)
 */
object SteamAutoCloud {

    private const val MAX_USER_FILE_RETRIES = 3

    /**
     * Empty reads tolerated in a row before a stream counts as stuck.
     *
     * A blocking read only returns 0 for a zero-length request, so any empty read already means the
     * stream is misbehaving; the allowance is there for one that returns a short burst of them
     * before its data arrives.
     */
    internal const val MAX_CONSECUTIVE_EMPTY_READS = 64

    /**
     * Steamworks documents 100 MB as the maximum size of a single Steam Cloud file. Staying under
     * it also keeps the file size within the Int that beginFileUpload takes, which would otherwise
     * wrap for a file above 2 GiB.
     */
    internal const val MAX_CLOUD_FILE_SIZE_BYTES: Long = 100L * 1024 * 1024

    /**
     * Normalizes the different spellings Steam and local scans use for the same cloud key.
     * In particular, placeholders may be followed by no slash, separators may be Windows-style,
     * and Steam Cloud paths are case-insensitive.
     */
    internal fun canonicalCloudKey(path: String): String {
        val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
        val placeholder = Regex("^%[^%]+%").find(normalized)?.value

        return if (placeholder != null) {
            val suffix = normalized.removePrefix(placeholder).trim('/')
            buildString {
                append(placeholder.lowercase(Locale.ROOT))
                if (suffix.isNotEmpty()) {
                    append('/')
                    append(suffix.lowercase(Locale.ROOT))
                }
            }
        } else {
            normalized.trim('/').lowercase(Locale.ROOT)
        }
    }

    private fun AppFileInfo.isTombstoned(): Boolean =
        persistState == ECloudStoragePersistState.k_ECloudStoragePersistStateForgotten ||
            persistState == ECloudStoragePersistState.k_ECloudStoragePersistStateDeleted

    /**
     * Checks a would-be cloud file set against the per-app quota the app declares in its ufs
     * appinfo section, returning a description of the violation or null if the set fits.
     *
     * The file count is checked first: a save directory holds far more files than bytes relative
     * to its limits, so an over-collecting sweep runs out of files long before it runs out of
     * space. A limit of zero means the app declares none.
     *
     * This is the app's declared limit, not the account's current usage, so it does not account
     * for files another device left in the cloud.
     */
    internal fun checkQuota(ufs: UFS, fileCount: Int, totalBytes: Long): String? {
        if (ufs.maxNumFiles > 0 && fileCount > ufs.maxNumFiles) {
            return "$fileCount file(s) exceeds the app's limit of ${ufs.maxNumFiles}"
        }
        if (ufs.quota > 0 && totalBytes > ufs.quota) {
            return "$totalBytes byte(s) exceeds the app's quota of ${ufs.quota}"
        }
        return null
    }

    internal data class HashLookupResult(
        val sha: ByteArray,
        val wasCacheHit: Boolean,
    )

    /** Computes SHA-1 hash by streaming the file in chunks to avoid OOM on large files. */
    private fun streamingShaHash(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(8192)
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                digest.update(buf, 0, bytesRead)
            }
        }
        return digest.digest()
    }

    private fun findPlaceholderWithin(aString: String): Sequence<MatchResult> =
        Regex("%\\w+%").findAll(aString)

    internal inline fun InputStream.copyTo(
        out: OutputStream,
        bufferSize: Int = 8 * 1024,
        progress: (chunkBytes: Long, totalBytes: Long) -> Unit,
    ): Long {
        val buf = ByteArray(bufferSize)
        var bytesRead: Int
        var total = 0L
        var emptyReads = 0
        while (read(buf).also { bytesRead = it } >= 0) {
            if (bytesRead == 0) {
                // Skipping the empty read keeps a zero-length chunk out of the output and out of
                // the progress callback, but a stream that only ever returns 0 would spin here
                // forever: the loop never suspends, so the caller's withTimeout cannot cancel it.
                if (++emptyReads > MAX_CONSECUTIVE_EMPTY_READS) {
                    throw IOException("Stream returned $emptyReads empty reads in a row after $total byte(s)")
                }
                continue
            }
            emptyReads = 0
            out.write(buf, 0, bytesRead)
            total += bytesRead
            progress(bytesRead.toLong(), total)
        }
        return total
    }

    internal suspend fun getCachedShaOrHash(
        appId: Int,
        path: Path,
        hashCacheDao: SteamFileHashCacheDao,
    ): HashLookupResult {
        val absPath = path.pathString
        val sizeBytes = Files.size(path)
        val mtimeMillis = Files.getLastModifiedTime(path).toMillis()
        val cached = hashCacheDao.getByAppIdAndPath(appId, absPath)

        if (cached != null && cached.sizeBytes == sizeBytes && cached.mtimeMillis == mtimeMillis) {
            return HashLookupResult(
                sha = cached.sha,
                wasCacheHit = true,
            )
        }

        val sha = streamingShaHash(path)
        hashCacheDao.insert(
            SteamFileHashCache(
                appId = appId,
                absPath = absPath,
                sizeBytes = sizeBytes,
                mtimeMillis = mtimeMillis,
                sha = sha,
            ),
        )
        return HashLookupResult(
            sha = sha,
            wasCacheHit = false,
        )
    }

    /**
     * Reads the cached file list, reporting an unreadably large row as no cache at all.
     *
     * The whole list lives in one column, so a big enough list exceeds the cursor window and every
     * read of that row throws. Treating it as absent costs a full resync instead of wedging the app.
     */
    internal suspend fun getCachedFileList(steamInstance: SteamService, appId: Int): FileChangeLists? = try {
        steamInstance.fileChangeListsDao.getByAppId(appId)
    } catch (e: SQLiteBlobTooBigException) {
        Timber.e(e, "Cached file list of $appId is too large to read, treating it as absent")
        null
    }

    /** Preference holding the id of an upload batch this device opened and has not closed yet. */
    internal fun openUploadBatchKey(appId: Int): String = "cloud_open_upload_batch_$appId"

    /**
     * Closes an upload batch an earlier session left open.
     *
     * Steam reports the app as having an upload in progress until its batch is completed, so a
     * session that died mid-upload keeps the next one from syncing until the batch is closed.
     */
    internal suspend fun closeAbandonedUploadBatch(steamCloud: SteamCloud, appId: Int) {
        val batchId = PrefManager.getLong(openUploadBatchKey(appId), 0L)

        if (batchId == 0L) {
            return
        }

        Timber.w("Closing upload batch $batchId of $appId left open by an earlier session")

        try {
            steamCloud.completeAppUploadBatch(
                appId = appId,
                batchId = batchId,
                batchEResult = EResult.Fail,
            ).await()

            PrefManager.setLongBlocking(openUploadBatchKey(appId), 0L)
        } catch (e: Exception) {
            Timber.w(e, "Could not close upload batch $batchId of $appId, leaving it recorded")
        }
    }

    fun syncUserFiles(
        appInfo: SteamApp,
        clientId: Long,
        steamInstance: SteamService,
        steamCloud: SteamCloud,
        preferredSave: SaveLocation = SaveLocation.None,
        parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        prefixToPath: (String) -> String,
        overrideLocalChangeNumber: Long? = null,
        onProgress: ((message: String, progress: Float) -> Unit)? = null,
    ): Deferred<PostSyncInfo?> = parentScope.async {
        val postSyncInfo: PostSyncInfo?

        Timber.i("Retrieving save files of ${appInfo.name}")

        // When a rootoverride remaps a root (e.g. GameInstall → WinAppDataRoaming), the cloud
        // still stores files under the original root placeholder (uploadRoot). Map those
        // placeholders to the local root so downloads land in the right directory.
        val uploadRootRemap: Map<String, String> = appInfo.ufs.saveFilePatterns
            .filter { it.uploadRoot != it.root }
            .associate { "%${it.uploadRoot.name}%" to it.root.name }

        // Full-prefix remap for patterns where addPath shifts the local subfolder relative to
        // the cloud path. E.g. cloud "%GameInstall%saves" must land at "<WinAppDataRoaming>/MyGame/saves",
        // not "<WinAppDataRoaming>/saves" — root-only replacement can't express this.
        val cloudPrefixToLocalPath: Map<String, String> = appInfo.ufs.saveFilePatterns
            .filter { it.uploadPath != it.path }
            .flatMap { p ->
                val localPath = Paths.get(prefixToPath(p.root.name), p.substitutedPath).pathString
                val cloudPath = p.uploadPath
                    .replace("\\", "/")
                    .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
                    .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())
                    .trim('/')
                val cloudRoot = "%${p.uploadRoot.name}%"
                val cloudPrefixes = if (cloudPath.isBlank()) {
                    listOf(cloudRoot)
                } else {
                    listOf(
                        "$cloudRoot$cloudPath",
                        "$cloudRoot/$cloudPath",
                    )
                }
                cloudPrefixes.map { cloudKey ->
                    cloudKey to localPath
                }
            }
            .toMap()

        val getPathTypePairs: (AppFileChangeList) -> List<Pair<String, String>> = { fileList ->
            fileList.pathPrefixes
                .map {
                    var matchResults = findPlaceholderWithin(it).map { it.value }.toList()
                    val bare = if (it.startsWith("ROOT_MOD")) listOf("ROOT_MOD") else emptyList()

                    Timber.i("Mapping prefix $it and found $matchResults")

                    if (matchResults.isEmpty()) {
                        matchResults = List(1) { PathType.DEFAULT.name }
                    }

                    matchResults + bare
                }
                .flatten()
                .distinct()
                .map { placeholder ->
                    val localRootName = uploadRootRemap[placeholder] ?: placeholder
                    placeholder to prefixToPath(localRootName)
                }
        }

        val convertPrefixes: (AppFileChangeList) -> List<String> = { fileList ->
            val pathTypePairs = getPathTypePairs(fileList)

            fileList.pathPrefixes.map { prefix ->
                // Full-prefix match first: handles addPath case where the cloud path omits a
                // subfolder that the local path includes. Root-only replacement can't express this.
                // Cloud prefixes sometimes include a trailing slash (e.g. "%WinAppDataLocalLow%76561198035529760/save1/")
                // but the map keys are built without one — trim before lookup so they match.
                val cloudPrefix = prefix.trimEnd('/')
                cloudPrefixToLocalPath.entries
                    .filter { (cloudKey, _) -> cloudPrefix == cloudKey || cloudPrefix.startsWith("$cloudKey/") }
                    .maxByOrNull { (cloudKey, _) -> cloudKey.length }
                    ?.let { (cloudKey, localPath) ->
                        Paths.get(localPath, cloudPrefix.removePrefix(cloudKey).trimStart('/')).pathString
                    }
                    ?: run {
                        var modified = prefix

                        val prefixContainsNoPlaceholder = findPlaceholderWithin(prefix).none()

                        if (prefixContainsNoPlaceholder) {
                            modified = Paths.get(PathType.DEFAULT.name, prefix).pathString
                        }

                        pathTypePairs.forEach {
                            modified = modified.replace(it.first, it.second)
                        }

                        // if the prefix has not been modified then there were no placeholders in it
                        // so we need to set it to point to the default path
                        if (modified == prefix) {
                            modified = Paths.get(prefixToPath(PathType.DEFAULT.name), modified).toString()
                        }

                        modified
                    }
            }
        }

        val getFilePrefix: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            if (file.hasPathPrefixIndex && file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(fileList.pathPrefixes[file.pathPrefixIndex]).pathString
            } else {
                ""
            }
        }

        val getFilePrefixPath: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            Paths.get(getFilePrefix(file, fileList), file.filename).pathString
        }

        val hashCacheHits = AtomicInteger(0)
        val hashCacheMisses = AtomicInteger(0)
        val hashCacheDao = steamInstance.db.steamFileHashCacheDao()

        val getFullFilePath: (AppFileInfo, AppFileChangeList) -> Path = getFullFilePath@{ file, fileList ->
            val gameInstallPrefix = "%${PathType.GameInstall.name}%"
            if (file.filename.startsWith(gameInstallPrefix)) {
                // Steam API sometimes returns prefix="" and filename="%GameInstall%save0.dat" instead of splitting correctly.
                // Strip the embedded prefix (and any leading slash) to get the bare filename.
                val stripped = file.filename.removePrefix(gameInstallPrefix).trimStart('/')
                // If a Windows rootoverride remaps GameInstall → another directory (e.g.
                // Danganronpa 2: WinMyDocuments/My Games/Danganronpa2/), download there instead
                // of the raw game-install folder so the game can find its saves.
                val remapped = cloudPrefixToLocalPath[gameInstallPrefix]
                return@getFullFilePath if (remapped != null) {
                    Paths.get(remapped, stripped)
                } else {
                    Paths.get(prefixToPath(PathType.GameInstall.name), stripped)
                }
            }

            val convertedPrefixes = convertPrefixes(fileList)

            if (file.hasPathPrefixIndex && file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(convertedPrefixes[file.pathPrefixIndex], file.filename)
            } else {
                // if the file does not reference any prefix then we need to set it to the default path
                Paths.get(prefixToPath(PathType.DEFAULT.name), file.filename)
            }
        }

        val getFilesDiff: (List<UserFileInfo>, List<UserFileInfo>) -> Pair<Boolean, FileChanges> = { currentFiles, oldFiles ->
            val overlappingFiles = currentFiles.filter { currentFile ->
                oldFiles.any { currentFile.prefixPath == it.prefixPath }
            }

            val newFiles = currentFiles.filter { currentFile ->
                !oldFiles.any { currentFile.prefixPath == it.prefixPath }
            }

            val deletedFiles = oldFiles.filter { oldFile ->
                !currentFiles.any { oldFile.prefixPath == it.prefixPath }
            }

            val modifiedFiles = overlappingFiles.filter { file ->
                oldFiles.first {
                    it.prefixPath == file.prefixPath
                }.let {
                    Timber.i("Comparing SHA of ${it.prefixPath} and ${file.prefixPath}")
                    Timber.i("[${it.sha.joinToString(", ")}]\n[${file.sha.joinToString(", ")}]")

                    !it.sha.contentEquals(file.sha)
                }
            }

            val changesExist = newFiles.isNotEmpty() || deletedFiles.isNotEmpty() || modifiedFiles.isNotEmpty()

            changesExist to FileChanges(deletedFiles, modifiedFiles, newFiles)
        }

        val getLocalUserFilesAsPrefixMap: suspend () -> Map<String, List<UserFileInfo>> = {
            val savePatterns = appInfo.ufs.saveFilePatterns.filter { userFile -> userFile.root.isWindows }

            val result = mutableMapOf<String, MutableList<UserFileInfo>>()

            if (savePatterns.isNotEmpty()) {
                savePatterns.forEach { userFile ->
                    if (userFile.root == PathType.SteamUserData) {
                        // skip handling, use the logic below to scan SteamUserData
                        return@forEach
                    }

                    val basePath = Paths.get(prefixToPath(userFile.root.toString()), userFile.substitutedPath)

                    // `recursive` is a boolean in the ufs schema and is absent far more often than
                    // not; descending anyway sweeps up whatever the engine keeps in subdirectories
                    // of the save directory.
                    val recursive = userFile.recursive != 0

                    Timber.i(
                        "Looking for saves in $basePath with pattern ${userFile.pattern} " +
                            "(prefix ${userFile.prefix}, recursive=$recursive)",
                    )

                    val filePaths = SteamSaveSweep.findSaveFiles(
                        basePath = basePath,
                        pattern = userFile.pattern,
                        recursive = recursive,
                    )
                    val files = buildList {
                        for (path in filePaths) {
                            val hashLookup = getCachedShaOrHash(
                                appId = appInfo.id,
                                path = path,
                                hashCacheDao = hashCacheDao,
                            )
                            if (hashLookup.wasCacheHit) {
                                hashCacheHits.incrementAndGet()
                            } else {
                                hashCacheMisses.incrementAndGet()
                            }
                            val sha = hashLookup.sha

                            Timber.i("Found ${path.pathString}\n\tin ${userFile.prefix}\n\twith sha [${sha.joinToString(", ")}]")

                            val relativePath = basePath.relativize(path).pathString

                            add(UserFileInfo(
                                root = userFile.root,
                                path = userFile.substitutedPath,
                                filename = relativePath,
                                timestamp = Files.getLastModifiedTime(path).toMillis(),
                                sha = sha,
                                cloudRoot = userFile.uploadRoot,
                                cloudPath = userFile.uploadPath
                            ))
                        }
                    }

                    Timber.i("Found ${files.size} file(s) in $basePath for pattern ${userFile.pattern}")

                    val prefixKey = Paths.get(userFile.prefix).pathString
                    result.getOrPut(prefixKey) { mutableListOf() }.addAll(files)
                }
            }

            // Scan SteamUserData root recursively (depth 5)
            val rootType = PathType.SteamUserData
            val basePath = Paths.get(prefixToPath(rootType.toString()))

            Timber.i("Scanning $basePath recursively (depth 5) under ${rootType.name}")

            val steamUserDataPaths = SteamSaveSweep.findSaveFiles(
                basePath = basePath,
                pattern = "*",
                recursive = true,
            )
            val files = buildList {
                for (path in steamUserDataPaths) {
                    val hashLookup = getCachedShaOrHash(
                        appId = appInfo.id,
                        path = path,
                        hashCacheDao = hashCacheDao,
                    )
                    if (hashLookup.wasCacheHit) {
                        hashCacheHits.incrementAndGet()
                    } else {
                        hashCacheMisses.incrementAndGet()
                    }
                    val sha = hashLookup.sha

                    val relativePath = basePath.relativize(path).pathString

                    Timber.i("Found ${path.pathString}\n\tin %${rootType.name}%\n\twith sha [${sha.joinToString(", ")}]")

                    // Store relative path in filename; empty path component
                    add(UserFileInfo(
                        root = rootType,
                        path = "",
                        filename = relativePath,
                        timestamp = Files.getLastModifiedTime(path).toMillis(),
                        sha = sha,
                        cloudRoot = rootType,
                        cloudPath = ""
                    ))
                }
            }

            Timber.i("Found ${files.size} file(s) in $basePath")

            mapOf(Paths.get("%${rootType.name}%").pathString to files)

            if (files.isNotEmpty()) {
                val prefixKey = "%${rootType.name}%"
                result.getOrPut(prefixKey) { mutableListOf() }.addAll(files)
            }

            Timber.i(
                "Local save hash cache stats for ${appInfo.id} (${appInfo.name}): " +
                    "hits=${hashCacheHits.get()}, misses=${hashCacheMisses.get()}, files=${hashCacheHits.get() + hashCacheMisses.get()}",
            )

            result
        }

        val fileChangeListToUserFiles: (AppFileChangeList) -> List<UserFileInfo> = { appFileListChange ->
            val pathTypePairs = getPathTypePairs(appFileListChange)

            appFileListChange.files.filterNot { it.isTombstoned() }.map {
                UserFileInfo(
                    root = if (it.hasPathPrefixIndex && it.pathPrefixIndex < pathTypePairs.size) {
                        PathType.from(pathTypePairs[it.pathPrefixIndex].first)
                    } else {
                        PathType.DEFAULT
                    },
                    path = if (it.hasPathPrefixIndex && it.pathPrefixIndex < pathTypePairs.size) {
                        appFileListChange.pathPrefixes[it.pathPrefixIndex]
                    } else {
                        ""
                    },
                    filename = it.filename,
                    timestamp = it.timestamp.time,
                    sha = it.shaFile,
                )
            }
        }

        val buildUrl: (Boolean, String, String) -> String = { useHttps, urlHost, urlPath ->
            val scheme = if (useHttps) "https://" else "http://"
            "$scheme${urlHost}$urlPath"
        }

        val downloadFiles: (List<AppFileInfo>, AppFileChangeList, CoroutineScope) -> Deferred<UserFilesDownloadResult> = { filesToDownload, fileList, parentScope ->
            parentScope.async {
                val filesDownloaded = AtomicInteger(0)
                val bytesDownloaded = AtomicLong(0L)
                val totalFiles = filesToDownload.size
                val parallelism = PrefManager.downloadSpeed.coerceAtLeast(1)
                // A new client (and its Dispatcher thread pool) is created intentionally per sync,
                // since cloud saves are downloaded at most once per game launch.
                val downloadHttpClient = Net.httpForParallelDownloads(parallelism)
                val semaphore = Semaphore(parallelism)
                val completedFiles = AtomicInteger(0)
                val totalRawBytes = filesToDownload.sumOf { it.rawFileSize.toLong() }
                // downloadedRawBytes tracks bytes as they stream in (per-chunk) for live progress.
                // bytesDownloaded accumulates rawFileSize per completed file for final accounting.
                val downloadedRawBytes = AtomicLong(0L)
                val lastReportedPercent = AtomicInteger(-1)
                val progressMessage: (Int) -> String = { finishedFiles ->
                    steamInstance.getString(
                        R.string.steam_cloud_sync_downloading_save_files,
                        finishedFiles,
                        totalFiles,
                    )
                }

                try {
                    coroutineScope {
                        filesToDownload.map { file ->
                            async {
                                semaphore.withPermit {
                                    val result = downloadSingleFile(
                                        appInfo = appInfo,
                                        steamCloud = steamCloud,
                                        hashCacheDao = hashCacheDao,
                                        file = file,
                                        fileList = fileList,
                                        getFilePrefixPath = getFilePrefixPath,
                                        getFullFilePath = getFullFilePath,
                                        buildUrl = buildUrl,
                                        httpClient = downloadHttpClient,
                                        totalRawBytes = totalRawBytes,
                                        downloadedRawBytes = downloadedRawBytes,
                                        lastReportedPercent = lastReportedPercent,
                                        completedFiles = completedFiles,
                                        totalFiles = totalFiles,
                                        progressMessage = progressMessage,
                                        onProgress = onProgress,
                                    )
                                    if (result != null) {
                                        filesDownloaded.addAndGet(result.filesDownloaded)
                                        bytesDownloaded.addAndGet(result.bytesDownloaded)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } finally {
                    runCatching {
                        downloadHttpClient.dispatcher.executorService.shutdown()
                    }
                    runCatching {
                        downloadHttpClient.connectionPool.evictAll()
                    }
                }

                if (totalFiles > 0 && filesDownloaded.get() == totalFiles) {
                    onProgress?.invoke("Download complete", 1.0f)
                }

                UserFilesDownloadResult(filesDownloaded.get(), bytesDownloaded.get())
            }
        }

        val uploadFiles: (FileChanges, CoroutineScope) -> Deferred<UserFilesUploadResult> = { fileChanges, parentScope ->
            parentScope.async {
                var filesUploaded = 0
                var bytesUploaded = 0L

                // Overlapping UFS rules can leave multiple cached spellings of the same cloud
                // path. Steam treats those keys case-insensitively, so issue one delete while
                // preserving the first original spelling as the RPC representative.
                val filesToDelete = fileChanges.filesDeleted
                    .map { it.prefixPath }
                    .distinctBy { canonicalCloudKey(it) }

                val filesToUpload = fileChanges.filesCreated
                    .union(fileChanges.filesModified)
                    .map { it.prefixPath to it }
                    // Filter out entries whose files no longer exist at upload time
                    .filter { Files.exists(it.second.getAbsPath(prefixToPath)) }

                val totalFiles = filesToUpload.size

                Timber.i(
                    "Beginning app upload batch with ${filesToDelete.size} file(s) to delete " +
                        "and ${filesToUpload.size} file(s) to upload",
                )

                val batchKey = openUploadBatchKey(appInfo.id)
                val uploadBatchResponse = withContext(NonCancellable) {
                    steamCloud.beginAppUploadBatch(
                        appId = appInfo.id,
                        machineName = SteamUtils.getMachineName(steamInstance),
                        clientId = clientId,
                        filesToDelete = filesToDelete,
                        filesToUpload = filesToUpload.map { it.first },
                        appBuildId = appInfo.branches[SteamService.getInstalledApp(appInfo.id)?.branch ?: "public"]?.buildId ?: 0,
                    ).await().also { openedBatch ->
                        if (openedBatch.batchID != 0L) {
                            try {
                                // Record before cancellation can resume, so this or a later session
                                // can always close a batch Steam has already opened.
                                PrefManager.setLongBlocking(batchKey, openedBatch.batchID)
                            } catch (e: Exception) {
                                steamCloud.completeAppUploadBatch(
                                    appId = appInfo.id,
                                    batchId = openedBatch.batchID,
                                    batchEResult = EResult.Fail,
                                ).await()
                                throw e
                            }
                        }
                    }
                }

                // AppUploadBatchResponse does not expose the response EResult, so an unset batch id
                // is the only signal we get that Steam refused to open the batch. Uploading into it
                // would be a no-op, and there is no batch to complete afterwards.
                if (uploadBatchResponse.batchID == 0L) {
                    currentCoroutineContext().ensureActive()
                    Timber.e("Steam did not open an upload batch for ${appInfo.id}, aborting upload")

                    return@async UserFilesUploadResult(false, uploadBatchResponse.appChangeNumber, 0, 0L)
                }

                var uploadBatchSuccess = true

                try {
                    // This checkpoint is deliberately inside the finally-protected region. A
                    // cancellation that arrived while the non-cancellable open was in flight must
                    // close the now-known batch before it propagates.
                    currentCoroutineContext().ensureActive()

                    // Listing a path in filesToDelete only declares the batch intent. Steam still
                    // requires each deletion to be issued inside the opened batch; otherwise the
                    // batch can complete successfully while the remote file remains untouched.
                    filesToDelete.forEach { path ->
                        val deleted = steamCloud.deleteFile(appInfo.id, path, uploadBatchResponse.batchID).await()
                        if (!deleted) {
                            Timber.w("Steam refused to delete $path of ${appInfo.id}")
                            uploadBatchSuccess = false
                        }
                    }

                    filesToUpload.map { it.second }.forEachIndexed { index, file ->
                        val absFilePath = file.getAbsPath(prefixToPath)

                        val fileSizeBytes = try {
                            Files.size(absFilePath)
                        } catch (e: Exception) {
                            Timber.w("Skipping upload of ${file.prefixPath}: ${e.javaClass.simpleName}: ${e.message}")
                            uploadBatchSuccess = false
                            return@forEachIndexed
                        }

                        if (fileSizeBytes > MAX_CLOUD_FILE_SIZE_BYTES) {
                            Timber.w(
                                "Skipping upload of ${file.prefixPath}: $fileSizeBytes byte(s) is over " +
                                    "the $MAX_CLOUD_FILE_SIZE_BYTES byte Steam Cloud per-file limit",
                            )
                            uploadBatchSuccess = false
                            return@forEachIndexed
                        }

                        val fileSize = fileSizeBytes.toInt()

                        Timber.i("Beginning upload of ${file.prefixPath} whose timestamp is ${file.timestamp}")

                        // Report start of upload
                        onProgress?.invoke("Uploading ${file.filename}", 0f)

                        val uploadInfo = steamCloud.beginFileUpload(
                            appId = appInfo.id,
                            filename = if (appInfo.ufs.saveFilePatterns.isEmpty()) {
                                // For SteamUserData files, use just the filename without folder prefix
                                if (file.root == PathType.SteamUserData) {
                                    file.filename
                                } else {
                                    file.path + file.filename
                                }
                            } else {
                                // For SteamUserData files, use just the filename to avoid folder prefix
                                if (file.root == PathType.SteamUserData) {
                                    file.filename
                                } else {
                                    file.prefixPath
                                }
                            },
                            fileSize = fileSize,
                            rawFileSize = fileSize,
                            fileSha = file.sha,
                            // timestamp = prootTimestampToDate(file.timestamp),
                            timestamp = Date(file.timestamp),
                            uploadBatchId = uploadBatchResponse.batchID,
                        ).await()

                        var uploadFileSuccess = true
                        var bytesUploadedForFile = 0L
                        var lastReportedProgress = -1f
                        val progressThreshold = 0.01f // Update every 1% change

                        // A file with content and no block requests transfers nothing. Without this the
                        // loop below is a no-op and the file still counts as uploaded.
                        if (fileSize > 0 && uploadInfo.blockRequests.isEmpty()) {
                            Timber.w("Steam returned no block requests for ${file.prefixPath}")

                            uploadFileSuccess = false
                            uploadBatchSuccess = false
                        }

                        RandomAccessFile(absFilePath.pathString, "r").use { fs ->
                            uploadInfo.blockRequests.forEach { blockRequest ->
                                val httpUrl = buildUrl(
                                    blockRequest.useHttps,
                                    blockRequest.urlHost,
                                    blockRequest.urlPath,
                                )

                                Timber.i("Uploading to $httpUrl")
                                Timber.i(
                                    "Block Request:" +
                                        "\n\tblockOffset: ${blockRequest.blockOffset}" +
                                        "\n\tblockLength: ${blockRequest.blockLength}" +
                                        "\n\trequestHeaders:\n\t\t${
                                            blockRequest.requestHeaders.joinToString("\n\t\t") { "${it.name}: ${it.value}" }
                                        }" +
                                        "\n\texplicitBodyData: [${
                                            blockRequest.explicitBodyData.joinToString(
                                                ", ",
                                            )
                                        }]" +
                                        "\n\tmayParallelize: ${blockRequest.mayParallelize}",
                                )

                                val byteArray = ByteArray(blockRequest.blockLength)

                                fs.seek(blockRequest.blockOffset)

                                val bytesRead = fs.read(byteArray, 0, blockRequest.blockLength)

                                Timber.i("Read $bytesRead byte(s) for block")

                                val mediaType = if (blockRequest.requestHeaders.any { it.name.equals("Content-Type", ignoreCase = true) }) {
                                    blockRequest.requestHeaders.first { it.name.equals("Content-Type", ignoreCase = true) }.value.toMediaTypeOrNull()
                                } else {
                                    "application/octet-stream".toMediaTypeOrNull()
                                }

                                val requestBody = byteArray.toRequestBody(mediaType)

                                // val requestBody = byteArray.toRequestBody()

                                val headers = Headers.headersOf(
                                    *blockRequest.requestHeaders
                                        .map { listOf(it.name, it.value) }
                                        .flatten()
                                        .toTypedArray(),
                                )

                                val request = Request.Builder()
                                    .url(httpUrl)
                                    .put(requestBody)
                                    .headers(headers)
                                    .addHeader("Accept", "text/html,*/*;q=0.9")
                                    .addHeader("accept-encoding", "gzip,identity,*;q=0")
                                    .addHeader("accept-charset", "ISO-8859-1,utf-8,*;q=0.7")
                                    .addHeader("user-agent", "Valve/Steam HTTP Client 1.0")
                                    .build()

                                val httpClient = steamInstance.steamClient!!.configuration.httpClient

                                Timber.i("Sending request to ${request.url} using\n$request")

                                withTimeout(SteamService.requestTimeout) {
                                    val response = httpClient.newCall(request).execute()

                                    if (!response.isSuccessful) {
                                        Timber.w(
                                            "Failed to upload part of %s: HTTP %d %s, %s",
                                            file.prefixPath,
                                            response.code,
                                            response.message,
                                            response?.body.toString(),
                                        )

                                        uploadFileSuccess = false
                                        uploadBatchSuccess = false
                                    } else {
                                        // Update progress after successful block upload
                                        bytesUploadedForFile += blockRequest.blockLength
                                        if (fileSize > 0) {
                                            val currentProgress = (bytesUploadedForFile.toFloat() / fileSize).coerceIn(0f, 1f)
                                            // Only update if progress changed by at least 1% or we're at 100%
                                            if (currentProgress - lastReportedProgress >= progressThreshold || currentProgress >= 1f) {
                                                onProgress?.invoke("Uploading ${file.filename}", currentProgress)
                                                lastReportedProgress = currentProgress
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val commitSuccess = steamCloud.commitFileUpload(
                            transferSucceeded = uploadFileSuccess,
                            appId = appInfo.id,
                            fileSha = file.sha,
                            filename = if (appInfo.ufs.saveFilePatterns.isEmpty()) {
                                // For SteamUserData files, use just the filename without folder prefix
                                if (file.root == PathType.SteamUserData) {
                                    file.filename
                                } else {
                                    file.path + file.filename
                                }
                            } else {
                                // For SteamUserData files, use just the filename to avoid folder prefix
                                if (file.root == PathType.SteamUserData) {
                                    file.filename
                                } else {
                                    file.prefixPath
                                }
                            },
                        ).await()

                        Timber.i("File ${file.prefixPath} commit success: $commitSuccess")

                        if (!commitSuccess) {
                            uploadBatchSuccess = false
                        } else if (uploadFileSuccess) {
                            filesUploaded++
                            bytesUploaded += fileSize
                        }
                    }
                } catch (e: Exception) {
                    // The batch stays open on the server until it is completed, so mark it failed
                    // and let the finally block close it before this unwinds.
                    uploadBatchSuccess = false

                    throw e
                } finally {
                    // Cancellation must not skip the close, or the batch is left open.
                    withContext(NonCancellable) {
                        val batchEResult = if (uploadBatchSuccess) EResult.OK else EResult.Fail

                        Timber.i("Completing upload batch ${uploadBatchResponse.batchID} of ${appInfo.id} with $batchEResult")

                        steamCloud.completeAppUploadBatch(
                            appId = appInfo.id,
                            batchId = uploadBatchResponse.batchID,
                            batchEResult = batchEResult,
                        ).await()

                        PrefManager.setLongBlocking(batchKey, 0L)
                    }
                }

                if (totalFiles > 0) {
                    onProgress?.invoke("Upload complete", 1.0f)
                }

                UserFilesUploadResult(uploadBatchSuccess, uploadBatchResponse.appChangeNumber, filesUploaded, bytesUploaded)
            }
        }

        var syncResult = SyncResult.Success
        var conflictUfsVersion: Int? = null
        var remoteTimestamp = 0L
        var localTimestamp = 0L
        var uploadsRequired = false
        var uploadsCompleted = true

        // sync metrics
        var filesUploaded = 0
        var filesDownloaded = 0
        var filesDeleted = 0
        var filesManaged = 0
        var bytesUploaded = 0L
        var bytesDownloaded = 0L
        var microsecTotal = 0L
        var microsecInitCaches = 0L
        var microsecValidateState = 0L
        var microsecAcLaunch = 0L
        var microsecAcPrepUserFiles = 0L
        var microsecAcExit = 0L
        var microsecBuildSyncList = 0L
        var microsecDeleteFiles = 0L
        var microsecDownloadFiles = 0L
        var microsecUploadFiles = 0L
        var lastCloudAppChangeNumber = -1L

        microsecTotal = measureTime {
            closeAbandonedUploadBatch(steamCloud, appInfo.id)

            val localAppChangeNumber = overrideLocalChangeNumber ?: steamInstance.changeNumbersDao.getByAppId(appInfo.id)?.changeNumber ?: -1

            val cachedFileList = getCachedFileList(steamInstance, appInfo.id)
            val cacheIsAbsentOrEmpty = cachedFileList == null || cachedFileList.userFileInfo.isEmpty()
            val changeNumber = if (!cacheIsAbsentOrEmpty && localAppChangeNumber >= 0) localAppChangeNumber else 0L
            val initialFileListChange = steamCloud.getAppFileListChange(appInfo.id, changeNumber).await()
            val appFileListChange = if (initialFileListChange.isOnlyDelta) {
                // The rest of this sync reconciles the cloud set against every local file. Treating
                // a delta as that set makes every unchanged cloud file look deleted locally.
                Timber.i("Steam returned a delta for ${appInfo.id}; resolving the full cloud manifest")
                steamCloud.getAppFileListChange(appInfo.id, 0L).await().also { fullFileList ->
                    check(!fullFileList.isOnlyDelta) {
                        "Steam returned a partial cloud manifest for ${appInfo.id} after a full-manifest request"
                    }
                }
            } else {
                initialFileListChange
            }

            val cloudAppChangeNumber = appFileListChange.currentChangeNumber
            lastCloudAppChangeNumber = cloudAppChangeNumber
            val activeCloudFiles = appFileListChange.files.filterNot { it.isTombstoned() }
            val tombstonedCloudKeys = appFileListChange.files
                .filter { it.isTombstoned() }
                .map { canonicalCloudKey(getFilePrefixPath(it, appFileListChange)) }
                .toSet()

            Timber.i("AppChangeNumber: $localAppChangeNumber -> $cloudAppChangeNumber")

            appFileListChange.printFileChangeList(appInfo)

            // retrieve existing user files from local storage
            val localUserFilesMap: Map<String, List<UserFileInfo>>
            val allLocalUserFiles: List<UserFileInfo>

            microsecInitCaches = measureTime {
                localUserFilesMap = getLocalUserFilesAsPrefixMap()
                allLocalUserFiles = localUserFilesMap.map { it.value }.flatten()
            }.inWholeMicroseconds
            val syncableLocalUserFiles = allLocalUserFiles.filterNot {
                canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
            }
            val syncableCachedUserFiles = cachedFileList?.userFileInfo?.filterNot {
                canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
            }

            val effectiveLocalChangeNumber = if (cacheIsAbsentOrEmpty && allLocalUserFiles.isNotEmpty()) {
                Timber.w("Cache absent/empty but local files exist — forcing full cloud fetch (storedCn=$localAppChangeNumber)")
                -1L
            } else {
                localAppChangeNumber
            }

            val downloadUserFiles: (CoroutineScope) -> Deferred<PostSyncInfo?> = { parentScope ->
                parentScope.async {
                    Timber.i("Downloading cloud user files")

                    val remoteUserFiles = fileChangeListToUserFiles(appFileListChange)
                    val filesDiff = getFilesDiff(remoteUserFiles, syncableLocalUserFiles).second
                    microsecDeleteFiles = measureTime {
                        var totalFilesDeleted = 0

                        filesDiff.filesDeleted.forEach {
                            val deleted = Files.deleteIfExists(it.getAbsPath(prefixToPath))
                            if (deleted) totalFilesDeleted++
                        }

                        filesDeleted = totalFilesDeleted
                    }.inWholeMicroseconds

                    val allCloudFilesDownloaded: Boolean
                    microsecDownloadFiles = measureTime {
                        val downloadInfo = downloadFiles(activeCloudFiles, appFileListChange, parentScope).await()
                        filesDownloaded = downloadInfo.filesDownloaded
                        bytesDownloaded = downloadInfo.bytesDownloaded
                        allCloudFilesDownloaded = downloadInfo.filesDownloaded == activeCloudFiles.size
                    }.inWholeMicroseconds

                    val updatedLocalFiles: Map<String, List<UserFileInfo>>
                    microsecValidateState = measureTime {
                        updatedLocalFiles = getLocalUserFilesAsPrefixMap()
                        filesManaged = updatedLocalFiles.size
                    }.inWholeMicroseconds

                    // var retries = 0

                    // do {
                    //     downloadFiles(appFileListChange, parentScope).await()
                    //     updatedLocalFiles = getLocalUserFilesAsPrefixMap()
                    //     hasLocalChanges =
                    //         hasHashConflicts(updatedLocalFiles, appFileListChange)
                    // } while (hasLocalChanges && retries++ < MAX_USER_FILE_RETRIES)
                    //

                    if (!allCloudFilesDownloaded) {
                        Timber.e("Failed to download latest user files after $MAX_USER_FILE_RETRIES tries")

                        syncResult = SyncResult.DownloadFail

                        return@async PostSyncInfo(syncResult)
                    }

                    with(steamInstance) {
                        db.withTransaction {
                            fileChangeListsDao.insert(appInfo.id, updatedLocalFiles.map { it.value }.flatten())
                            changeNumbersDao.insert(appInfo.id, cloudAppChangeNumber)
                        }
                    }

                    return@async null
                }
            }

            val uploadUserFiles: (CoroutineScope) -> Deferred<Unit> = { parentScope ->
                parentScope.async {
                    Timber.i("Uploading local user files")

                    val fileChanges = getCachedFileList(steamInstance, appInfo.id).let {
                        val result = getFilesDiff(allLocalUserFiles, it?.userFileInfo ?: emptyList())

                        result.second
                    }

                    // Steam de-lists a cloud file by marking it Forgotten or Deleted rather than
                    // dropping it from the list. FileForget is Valve's sanctioned way to free
                    // quota without touching the user's local copy, so that local file is still
                    // on disk and still looks like something to upload. Sending it again would
                    // undo the de-listing on the very next sync - the loop the cloud cleanup
                    // exists to break.
                    val uploadableChanges = if (tombstonedCloudKeys.isEmpty()) {
                        fileChanges
                    } else {
                        val skipped = (fileChanges.filesCreated + fileChanges.filesModified + fileChanges.filesDeleted)
                            .count { canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys }

                        if (skipped > 0) {
                            Timber.i("Skipping $skipped file(s) of ${appInfo.id} de-listed in the cloud")
                        }

                        fileChanges.copy(
                            filesCreated = fileChanges.filesCreated.filterNot {
                                canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
                            },
                            filesModified = fileChanges.filesModified.filterNot {
                                canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
                            },
                            filesDeleted = fileChanges.filesDeleted.filterNot {
                                canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
                            },
                        )
                    }

                    uploadsRequired = uploadableChanges.filesCreated.isNotEmpty() ||
                        uploadableChanges.filesModified.isNotEmpty()
                    val hasBatchChanges = uploadsRequired || uploadableChanges.filesDeleted.isNotEmpty()
                    filesManaged = allLocalUserFiles.size

                    // A tombstone may be the only difference. Advancing the local snapshot avoids
                    // reopening an empty batch on every sync while keeping the local file intact.
                    if (!hasBatchChanges) {
                        uploadsCompleted = true
                        lastCloudAppChangeNumber = cloudAppChangeNumber
                        with(steamInstance) {
                            db.withTransaction {
                                fileChangeListsDao.insert(appInfo.id, allLocalUserFiles)
                                changeNumbersDao.insert(appInfo.id, cloudAppChangeNumber)
                            }
                        }
                        return@async
                    }

                    // A delete-only batch must be allowed even when the remaining local set is
                    // above quota; quota constrains files being uploaded, not removals.
                    if (uploadsRequired) {
                        val quotaEligibleFiles = allLocalUserFiles.filterNot {
                            canonicalCloudKey(it.prefixPath) in tombstonedCloudKeys
                        }
                        val quotaViolation = checkQuota(
                            ufs = appInfo.ufs,
                            fileCount = quotaEligibleFiles.size,
                            totalBytes = quotaEligibleFiles.sumOf { userFile ->
                                runCatching { Files.size(userFile.getAbsPath(prefixToPath)) }.getOrDefault(0L)
                            },
                        )

                        if (quotaViolation != null) {
                            Timber.e("Not uploading save files of ${appInfo.name}: $quotaViolation")
                            uploadsCompleted = false
                            syncResult = SyncResult.QuotaExceeded
                            return@async
                        }
                    }

                    val uploadResult: UserFilesUploadResult

                    microsecUploadFiles = measureTime {
                        uploadResult = uploadFiles(uploadableChanges, parentScope).await()
                        filesUploaded = uploadResult.filesUploaded
                        bytesUploaded = uploadResult.bytesUploaded
                    }.inWholeMicroseconds

                    // Recording the file list against change number 0 would leave us permanently
                    // behind the cloud, turning every later sync into a conflict.
                    val uploadRecorded = uploadResult.uploadBatchSuccess && uploadResult.appChangeNumber > 0

                    if (uploadResult.uploadBatchSuccess && !uploadRecorded) {
                        Timber.e("Upload batch succeeded with change number ${uploadResult.appChangeNumber}, discarding")
                    }

                    uploadsCompleted = uploadRecorded

                    if (uploadRecorded) {
                        lastCloudAppChangeNumber = uploadResult.appChangeNumber
                        with(steamInstance) {
                            db.withTransaction {
                                fileChangeListsDao.insert(appInfo.id, allLocalUserFiles)
                                changeNumbersDao.insert(appInfo.id, uploadResult.appChangeNumber)
                            }
                        }
                    } else {
                        syncResult = SyncResult.UpdateFail
                    }
                }
            }

            // Download cloud files that were never synced to this device: present in the cloud
            // manifest but absent from both disk and the pre-sync cache. An upload-only sync (e.g.
            // Keep Local) advances our change number without downloading, orphaning cloud-only files.
            // A file in the cache but missing from disk was deleted locally — left out of "known" on
            // purpose so it propagates as a cloud delete rather than being resurrected. The pre-sync
            // cachedFileList is used (not a fresh read) so an upload earlier in this sync can't hide
            // such a delete. Callers run this only after any local upload, so a full local rescan is
            // then the correct cache snapshot.
            val reconcileNeverSyncedCloudFiles: (CoroutineScope) -> Deferred<Int> = { parentScope ->
                parentScope.async {
                    // Lowercased absolute paths, mirroring the silent-rehydrate comparison, since
                    // Steam Cloud and wine may disagree on case.
                    val knownKeys = (allLocalUserFiles + (cachedFileList?.userFileInfo ?: emptyList()))
                        .map { it.getAbsPath(prefixToPath).toString().lowercase() }
                        .toSet()
                    val neverSynced = activeCloudFiles.filter { cloudFile ->
                        val localPath = getFullFilePath(cloudFile, appFileListChange)
                        localPath.toString().lowercase() !in knownKeys && !Files.exists(localPath)
                    }

                    if (neverSynced.isEmpty()) {
                        0
                    } else {
                        Timber.i("Reconcile: downloading ${neverSynced.size} never-synced cloud file(s) for ${appInfo.id}")
                        val downloadInfo = downloadFiles(neverSynced, appFileListChange, parentScope).await()
                        filesDownloaded += downloadInfo.filesDownloaded
                        bytesDownloaded += downloadInfo.bytesDownloaded
                        steamInstance.fileChangeListsDao.insert(appInfo.id, getLocalUserFilesAsPrefixMap().values.flatten())
                        downloadInfo.filesDownloaded
                    }
                }
            }

            if (effectiveLocalChangeNumber < cloudAppChangeNumber) {
                // our change number is less than the expected, meaning we are behind and
                // need to download the new user files, but first we should check that
                // the local user files are not conflicting with their respective change
                // number or else that would mean that the user made changes locally and
                // on a separate device and they must choose between the two
                microsecAcLaunch = measureTime {
                    var hasLocalChanges: Boolean

                    microsecAcPrepUserFiles = measureTime {
                        hasLocalChanges = syncableCachedUserFiles?.let {
                            getFilesDiff(syncableLocalUserFiles, it).first
                        } == true
                    }.inWholeMicroseconds

                    val hasUncachedLocalFiles = cacheIsAbsentOrEmpty && syncableLocalUserFiles.isNotEmpty()
                    var rehydratedSilently = false
                    if (hasUncachedLocalFiles) {
                        // no cache but local files exist. before declaring conflict,
                        // check if local state is byte-identical to remote — this is
                        // the "cache-wiped by destructive migration, nothing actually
                        // changed" case and should be silent. key by absolute filesystem
                        // path: cloud stores files as (pathPrefixIndex, basename) while
                        // local scan stores filename as subdir-relative path with a
                        // single pattern prefix, so basename-only keys won't match for
                        // nested files.
                        // windows paths are case-insensitive; steam cloud and wine may
                        // disagree on case. lowercase the keys so content-identical
                        // files compare equal regardless.
                        val localByPath = syncableLocalUserFiles.associate {
                            it.getAbsPath(prefixToPath).toString().lowercase() to it.sha
                        }
                        val remoteByPath = activeCloudFiles.associate {
                            getFullFilePath(it, appFileListChange).toString().lowercase() to it.shaFile
                        }
                        val localMatchesRemote = localByPath.keys == remoteByPath.keys &&
                            localByPath.all { (path, sha) ->
                                sha.contentEquals(remoteByPath[path])
                            }

                        if (localMatchesRemote) {
                            Timber.i("Cache absent but local matches remote — rehydrating cache silently")
                            with(steamInstance) {
                                db.withTransaction {
                                    fileChangeListsDao.insert(appInfo.id, allLocalUserFiles)
                                    changeNumbersDao.insert(appInfo.id, cloudAppChangeNumber)
                                }
                            }
                            syncResult = SyncResult.UpToDate
                            filesManaged = allLocalUserFiles.size
                            rehydratedSilently = true
                        } else {
                            hasLocalChanges = true
                            conflictUfsVersion = CURRENT_UFS_PARSE_VERSION
                            remoteTimestamp = activeCloudFiles.map { it.timestamp.time }.maxOrNull() ?: 0L
                            localTimestamp = allLocalUserFiles.map { it.timestamp }.maxOrNull() ?: 0L
                        }
                    }

                    if (rehydratedSilently) {
                        // nothing to do — cache is now consistent with cloud
                    } else if (!hasLocalChanges) {
                        // we can safely download the new changes since no changes have been
                        // made locally

                        Timber.i("No local changes but new cloud user files")

                        downloadUserFiles(parentScope).await()?.let {
                            return@async it
                        }
                    } else {
                        Timber.i("Found local changes and new cloud user files, conflict resolution...")

                        when (preferredSave) {
                            SaveLocation.Local -> {
                                // overwrite remote save with the local one
                                uploadUserFiles(parentScope).await()
                                // Keep-Local is upload-only; without this, cloud-only files this
                                // device never had would be orphaned. Pull them down instead.
                                reconcileNeverSyncedCloudFiles(parentScope).await()
                            }

                            SaveLocation.Remote -> {
                                // overwrite local save with the remote one
                                downloadUserFiles(parentScope).await()?.let {
                                    return@async it
                                }
                            }

                            SaveLocation.None -> {
                                syncResult = SyncResult.Conflict
                                remoteTimestamp = activeCloudFiles.map { it.timestamp.time }.maxOrNull() ?: 0L
                                localTimestamp = allLocalUserFiles.map { it.timestamp }.maxOrNull() ?: 0L
                            }
                        }
                    }
                }.inWholeMicroseconds
            } else if (effectiveLocalChangeNumber == cloudAppChangeNumber) {
                // our app change numbers are the same so the file hashes should match
                // if they do not then that means we have new user files locally that
                // need uploading
                microsecAcExit = measureTime {
                    // var fileChanges: FileChanges? = null

                    val hasLocalChanges = syncableCachedUserFiles
                        ?.let {
                            val result = getFilesDiff(syncableLocalUserFiles, it)
                            // fileChanges = result.second
                            result.first
                        } == true

                    if (hasLocalChanges) {
                        Timber.i("Found local changes and no new cloud user files")

                        uploadUserFiles(parentScope).await()
                    }

                    // Equal change numbers normally mean "cloud has nothing new", but an earlier
                    // upload-only sync can leave cloud-only files that were never downloaded here.
                    // Reconcile after any upload (the two file sets are disjoint: never-synced files
                    // are by definition not on disk, so not local changes).
                    val downloaded = reconcileNeverSyncedCloudFiles(parentScope).await()

                    if (!hasLocalChanges) {
                        if (downloaded > 0) {
                            Timber.i("Downloaded $downloaded never-synced cloud file(s)")
                            syncResult = SyncResult.Success
                        } else {
                            Timber.i("No local changes and no new cloud user files, doing nothing...")
                            syncResult = SyncResult.UpToDate
                        }
                    }
                }.inWholeMicroseconds
            } else {
                // our last scenario is if the change number we have is greater than
                // the change number from the cloud. This scenario should not happen, I
                // believe, since we get the new app change number after having downloaded
                // or uploaded from/to the cloud, so we should always be either behind or
                // on par with the cloud change number, never ahead
                Timber.e("Local change number greater than cloud $localAppChangeNumber > $cloudAppChangeNumber")

                syncResult = SyncResult.UnknownFail
            }
        }.inWholeMicroseconds

        postSyncInfo = PostSyncInfo(
            syncResult = syncResult,
            conflictUfsVersion = conflictUfsVersion,
            remoteTimestamp = remoteTimestamp,
            localTimestamp = localTimestamp,
            uploadsRequired = uploadsRequired,
            uploadsCompleted = uploadsCompleted,
            filesUploaded = filesUploaded,
            filesDownloaded = filesDownloaded,
            filesDeleted = filesDeleted,
            filesManaged = filesManaged,
            hashCacheHits = hashCacheHits.get(),
            hashCacheMisses = hashCacheMisses.get(),
            bytesUploaded = bytesUploaded,
            bytesDownloaded = bytesDownloaded,
            microsecTotal = microsecTotal,
            microsecInitCaches = microsecInitCaches,
            microsecValidateState = microsecValidateState,
            microsecAcLaunch = microsecAcLaunch,
            microsecAcPrepUserFiles = microsecAcPrepUserFiles,
            microsecAcExit = microsecAcExit,
            // microsecBuildSyncList = microsecBuildSyncList,
            microsecDeleteFiles = microsecDeleteFiles,
            microsecDownloadFiles = microsecDownloadFiles,
            microsecUploadFiles = microsecUploadFiles,
        )

        // Write remotecache.vdf after successful sync
        if (syncResult == SyncResult.Success || syncResult == SyncResult.UpToDate) {
            try {
                val allFiles = getLocalUserFilesAsPrefixMap().values.flatten()

                writeRemoteCacheVdf(
                    appId = appInfo.id,
                    userdataPath = Paths.get(prefixToPath(PathType.SteamUserData.name)).parent,
                    changeNumber = lastCloudAppChangeNumber,
                    files = allFiles,
                    prefixToPath = prefixToPath,
                    syncState = 1, // 1 = syncing (matches Windows behavior)
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to write remotecache.vdf for app ${appInfo.id}")
            }
        }

        postSyncInfo
    }

    private suspend fun downloadSingleFile(
        appInfo: SteamApp,
        steamCloud: SteamCloud,
        hashCacheDao: SteamFileHashCacheDao,
        file: AppFileInfo,
        fileList: AppFileChangeList,
        getFilePrefixPath: (AppFileInfo, AppFileChangeList) -> String,
        getFullFilePath: (AppFileInfo, AppFileChangeList) -> Path,
        buildUrl: (Boolean, String, String) -> String,
        httpClient: okhttp3.OkHttpClient,
        totalRawBytes: Long,
        downloadedRawBytes: AtomicLong,
        lastReportedPercent: AtomicInteger,
        completedFiles: AtomicInteger,
        totalFiles: Int,
        progressMessage: (Int) -> String,
        onProgress: ((message: String, progress: Float) -> Unit)?, // invoked from IO thread
    ): UserFilesDownloadResult? {
        val prefixedPath = getFilePrefixPath(file, fileList)
        val actualFilePath = getFullFilePath(file, fileList)

        Timber.i("$prefixedPath -> $actualFilePath")

        val fileDownloadInfo = try {
            steamCloud.clientFileDownload(appInfo.id, prefixedPath).await()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch download info for %s", prefixedPath)
            return null
        }

        if (fileDownloadInfo.urlHost.isEmpty()) {
            Timber.w("URL host of $prefixedPath was empty")
            return null
        }

        val httpUrl = with(fileDownloadInfo) {
            buildUrl(useHttps, urlHost, urlPath)
        }

        Timber.i("Downloading $httpUrl")

        val headers = Headers.headersOf(
            *fileDownloadInfo.requestHeaders
                .map { listOf(it.name, it.value) }
                .flatten()
                .toTypedArray(),
        )

        val request = Request.Builder()
            .url(httpUrl)
            .headers(headers)
            .build()

        val response = try {
            withTimeout(SteamService.requestTimeout) {
                httpClient.newCall(request).execute()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "Timed out downloading %s", actualFilePath)
            null
        } catch (e: SocketTimeoutException) {
            Timber.w("Could not download $actualFilePath: %s", e.message)
            null
        } catch (e: IOException) {
            Timber.w("Could not download $actualFilePath: %s", e.message)
            null
        }

        if (response == null) {
            return null
        }

        if (!response.isSuccessful) {
            Timber.w("File download of $prefixedPath was unsuccessful")
            response.close()
            return null
        }

        var temporaryFile: Path? = null

        try {
            val totalFileSize = fileDownloadInfo.rawFileSize.toLong()
            val targetParent = requireNotNull(actualFilePath.parent) {
                "Cloud save path has no parent: $actualFilePath"
            }
            Files.createDirectories(targetParent)
            val temporaryPath = Files.createTempFile(targetParent, ".cloud-", ".download")
            temporaryFile = temporaryPath

            val copyToTemporaryFile: (InputStream) -> Boolean = { input ->
                FileOutputStream(temporaryPath.toString()).use { fs ->
                    val totalBytesRead = input.copyTo(fs, 8 * 1024) { chunkBytes, _ ->
                        if (totalRawBytes > 0L) {
                            val currentPercent = (
                                downloadedRawBytes.addAndGet(chunkBytes) * 100 / totalRawBytes
                                ).toInt().coerceIn(0, 100)
                            while (true) {
                                val previousPercent = lastReportedPercent.get()
                                if (currentPercent <= previousPercent) break
                                if (lastReportedPercent.compareAndSet(previousPercent, currentPercent)) {
                                    onProgress?.invoke(
                                        progressMessage(completedFiles.get()),
                                        currentPercent / 100f,
                                    )
                                    break
                                }
                            }
                        }
                    }

                    if (totalBytesRead != totalFileSize) {
                        Timber.w("Bytes read from stream of $prefixedPath does not match expected size")
                        return@use false
                    }
                    true
                }
            }

            val downloaded = withTimeout(SteamService.responseTimeout) {
                if (fileDownloadInfo.fileSize != fileDownloadInfo.rawFileSize) {
                    response.body?.byteStream()?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInput ->
                            val entry = zipInput.nextEntry

                            if (entry == null) {
                                Timber.w("Downloaded user file $prefixedPath has no zip entries")
                                return@withTimeout false
                            }

                            if (!copyToTemporaryFile(zipInput)) return@withTimeout false

                            if (zipInput.nextEntry != null) {
                                Timber.e("Downloaded user file $prefixedPath has more than one zip entry")
                            }
                        }
                    } ?: return@withTimeout false
                } else {
                    response.body?.byteStream()?.use { inputStream ->
                        if (!copyToTemporaryFile(inputStream)) return@withTimeout false
                    } ?: return@withTimeout false
                }
                true
            }

            if (!downloaded) {
                return null
            }

            val downloadedSize = Files.size(temporaryPath)
            if (downloadedSize != totalFileSize) {
                Timber.w("Downloaded size for $prefixedPath was $downloadedSize, expected $totalFileSize")
                return null
            }

            val downloadedSha = streamingShaHash(temporaryPath)
            if (!downloadedSha.contentEquals(file.shaFile)) {
                Timber.w("Downloaded SHA for $prefixedPath did not match Steam's manifest")
                return null
            }

            // Preserve file timestamp from steamcloud, could fix game save loading, tested Skyrim.
            try {
                val fileTime = FileTime.fromMillis(fileDownloadInfo.timestamp.time)
                Files.setLastModifiedTime(temporaryPath, fileTime)
            } catch (e: Exception) {
                Timber.w("Failed to set lastModified for $actualFilePath: ${e.message}")
            }

            try {
                Files.move(
                    temporaryPath,
                    actualFilePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, actualFilePath, StandardCopyOption.REPLACE_EXISTING)
            }

            hashCacheDao.insert(
                SteamFileHashCache(
                    appId = appInfo.id,
                    absPath = actualFilePath.pathString,
                    sizeBytes = downloadedSize,
                    mtimeMillis = Files.getLastModifiedTime(actualFilePath).toMillis(),
                    sha = downloadedSha,
                ),
            )

            val finishedFiles = completedFiles.incrementAndGet()
            val finalProgress = if (totalRawBytes > 0L) {
                (downloadedRawBytes.get().toFloat() / totalRawBytes).coerceIn(0f, 1f)
            } else {
                finishedFiles.toFloat() / totalFiles
            }
            onProgress?.invoke(progressMessage(finishedFiles), finalProgress)

            return UserFilesDownloadResult(1, fileDownloadInfo.rawFileSize.toLong())
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "Timed out downloading %s", actualFilePath)
            return null
        } catch (e: FileSystemException) {
            Timber.w("Could not download $actualFilePath: %s", e.message)
            return null
        } catch (e: SocketTimeoutException) {
            // Distinct from the outer SocketTimeoutException catch above: that one covers the
            // connection/response phase; this one covers a timeout during the streaming read.
            Timber.w("Could not download $actualFilePath: %s", e.message)
            return null
        } catch (e: IOException) {
            Timber.w("Could not download $actualFilePath: %s", e.message)
            return null
        } finally {
            temporaryFile?.let { path ->
                runCatching { Files.deleteIfExists(path) }
                    .onFailure { Timber.w(it, "Could not remove incomplete cloud download $path") }
            }
            response.close()
        }
    }

    private fun AppFileChangeList.printFileChangeList(appInfo: SteamApp) {
        with(this) {
            Timber.i(
                "GetAppFileListChange(${appInfo.id}):" +
                    "\n\tTotal Files: ${files.size}" +
                    "\n\tCurrent Change Number: $currentChangeNumber" +
                    "\n\tIs Only Delta: $isOnlyDelta" +
                    "\n\tApp BuildID Hwm: $appBuildIDHwm" +
                    "\n\tPath Prefixes: \n\t\t${pathPrefixes.joinToString("\n\t\t")}" +
                    "\n\tMachine Names: \n\t\t${machineNames.joinToString("\n\t\t")}" +
                    files.joinToString {
                        "\n\t${it.filename}:" +
                            "\n\t\tshaFile: ${it.shaFile}" +
                            "\n\t\ttimestamp: ${it.timestamp}" +
                            "\n\t\trawFileSize: ${it.rawFileSize}" +
                            "\n\t\tpersistState: ${it.persistState}" +
                            "\n\t\tplatformsToSync: ${it.platformsToSync}" +
                            "\n\t\tpathPrefixIndex: ${if (it.hasPathPrefixIndex) it.pathPrefixIndex.toString() else "<none>"}" +
                            "\n\t\tmachineNameIndex: ${if (it.hasMachineNameIndex) it.machineNameIndex.toString() else "<none>"}"
                    },
            )
        }
    }

    /**
     * Writes a remotecache.vdf file for the given app in the Steam userdata directory.
     * This file tracks Steam Cloud sync metadata for save files.
     *
     * @param appId The Steam app ID
     * @param userdataPath Path to the app-specific userdata directory (e.g., "userdata/steamid/appid/")
     * @param changeNumber Cloud change number (0 if not synced)
     * @param files List of UserFileInfo representing the synced files
     * @param prefixToPath Function to convert path prefix to absolute path
     * @param syncState Sync state: 0=unknown, 1=syncing, 2=pending, 3=synced
     */
    fun writeRemoteCacheVdf(
        appId: Int,
        userdataPath: Path,
        changeNumber: Long = 0,
        files: List<UserFileInfo>,
        prefixToPath: (String) -> String,
        syncState: Int = 1,
    ) {
        try {
            Files.createDirectories(userdataPath)

            val remoteCacheFile = userdataPath.resolve("remotecache.vdf")

            // Build VDF structure using KeyValue
            val root = KeyValue(appId.toString())
            root.children.add(KeyValue("ChangeNumber", changeNumber.toString()))
            root.children.add(KeyValue("OSType", EOSType.WinUnknown.code().toString())) // 0 = Windows

            // Create an entry for each file using the filename as the key
            // Sort files by their cloud path for consistent ordering
            files.sortedBy { fileInfo ->
                if (fileInfo.cloudPath.isBlank() || fileInfo.cloudPath == ".") {
                    fileInfo.filename
                } else {
                    "${fileInfo.cloudPath}/${fileInfo.filename}".replace("\\", "/")
                }.replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
                    .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())
            }.forEach { fileInfo ->
                val fileSize = try {
                    Files.size(fileInfo.getAbsPath(prefixToPath))
                } catch (e: Exception) {
                    0L
                }

                val fileSha = fileInfo.sha.joinToString("") { "%02x".format(it) }
                val fileTimestamp = fileInfo.timestamp / 1000 // Convert to seconds

                // Use the cloud path (cloudPath + filename) as the key
                val cloudFilePath = if (fileInfo.cloudPath.isBlank() || fileInfo.cloudPath == ".") {
                    fileInfo.filename
                } else {
                    "${fileInfo.cloudPath}/${fileInfo.filename}".replace("\\", "/")
                }.replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
                    .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())

                val fileEntry = KeyValue(cloudFilePath)
                fileEntry.children.add(KeyValue("root", "0"))
                fileEntry.children.add(KeyValue("size", fileSize.toString()))
                fileEntry.children.add(KeyValue("localtime", fileTimestamp.toString()))
                fileEntry.children.add(KeyValue("time", fileTimestamp.toString()))
                fileEntry.children.add(KeyValue("remotetime", fileTimestamp.toString()))
                fileEntry.children.add(KeyValue("sha", fileSha))
                fileEntry.children.add(KeyValue("syncstate", syncState.toString()))
                fileEntry.children.add(KeyValue("persiststate", "0"))
                fileEntry.children.add(KeyValue("platformstosync2", "-1"))

                root.children.add(fileEntry)
            }

            // Write to file
            root.saveToFile(remoteCacheFile.toFile(), false)

            Timber.i("Wrote remotecache.vdf for app $appId to ${remoteCacheFile.toAbsolutePath()}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to write remotecache.vdf for app $appId")
        }
    }
}
