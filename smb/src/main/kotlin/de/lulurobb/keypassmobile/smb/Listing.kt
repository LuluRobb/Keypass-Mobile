package de.lulurobb.keypassmobile.smb

import de.lulurobb.keypassmobile.smb.rpc.RawShare
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** Share type bits (MS-SRVS 2.2.2.4). */
private const val STYPE_MASK = 0x000000FFL
private const val STYPE_DISKTREE = 0L
private const val STYPE_SPECIAL = 0x80000000L

/** FILE_ATTRIBUTE_DIRECTORY (MS-FSCC 2.6). */
private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

/** File extension shown in folder listings (DS-02), compared case-insensitively. */
private const val KDBX_EXTENSION = ".kdbx"

/**
 * Converts a user or UI path to the SMB form: backslash separators, no leading or
 * trailing separator, no empty or "." segments. The empty string is the share root.
 */
internal fun normalizePath(path: String): String =
    path.split('/', '\\').filter { it.isNotEmpty() && it != "." }.joinToString("\\")

/**
 * German name order: case- and accent-aware collation (A-umlaut sorts next to A, not
 * after Z), with a plain string comparison as tie-breaker for a stable total order.
 */
internal fun germanNameOrder(): Comparator<String> {
    val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.TERTIARY }
    return Comparator { a, b ->
        val c = collator.compare(a, b)
        if (c != 0) c else a.compareTo(b)
    }
}

/** Folders first, then files; each group in German name order. */
internal fun folderItemOrder(): Comparator<FolderItem> {
    val names = germanNameOrder()
    return compareBy<FolderItem> { !it.isFolder }.thenComparator { a, b -> names.compare(a.name, b.name) }
}

/** A raw directory entry as delivered by the server. */
internal class RawEntry(
    val name: String,
    val attributes: Long,
    val size: Long,
    val modified: Instant,
)

/**
 * Keeps folders and `.kdbx` files (case-insensitive), drops "." and "..", sorts with
 * [folderItemOrder].
 */
internal fun toFolderItems(entries: List<RawEntry>): List<FolderItem> = entries
    .asSequence()
    .filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
    .mapNotNull { e ->
        val isFolder = (e.attributes and FILE_ATTRIBUTE_DIRECTORY) != 0L
        when {
            isFolder -> FolderItem(e.name, isFolder = true, size = 0, modified = e.modified)
            e.name.endsWith(KDBX_EXTENSION, ignoreCase = true) ->
                FolderItem(e.name, isFolder = false, size = e.size, modified = e.modified)
            else -> null
        }
    }
    .sortedWith(folderItemOrder())
    .toList()

/**
 * Keeps plain disk shares: no IPC, printer or device shares, no special shares and
 * nothing ending in `$` (IPC$, ADMIN$, C$, print$, hidden shares). Sorted by name.
 */
internal fun toDiskShares(raw: List<RawShare>): List<ShareInfo> {
    val names = germanNameOrder()
    return raw
        .filter { s ->
            s.name.isNotBlank() &&
                !s.name.endsWith("$") &&
                (s.type and STYPE_MASK) == STYPE_DISKTREE &&
                (s.type and STYPE_SPECIAL) == 0L
        }
        .map { ShareInfo(it.name, it.comment) }
        .sortedWith { a, b -> names.compare(a.name, b.name) }
}
