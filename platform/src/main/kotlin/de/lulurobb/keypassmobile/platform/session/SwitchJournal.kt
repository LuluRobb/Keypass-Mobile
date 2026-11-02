package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * What an unfinished source change has done so far, to undo it after a crash
 * (see [Session.recoverInterruptedSwitch]). Holds no secret.
 *
 * @property target The new source; null when the record could not be read (then everything is undone).
 * @property differs The new file or server differs from the source before.
 * @property setAside The old offline copy was set aside (renamed).
 * @property offlineReplaced The new file replaced the offline copy directly (it opened with the key in memory).
 * @property passwordTouched The new SMB password may have been stored.
 */
internal data class SwitchRecord(
    val target: SourceConfig?,
    val differs: Boolean,
    val setAside: Boolean,
    val offlineReplaced: Boolean,
    val passwordTouched: Boolean,
)

/** Persists the [SwitchRecord] of a running source change; no record means no change is running. */
internal interface SwitchJournal {
    fun read(): SwitchRecord?

    /** Stores [record]; null deletes it. @throws IOException when it cannot be written. */
    fun write(record: SwitchRecord?)

    /** No persistence (tests of other things). */
    object None : SwitchJournal {
        override fun read(): SwitchRecord? = null

        override fun write(record: SwitchRecord?) = Unit
    }
}

/** [SwitchJournal] in one small file in the app's private directory (SE-08). */
internal class FileSwitchJournal(private val file: File) : SwitchJournal {
    private val temp = File(file.parentFile, file.name + ".tmp")

    /** An unreadable file still means a change was running: undo conservatively. */
    override fun read(): SwitchRecord? {
        if (!file.exists()) return null
        val conservative = SwitchRecord(null, differs = true, setAside = true, offlineReplaced = false, passwordTouched = true)
        val p = Properties()
        try {
            file.inputStream().use { p.load(it) }
        } catch (_: Exception) {
            return conservative
        }
        val target = run {
            val host = p.getProperty("host") ?: return@run null
            val user = p.getProperty("user") ?: return@run null
            val share = p.getProperty("share") ?: return@run null
            val path = p.getProperty("path")?.split('/')?.filter { it.isNotEmpty() }
            if (path.isNullOrEmpty()) return@run null
            SourceConfig(host, p.getProperty("port")?.toIntOrNull() ?: 445, p.getProperty("domain") ?: "", user, share, path)
        }
        return SwitchRecord(
            target = target,
            differs = p.getProperty("differs") != "false",
            setAside = p.getProperty("setAside") == "true",
            offlineReplaced = p.getProperty("offlineReplaced") == "true",
            passwordTouched = p.getProperty("passwordTouched") != "false",
        )
    }

    override fun write(record: SwitchRecord?) {
        if (record == null) {
            Files.deleteIfExists(file.toPath())
            return
        }
        file.parentFile?.mkdirs()
        val p = Properties()
        record.target?.let {
            p.setProperty("host", it.host)
            p.setProperty("port", it.port.toString())
            p.setProperty("domain", it.domain)
            p.setProperty("user", it.user)
            p.setProperty("share", it.share)
            p.setProperty("path", it.filePath.joinToString("/"))
        }
        p.setProperty("differs", record.differs.toString())
        p.setProperty("setAside", record.setAside.toString())
        p.setProperty("offlineReplaced", record.offlineReplaced.toString())
        p.setProperty("passwordTouched", record.passwordTouched.toString())
        try {
            FileOutputStream(temp).use { out ->
                p.store(out, "KeyPass Mobile source change in progress")
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }
}
