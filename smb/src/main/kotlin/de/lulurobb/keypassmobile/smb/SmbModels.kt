package de.lulurobb.keypassmobile.smb

import java.time.Instant

/**
 * A disk share of the server as returned by [SmbConnection.listShares].
 *
 * @property name Share name, used as `share` argument of the other calls.
 * @property comment Description configured on the server, empty if none.
 */
data class ShareInfo(
    val name: String,
    val comment: String,
)

/**
 * An entry of [SmbConnection.listFolder]: either a folder or a `.kdbx` file.
 *
 * @property name File or folder name without path.
 * @property isFolder True for folders.
 * @property size File size in bytes, 0 for folders.
 * @property modified Last write time as reported by the server.
 */
data class FolderItem(
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Instant,
)

/**
 * A folder as shown by the browse screen: the [items] of [SmbConnection.listFolder] and how many
 * files were left out because they are no `.kdbx` files (DS-02). Folders are never left out here.
 *
 * @property items Folders and `.kdbx` files, sorted.
 * @property otherFileCount Number of files that are not `.kdbx` files and are therefore not in [items].
 */
data class FolderListing(
    val items: List<FolderItem>,
    val otherFileCount: Int,
)

/**
 * Metadata of a remote file, used to decide whether the file on the share is newer
 * than the offline copy (SY-04).
 *
 * @property size File size in bytes.
 * @property modified Last write time as reported by the server.
 */
data class FileStat(
    val size: Long,
    val modified: Instant,
)
