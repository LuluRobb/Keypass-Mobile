package de.lulurobb.keypassmobile.core.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties

/** Remote size and modification time of a local file at download, plus when it was downloaded. */
internal data class FileMeta(val remoteSize: Long, val remoteModified: Instant, val downloadedAt: Instant) {
    fun matches(stat: RemoteStat): Boolean = remoteSize == stat.size && remoteModified == stat.modified

    companion object {
        fun of(stat: RemoteStat, downloadedAt: Instant) = FileMeta(stat.size, stat.modified, downloadedAt)
    }
}

/** Persisted sync state. Holds no secrets, only file metadata and status. */
internal data class SyncState(
    val offline: FileMeta? = null,
    val candidate: FileMeta? = null,
    val candidateRejected: Boolean = false,
    val lastSuccessfulSync: Instant? = null,
    val lastProblem: SyncProblem? = null,
    val lastProblemAt: Instant? = null,
) {
    fun withProblem(problem: SyncProblem, at: Instant) = copy(lastProblem = problem, lastProblemAt = at)

    fun withSuccess(at: Instant) = copy(lastSuccessfulSync = at, lastProblem = null, lastProblemAt = null)

    fun withoutCandidate() = copy(candidate = null, candidateRejected = false)
}

/**
 * The local files, all in one app private directory (excluded from backup,
 * SE-08): offline copy, candidate, temporary download and the state file.
 * Files are replaced atomically (write elsewhere, then rename).
 */
internal class LocalStore(private val dir: File) {
    val offline = File(dir, "offline.kdbx")
    val candidate = File(dir, "candidate.kdbx")
    val download = File(dir, "download.tmp")
    private val stateFile = File(dir, "sync-state.properties")
    private val stateTemp = File(dir, "sync-state.properties.tmp")

    init {
        dir.mkdirs()
    }

    /** Reads the state file; missing or unreadable values count as unknown. */
    fun loadState(): SyncState {
        stateTemp.delete()
        if (!stateFile.isFile) return SyncState()
        val props = Properties()
        try {
            stateFile.inputStream().use { props.load(it) }
        } catch (e: IOException) {
            return SyncState()
        } catch (e: IllegalArgumentException) {
            return SyncState()
        }
        return SyncState(
            offline = props.meta("offline"),
            candidate = props.meta("candidate"),
            candidateRejected = props.getProperty("candidate.rejected") == "true",
            lastSuccessfulSync = props.instant("lastSuccessfulSync"),
            lastProblem = props.getProperty("lastProblem")?.let { name -> SyncProblem.entries.firstOrNull { it.name == name } },
            lastProblemAt = props.instant("lastProblemAt"),
        )
    }

    /** Writes the state file atomically. */
    fun saveState(state: SyncState) {
        val props = Properties()
        props.putMeta("offline", state.offline)
        props.putMeta("candidate", state.candidate)
        if (state.candidateRejected) props.setProperty("candidate.rejected", "true")
        state.lastSuccessfulSync?.let { props.setProperty("lastSuccessfulSync", it.toString()) }
        state.lastProblem?.let { props.setProperty("lastProblem", it.name) }
        state.lastProblemAt?.let { props.setProperty("lastProblemAt", it.toString()) }
        FileOutputStream(stateTemp).use { out ->
            props.store(out, "KeyPass Mobile sync state")
            out.flush()
            out.fd.sync()
        }
        moveAtomically(stateTemp, stateFile)
    }

    /** Metadata of [file] if it exists and has the recorded size; otherwise unknown (null). */
    fun validMeta(file: File, meta: FileMeta?): FileMeta? =
        meta?.takeIf { file.isFile && file.length() == it.remoteSize }

    /** Flushes [file] to the storage device. */
    fun syncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    /** Replaces [to] with [from] in one step; [to] is either the old or the new file, never partial. */
    fun moveAtomically(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory()
    }

    fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    /** Deletes [file] and ignores failures (cleanup of temporary files). */
    fun deleteQuietly(file: File) {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: IOException) {
            // Left over; removed before the next download.
        }
    }

    /** Makes the rename durable where the platform supports it (Linux, Android). */
    private fun syncDirectory() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Not supported for directories on Windows; best effort only.
        }
    }

    private fun Properties.meta(prefix: String): FileMeta? {
        val size = getProperty("$prefix.size")?.toLongOrNull() ?: return null
        val modified = instant("$prefix.modified") ?: return null
        val downloadedAt = instant("$prefix.downloadedAt") ?: return null
        return FileMeta(size, modified, downloadedAt)
    }

    private fun Properties.putMeta(prefix: String, meta: FileMeta?) {
        if (meta == null) return
        setProperty("$prefix.size", meta.remoteSize.toString())
        setProperty("$prefix.modified", meta.remoteModified.toString())
        setProperty("$prefix.downloadedAt", meta.downloadedAt.toString())
    }

    private fun Properties.instant(key: String): Instant? =
        getProperty(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
