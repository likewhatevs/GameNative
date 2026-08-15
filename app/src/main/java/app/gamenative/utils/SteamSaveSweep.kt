package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import timber.log.Timber

/**
 * Selects the local files covered by a Steam Auto-Cloud `ufs.savefiles` rule.
 *
 * Steamworks documents a rule as a base directory plus two independent fields: `pattern`, a
 * "file mask pattern to match" using `*` as a wildcard, and `recursive`, which decides whether
 * to "include sub-directories when searching for matching files". So `recursive` selects the
 * candidate set and `pattern` filters it by file name.
 *
 * See https://partner.steamgames.com/doc/features/cloud
 */
object SteamSaveSweep {

    /**
     * Depth cap for recursive rules. Steam does not document a limit; this bounds the walk so a
     * symlink cycle inside the wine prefix cannot hang a sync, and matches the depth this client
     * previously used.
     */
    const val MAX_RECURSIVE_DEPTH: Int = 5

    /**
     * Path components that never hold save data. Steam's `ufs` schema has no exclude key, so this
     * has to live here.
     *
     * Matched case-insensitively against the components of a file's path *relative to the rule's
     * base directory*, so a rule that deliberately points at one of these directories still syncs
     * it — only files found *below* the rule's own directory can be denied.
     *
     * None of these names occurs as a path component of any `savefiles` rule in the 14 204-app
     * appinfo sample in ludusavi-manifest's steam-game-cache, i.e. no shipped rule targets them.
     */
    private val DENIED_DIRECTORIES = setOf(
        // Godot 4 writes its compiled-shader cache to user://shader_cache/<name>/<hash>/<hash>.cache
        // under both the RenderingDevice and the Compatibility renderer.
        "shader_cache",
        // Godot 4 writes its pipeline cache to user://vulkan/pipelines.*.cache; the directory keeps
        // the name "vulkan" under the other rendering backends too.
        "vulkan",
        // Godot's project-local generated data directory.
        ".godot",
        // Directories that are caches by name.
        "cache",
        "caches",
        // Log and crash-dump directories (e.g. Godot's user://logs/). Diagnostics, not save data.
        "logs",
        "crashes",
    )

    /** File-name suffixes that are regenerable by name. Matched case-insensitively. */
    private val DENIED_SUFFIXES = listOf(".cache")

    private val patternCache = ConcurrentHashMap<String, Regex>()

    /**
     * Matches a file name against a `ufs.savefiles` `pattern` mask.
     *
     * `*` is the only wildcard Steamworks documents, and it is applied to the file name alone, so
     * it never crosses a directory separator. `?`, `[...]` and `{...}` are treated as literals:
     * they are not documented as metacharacters, so reading them literally cannot silently widen
     * a rule.
     *
     * Matching is case-insensitive. The masks are authored for Windows, where file names are
     * case-insensitive, but they are applied here to a case-sensitive filesystem holding the
     * files of a Windows build.
     */
    fun matchesPattern(fileName: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        // "all files in the directory", the two spellings shipped rules use for it
        if (pattern == "*" || pattern == "*.*") return true
        val regex = patternCache.getOrPut(pattern) {
            pattern.split("*").joinToString(separator = "[^/\\\\]*") { Regex.escape(it) }
                .toRegex(RegexOption.IGNORE_CASE)
        }
        return regex.matches(fileName)
    }

    /**
     * Whether [relativePath] — a path relative to a rule's base directory — is an engine artefact
     * that must never be uploaded. Accepts either separator.
     */
    fun isEngineCache(relativePath: String): Boolean {
        val components = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (components.isEmpty()) return false
        val directories = components.dropLast(1)
        if (directories.any { it.lowercase() in DENIED_DIRECTORIES }) return true
        val fileName = components.last().lowercase()
        return DENIED_SUFFIXES.any { fileName.endsWith(it) }
    }

    /**
     * Collects the files a `ufs.savefiles` rule covers.
     *
     * With [recursive] false only the immediate children of [basePath] are candidates, which is
     * what a rule that omits `recursive` asks for. Engine artefacts are dropped in both modes.
     */
    fun findSaveFiles(basePath: Path, pattern: String, recursive: Boolean): List<Path> {
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) return emptyList()

        val results = mutableListOf<Path>()
        val collect: (Path) -> Unit = { path ->
            if (!Files.isDirectory(path) && matchesPattern(path.name, pattern)) {
                val relative = basePath.relativize(path).toString()
                if (isEngineCache(relative)) {
                    Timber.i("Skipping engine cache file $relative")
                } else {
                    results.add(path)
                }
            }
        }

        if (recursive) {
            FileUtils.walkThroughPath(basePath, MAX_RECURSIVE_DEPTH, collect)
        } else {
            Files.list(basePath).use { children -> children.forEach(collect) }
        }

        return results
    }
}
