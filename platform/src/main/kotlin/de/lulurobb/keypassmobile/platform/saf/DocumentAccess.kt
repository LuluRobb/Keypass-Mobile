package de.lulurobb.keypassmobile.platform.saf

import java.io.InputStream
import java.time.Instant

/**
 * Access to documents of the Storage Access Framework, as a seam so JVM tests
 * need neither a ContentResolver nor android.net.Uri. URIs are passed as text.
 * [ContentResolverDocuments] is the implementation on a real ContentResolver.
 */
internal interface DocumentAccess {
    /** Takes the persistable read permission for [uri]. @throws SecurityException if Android refuses it. */
    fun takeReadPermission(uri: String)

    /** Gives the persisted read permission for [uri] back. Never throws. */
    fun releaseReadPermission(uri: String)

    /** URIs that the app holds a persisted read permission for. */
    fun persistedReadUris(): Set<String>

    /**
     * Name, size and modification time of [uri]. Null means the document was not
     * found. @throws SecurityException if the app has no access.
     */
    fun query(uri: String): DocumentInfo?

    /**
     * Opens [uri] for reading. The caller closes the stream.
     * @throws java.io.FileNotFoundException if the document is gone,
     * @throws SecurityException if the app has no access,
     * @throws java.io.IOException on any other read problem.
     */
    fun open(uri: String): InputStream

    /**
     * Where the file lies, e.g. "Interner Speicher / A / B" (without the file
     * name) or the title of the provider root; null if unknown.
     */
    fun placeOf(uri: String): String?

    /** Document URI of the parent folder of [uri], for EXTRA_INITIAL_URI; null if unknown. */
    fun folderOf(uri: String): String?

    /** Document URI of the root of the internal storage (the start of the first setup). */
    fun internalStorageRoot(): String
}

/** What a provider reports about a document; each value is null when the provider does not report it. */
internal data class DocumentInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val lastModified: Instant?,
)
