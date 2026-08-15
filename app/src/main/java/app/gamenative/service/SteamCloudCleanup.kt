package app.gamenative.service

import app.gamenative.PrefManager
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.utils.SteamSaveSweep
import app.gamenative.utils.SteamUtils
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Removes regenerable engine files that earlier syncs left in an app's Steam Cloud storage.
 *
 * The sync path can only delete a cloud file it can still see in the local file-list cache: it
 * diffs the local scan against that cache and sends what disappeared. Once the cache row is gone
 * (game uninstalled, app data cleared, container recreated) the cloud files it described are
 * unreachable, and a sweep that has since been narrowed will never list them again either. This
 * reconciles the other way around — against what the cloud actually holds — so those files can
 * still be removed.
 *
 * Nothing here deletes on its own: [scan] classifies, the caller confirms, and [deleteFiles]
 * removes exactly the paths it is handed.
 */
object SteamCloudCleanup {

    /**
     * Paths per delete batch.
     *
     * How large a `filesToDelete` list Steam accepts is not something this client knows: the sync
     * path only ever sends the handful of files that disappeared since the last run. Rather than
     * discover the limit with the whole set in one request, this splits it up, which also keeps a
     * failed batch from taking the rest of the set down with it. An app's declared file limit is
     * in the low thousands at most, so this stays a handful of requests.
     */
    internal const val MAX_DELETES_PER_BATCH = 200

    enum class CloudFileKind {
        /** Matches the engine-cache deny list, below a save rule of this app. Regenerable. */
        EngineCache,

        /** Covered by a save rule of this app and not denied. Treated as save data. */
        SaveFile,

        /** Under no save rule this device knows about — another OS, another device, or a root
         *  that does not resolve here. Never selected: it may well be a save. */
        Unrecognised,
    }

    /**
     * @param path the name Steam knows the file by, built exactly as the download path is, so it
     *             is what a delete has to name.
     */
    data class CloudFile(
        val path: String,
        val sizeBytes: Long,
        val kind: CloudFileKind,
    )

    sealed interface ScanResult {
        data class Ready(val files: List<CloudFile>) : ScanResult {
            val removable: List<CloudFile> get() = files.filter { it.kind == CloudFileKind.EngineCache }
            val kept: List<CloudFile> get() = files.filter { it.kind != CloudFileKind.EngineCache }
        }

        /** The cloud listing could not be trusted, so no deletion set exists. */
        data class Failed(val reason: String) : ScanResult
    }

    /**
     * @param deleted paths that were in a batch Steam opened and that was completed successfully.
     *                A batch delete carries no per-file result, so this is as fine-grained as the
     *                outcome gets.
     */
    data class CleanupResult(
        val requested: Int,
        val deleted: Int,
        val failedPaths: List<String>,
    )

    /** A file as the cloud lists it: a path prefix (may be empty) plus a name below it. */
    internal data class RemoteFile(
        val prefix: String,
        val filename: String,
        val sizeBytes: Long,
    )

    /**
     * Lists what the cloud holds for [appInfo] and sorts it into [CloudFileKind]s.
     *
     * Change number 0 is what the sync path asks with when it has no cached list of its own. The
     * response says whether it came back as a delta; one that did, or one that holds nothing, is
     * reported as a failure rather than silently becoming "delete everything".
     */
    suspend fun scan(appInfo: SteamApp, steamCloud: SteamCloud): ScanResult {
        val fileList = try {
            steamCloud.getAppFileListChange(appInfo.id, 0L).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not list the cloud files of ${appInfo.id}")
            return ScanResult.Failed("Steam did not return the cloud file list")
        }

        if (fileList.isOnlyDelta) {
            return ScanResult.Failed("Steam returned only a partial cloud file list")
        }

        val remoteFiles = toRemoteFiles(fileList)

        if (remoteFiles.isEmpty()) {
            return ScanResult.Failed("Steam listed no cloud files for this app")
        }

        val files = classify(cloudRulePrefixes(appInfo.ufs), remoteFiles)

        Timber.i(
            "Cloud cleanup scan of ${appInfo.id}: ${files.size} file(s), " +
                files.groupingBy { it.kind }.eachCount(),
        )

        return ScanResult.Ready(files)
    }

