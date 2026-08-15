package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

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
     * Collects the files a `ufs.savefiles` rule covers.
     *
     * With [recursive] false only the immediate children of [basePath] are candidates, which is
     * what a rule that omits `recursive` asks for.
     */
    fun findSaveFiles(basePath: Path, pattern: String, recursive: Boolean): List<Path> {
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) return emptyList()

        val results = mutableListOf<Path>()
        val collect: (Path) -> Unit = { path ->
            if (!Files.isDirectory(path) && matchesPattern(path.name, pattern)) {
                results.add(path)
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
