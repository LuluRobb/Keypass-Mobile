package de.lulurobb.keypassmobile.platform.saf

/**
 * Pure rules for the place text and the parent folder of a picked document
 * (DS-10, UI-07). They work on the provider authority and the decoded document
 * ID, so JVM tests need no android.net.Uri; [ContentResolverDocuments] builds
 * the URIs and asks the providers for what these rules cannot know.
 */
internal object DocumentPlaces {
    /** Provider of the internal storage and of SD cards; document IDs are "root:path". */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Provider of the Downloads root; document IDs are "raw:/path", "msf:123" or "downloads". */
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /** Document ID of the root of the internal storage. */
    const val PRIMARY_ROOT_DOCUMENT_ID = "primary:"

    /** Document ID of the root document of the Downloads provider. */
    const val DOWNLOADS_ROOT_DOCUMENT_ID = "downloads"

    /** Title of the Downloads root, used when the provider does not tell it. */
    const val DOWNLOADS_TITLE = "Downloads"

    private const val PRIMARY_ROOT_ID = "primary"
    private const val RAW_PREFIX = "raw:"

    /**
     * Document ID of the parent folder of [docId], or null when this provider
     * needs a different way (not a known provider) or the document has no
     * parent (a root).
     *
     * - ExternalStorageProvider: "root:" plus the parent path ("primary:A/B/f"
     *   gives "primary:A/B", "primary:f" gives "primary:").
     * - DownloadsProvider: the parent path for "raw:/path"; the root document
     *   "downloads" for everything else ("msf:123" and friends).
     */
    fun parentDocumentId(authority: String, docId: String): String? = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> externalParent(docId)
        DOWNLOADS_AUTHORITY -> downloadsParent(docId)
        else -> null
    }

    /** Whether [parentDocumentId] answers completely for [authority], so a null result means "no parent". */
    fun isKnownProvider(authority: String): Boolean =
        authority == EXTERNAL_STORAGE_AUTHORITY || authority == DOWNLOADS_AUTHORITY

    /** The parent from the path a provider reports for a document (root first, document last): the second to last element. */
    fun parentFromPath(path: List<String>): String? = if (path.size >= 2) path[path.size - 2] else null

    /**
     * Place text of a file, without the file name: "Interner Speicher / A / B"
     * for the internal storage, the root title plus the folders for another
     * ExternalStorageProvider root (SD card), the root title for any other
     * provider. Null when the root title is needed and unknown.
     *
     * @param internalStorageLabel German name of the internal storage root.
     * @param rootTitle looks up the title of the root the document belongs to; only called when needed.
     */
    fun placeText(
        authority: String,
        docId: String,
        internalStorageLabel: String,
        rootTitle: () -> String?,
    ): String? {
        val parts = when (authority) {
            EXTERNAL_STORAGE_AUTHORITY -> {
                val root = docId.substringBefore(':', "")
                val folders = pathSegments(docId.substringAfter(':', "")).dropLast(1)
                val title = if (root == PRIMARY_ROOT_ID) internalStorageLabel else rootTitle()
                if (title.isNullOrBlank()) return null
                listOf(title) + folders
            }
            DOWNLOADS_AUTHORITY -> listOf(rootTitle()?.takeIf { it.isNotBlank() } ?: DOWNLOADS_TITLE)
            else -> listOf(rootTitle()?.takeIf { it.isNotBlank() } ?: return null)
        }
        return parts.joinToString(" / ")
    }

    private fun externalParent(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val segments = pathSegments(docId.substring(colon + 1))
        if (segments.isEmpty()) return null
        return docId.substring(0, colon + 1) + segments.dropLast(1).joinToString("/")
    }

    private fun downloadsParent(docId: String): String? {
        if (docId == DOWNLOADS_ROOT_DOCUMENT_ID) return null
        if (docId.startsWith(RAW_PREFIX)) {
            val segments = pathSegments(docId.removePrefix(RAW_PREFIX))
            if (segments.size > 1) return RAW_PREFIX + "/" + segments.dropLast(1).joinToString("/")
        }
        return DOWNLOADS_ROOT_DOCUMENT_ID
    }

    private fun pathSegments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
}