    /**
     * Deletes [paths] from the app's cloud storage in batches.
     *
     * A batch declares its deletions in the request that opens it, the same way the sync path
     * removes files, so no per-file transfer follows. Every batch is completed on the way out with
     * the result it actually had, since one left open blocks the app's next sync.
     *
     * A batch that fails costs only its own chunk: the loop carries on and the paths it held are
     * reported back so the caller can say what survived.
     */
    suspend fun deleteFiles(
        appInfo: SteamApp,
        clientId: Long,
        steamInstance: SteamService,
        steamCloud: SteamCloud,
        paths: List<String>,
        onProgress: ((deleted: Int, total: Int) -> Unit)? = null,
    ): CleanupResult {
        if (paths.isEmpty()) {
            return CleanupResult(requested = 0, deleted = 0, failedPaths = emptyList())
        }

        SteamAutoCloud.closeAbandonedUploadBatch(steamCloud, appInfo.id)

        // Batches only take effect inside an app sync session, and Steam will not hand one out
        // while another client holds an upload pending for the app. Both halves matter: without
        // the session the deletes are accepted and quietly dropped, and without adopting the
        // stale flag there is no session to be had. Opened here, closed after the deletes.
        beginSyncSession(appInfo, clientId, steamInstance, steamCloud)

        val batchKey = SteamAutoCloud.openUploadBatchKey(appInfo.id)
        val appBuildId = appInfo.branches[SteamService.getInstalledApp(appInfo.id)?.branch ?: "public"]?.buildId ?: 0
        val failedPaths = mutableListOf<String>()
        var deleted = 0

        for (chunk in paths.chunked(MAX_DELETES_PER_BATCH)) {
            try {
                val batch = steamCloud.beginAppUploadBatch(
                    appId = appInfo.id,
                    machineName = SteamUtils.getMachineName(steamInstance),
                    clientId = clientId,
                    filesToDelete = chunk,
                    filesToUpload = emptyList(),
                    appBuildId = appBuildId,
                ).await()

                // As in the upload path, an unset batch id is the only sign Steam refused the
                // batch, and there is then nothing to complete.
                if (batch.batchID == 0L) {
                    Timber.e("Steam did not open a delete batch for ${appInfo.id}, skipping ${chunk.size} file(s)")
                    failedPaths += chunk
                    continue
                }

                PrefManager.setLongBlocking(batchKey, batch.batchID)

                var batchSuccess = true
                var deletedInChunk = 0

                try {
                    // Naming the files when the batch opens only declares the intent; each one
                    // still has to be deleted individually inside the batch or Steam keeps them
                    // and the batch completes clean anyway. This call also returns a per-file
                    // result, which the batch never does, so failures stop being invisible.
                    chunk.forEach { path ->
                        val ok = steamCloud.deleteFile(appInfo.id, path, batch.batchID).await()

                        if (ok) {
                            deletedInChunk++
                        } else {
                            Timber.w("Steam refused to delete $path of ${appInfo.id}")
                            failedPaths += path
                        }

                        // Reported from inside the batch on purpose: anything that throws here, a
                        // cancelled caller included, has to leave through the close below.
                        onProgress?.invoke(deleted + deletedInChunk, paths.size)
                    }
                } catch (e: Exception) {
                    batchSuccess = false
                    throw e
                } finally {
                    withContext(NonCancellable) {
                        val batchEResult = if (batchSuccess) EResult.OK else EResult.Fail

                        Timber.i("Completing delete batch ${batch.batchID} of ${appInfo.id} with $batchEResult")

                        steamCloud.completeAppUploadBatch(
                            appId = appInfo.id,
                            batchId = batch.batchID,
                            batchEResult = batchEResult,
                        ).await()

                        PrefManager.setLongBlocking(batchKey, 0L)
                    }
                }

                deleted += deletedInChunk
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Delete batch of ${chunk.size} file(s) failed for ${appInfo.id}")
                failedPaths += chunk
            }
        }

        Timber.i("Cloud cleanup of ${appInfo.id}: deleted $deleted of ${paths.size} file(s)")

        // Closing the session is what commits the batches above; leaving it open would also leave
        // the app holding the very pending-upload flag this exists to clear.
        endSyncSession(appInfo, clientId, steamCloud)

        return CleanupResult(requested = paths.size, deleted = deleted, failedPaths = failedPaths)
    }

    /**
     * Opens the app sync session the delete batches are committed inside, adopting a stale one.
     *
     * A batch is only applied as part of a sync session; sending one outside a session is accepted
     * and silently dropped, which reads as a successful cleanup that changed nothing. Steam will
     * not open a session while another client holds an upload pending for the app, and a run that
     * died mid-upload leaves exactly that behind - so the flag has to be taken over rather than
     * waited out.
     *
     * [SteamAutoCloud.closeAbandonedUploadBatch] already covers a batch this install still
     * remembers, but it reads the locally persisted batch id, which is gone once app data is
     * cleared or the game is reinstalled. The flag on Steam's side outlives that record, so
     * reconcile the way the file scan does - against what the cloud actually reports.
     *
     * Best effort: a failure here is logged, and the deletes are still attempted.
     */
    private suspend fun beginSyncSession(
        appInfo: SteamApp,
        clientId: Long,
        steamInstance: SteamService,
        steamCloud: SteamCloud,
    ) {
        try {
            val pending = steamCloud.signalAppLaunchIntent(
                appId = appInfo.id,
                clientId = clientId,
                machineName = SteamUtils.getMachineName(steamInstance),
                // The point of this call: adopt a stale session rather than refuse to act on it.
                ignorePendingOperations = true,
                osType = EOSType.WinUnknown,
            ).await()

            if (pending.isEmpty()) {
                Timber.i("Opened cleanup sync session for ${appInfo.id}, no pending operations")
            } else {
                Timber.i(
                    "Opened cleanup sync session for %d, taking over: %s",
                    appInfo.id,
                    pending.joinToString { "${it.operation} on ${it.machineName} (client ${it.clientId})" },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to open cleanup sync session for ${appInfo.id}")
        }
    }

    /**
     * Ends the sync session, which is what commits the batches sent inside it.
     *
     * Reported as nothing outstanding: the deletes were declared when their batches were opened,
     * so there is no upload left to finish, and saying otherwise would leave the app holding the
     * pending-upload flag this whole path exists to clear.
     */
    private suspend fun endSyncSession(appInfo: SteamApp, clientId: Long, steamCloud: SteamCloud) {
        try {
            steamCloud.signalAppExitSyncDone(
                appId = appInfo.id,
                clientId = clientId,
                uploadsCompleted = true,
                uploadsRequired = false,
            )
            Timber.i("Closed cleanup sync session for ${appInfo.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to close cleanup sync session for ${appInfo.id}")
        }
    }

    internal fun toRemoteFiles(fileList: AppFileChangeList): List<RemoteFile> = fileList.files.map { file ->
        RemoteFile(
            // Mirrors the prefix the download path builds, so the two name the same file.
            prefix = if (file.hasPathPrefixIndex && file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(fileList.pathPrefixes[file.pathPrefixIndex]).pathString
            } else {
                ""
            },
            filename = file.filename,
            sizeBytes = file.rawFileSize.toLong(),
        )
    }

    /**
     * The cloud path prefixes this device's save rules cover.
     *
     * `SteamUserData` is always included: the local scan walks that root whether or not the app
     * declares a rule for it.
     */
    internal fun cloudRulePrefixes(ufs: UFS): List<String> {
        val prefixes = mutableListOf(normalize("%${PathType.SteamUserData.name}%"))

        ufs.saveFilePatterns.forEach { pattern ->
            prefixes += normalize("%${pattern.uploadRoot.name}%${pattern.uploadPath}")
        }

        return prefixes.distinct()
    }

    /**
     * Sorts each cloud file into a [CloudFileKind].
     *
     * A file only becomes [CloudFileKind.EngineCache] when it sits *below* one of [rulePrefixes]
     * and the part of its path below that prefix is denied by [SteamSaveSweep.isEngineCache] —
     * the same test, against the same kind of rule-relative path, that keeps these files from
     * being uploaded in the first place. Everything else is left for the user to keep: a file
     * under no known rule may be a save from another OS or another device, and a file under a
     * known rule that the deny list does not name is save data by definition.
     */
    internal fun classify(rulePrefixes: List<String>, remote: List<RemoteFile>): List<CloudFile> =
        remote.map { file ->
            val path = if (file.prefix.isEmpty()) file.filename else Paths.get(file.prefix, file.filename).pathString
            val normalized = normalize(path)
            val rulePrefix = rulePrefixes
                .filter { normalized.startsWith("$it/", ignoreCase = true) }
                .maxByOrNull { it.length }

            val kind = when {
                rulePrefix == null -> CloudFileKind.Unrecognised
                SteamSaveSweep.isEngineCache(normalized.substring(rulePrefix.length + 1)) -> CloudFileKind.EngineCache
                else -> CloudFileKind.SaveFile
            }

            CloudFile(path = path, sizeBytes = file.sizeBytes, kind = kind)
        }

    /**
     * Puts a cloud path into one shape so a rule prefix and a file path can be compared.
     *
     * Cloud paths carry their root as a `%Placeholder%` that a subdirectory may follow with or
     * without a separator, and rules spell their own paths with either separator and with the
     * account ids left as placeholders.
     */
    private fun normalize(raw: String): String {
        val slashed = raw
            .replace('\\', '/')
            .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
            .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())

        val rootEnd = if (slashed.startsWith("%")) slashed.indexOf('%', startIndex = 1) else -1

        if (rootEnd < 0) {
            return slashed.trim('/')
        }

        val root = slashed.substring(0, rootEnd + 1)
        val rest = slashed.substring(rootEnd + 1).trim('/')

        return if (rest.isEmpty()) root else "$root/$rest"
    }
}
