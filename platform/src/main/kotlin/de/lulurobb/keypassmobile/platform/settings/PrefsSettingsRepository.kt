package de.lulurobb.keypassmobile.platform.settings

import android.content.Context
import android.content.SharedPreferences
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * [SettingsRepository] on a private SharedPreferences file. Reads the file once
 * at construction; writes with apply(), the in-memory state changes at once.
 */
internal class PrefsSettingsRepository(private val prefs: SharedPreferences) : SettingsRepository {

    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read(prefs))
    private val lock = Any()

    override val settings: StateFlow<KpmSettings> = state.asStateFlow()

    override fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings = synchronized(lock) {
        val next = transform(state.value)
        if (next != state.value) {
            write(prefs.edit(), next).apply()
            state.value = next
        }
        next
    }

    internal companion object {
        const val FILE_NAME = "kpm_platform_settings"

        private const val VERSION = "version"
        private const val VERSION_NUMBER = 2

        /** "smb" or "file"; missing in version 1 (SMB only). */
        private const val TYPE = "source.type"
        private const val TYPE_SMB = "smb"
        private const val TYPE_FILE = "file"
        private const val URI = "source.uri"
        private const val NAME = "source.name"
        private const val PLACE = "source.place"
        private const val LAST_FOLDER = "picker.lastFolder"
        private const val HOST = "source.host"
        private const val PORT = "source.port"
        private const val DOMAIN = "source.domain"
        private const val USER = "source.user"
        private const val SHARE = "source.share"
        private const val PATH = "source.path"
        private const val TRUSTED_GUID = "server.guid"
        private const val TRUSTED_SINCE = "server.since"
        private const val PENDING_GUID = "server.pendingGuid"
        private const val FINGERPRINT = "unlock.fingerprint"
        private const val INTERVAL = "unlock.passwordInterval"
        private const val LAST_ENTRY = "unlock.lastPasswordEntry"
        private const val INTERVAL_AT_ENTRY = "unlock.intervalAtEntry"
        private const val IDLE = "lock.idleTimeout"
        private const val SETUP_DONE = "setup.completed"
        private const val FINGERPRINT_EXPIRED = "unlock.fingerprintExpired"

        /** Separator of the path segments; cannot occur in SMB names. */
        private const val PATH_SEPARATOR = "/"

        fun read(p: SharedPreferences): KpmSettings {
            val source = readSource(p)
            val trustedGuid = p.getString(TRUSTED_GUID, null)?.let(::uuidOrNull)
            val intervalAtEntry = p.getString(INTERVAL_AT_ENTRY, null)
            return KpmSettings(
                source = source,
                lastPickedFolder = p.getString(LAST_FOLDER, null),
                trustedServer = trustedGuid?.let { TrustedServer(it, instantOrEpoch(p, TRUSTED_SINCE)) },
                pendingServerGuid = p.getString(PENDING_GUID, null)?.let(::uuidOrNull),
                fingerprintEnabled = p.getBoolean(FINGERPRINT, false),
                passwordInterval = PasswordInterval.fromName(p.getString(INTERVAL, null)),
                idleTimeout = IdleTimeout.fromName(p.getString(IDLE, null)),
                setupCompleted = p.getBoolean(SETUP_DONE, false),
                fingerprintExpired = p.getBoolean(FINGERPRINT_EXPIRED, false),
                lastPasswordEntry = if (p.contains(LAST_ENTRY)) Instant.ofEpochMilli(p.getLong(LAST_ENTRY, 0)) else null,
                // An unknown name must not silently become the default: then the password is due.
                intervalAtEntry = intervalAtEntry?.let { name -> PasswordInterval.entries.firstOrNull { it.name == name } },
            )
        }

        fun write(e: SharedPreferences.Editor, s: KpmSettings): SharedPreferences.Editor {
            e.putInt(VERSION, VERSION_NUMBER)
            writeSource(e, s.source)
            putOrRemove(e, LAST_FOLDER, s.lastPickedFolder)
            val trusted = s.trustedServer
            if (trusted == null) {
                e.remove(TRUSTED_GUID).remove(TRUSTED_SINCE)
            } else {
                e.putString(TRUSTED_GUID, trusted.guid.toString()).putLong(TRUSTED_SINCE, trusted.since.toEpochMilli())
            }
            putOrRemove(e, PENDING_GUID, s.pendingServerGuid?.toString())
            e.putBoolean(FINGERPRINT, s.fingerprintEnabled)
            e.putString(INTERVAL, s.passwordInterval.name)
            e.putString(IDLE, s.idleTimeout.name)
            e.putBoolean(SETUP_DONE, s.setupCompleted)
            e.putBoolean(FINGERPRINT_EXPIRED, s.fingerprintExpired)
            if (s.lastPasswordEntry == null) e.remove(LAST_ENTRY) else e.putLong(LAST_ENTRY, s.lastPasswordEntry.toEpochMilli())
            putOrRemove(e, INTERVAL_AT_ENTRY, s.intervalAtEntry?.name)
            return e
        }

        /**
         * A missing type with the SMB keys present is a source saved before the
         * file source existed: it reads as [SmbSource], nothing is rewritten until
         * the next update. An unknown type reads as no source.
         */
        private fun readSource(p: SharedPreferences): SourceConfig? = when (p.getString(TYPE, null)) {
            null, TYPE_SMB -> readSmb(p)
            TYPE_FILE -> readFile(p)
            else -> null
        }

        private fun readSmb(p: SharedPreferences): SmbSource? {
            val host = p.getString(HOST, null) ?: return null
            val user = p.getString(USER, null) ?: return null
            val share = p.getString(SHARE, null) ?: return null
            val path = p.getString(PATH, null)?.split(PATH_SEPARATOR)?.filter { it.isNotEmpty() }
            if (path.isNullOrEmpty()) return null
            return SmbSource(
                host = host,
                port = p.getInt(PORT, 445),
                domain = p.getString(DOMAIN, "") ?: "",
                user = user,
                share = share,
                filePath = path,
            )
        }

        private fun readFile(p: SharedPreferences): FileSource? {
            val uri = p.getString(URI, null)?.takeIf { it.isNotEmpty() } ?: return null
            val name = p.getString(NAME, null)?.takeIf { it.isNotEmpty() } ?: return null
            return FileSource(uri, name, p.getString(PLACE, null))
        }

        /** Writes the keys of one kind and removes those of the other (exactly one source). */
        private fun writeSource(e: SharedPreferences.Editor, src: SourceConfig?) {
            when (src) {
                null -> {
                    e.remove(TYPE)
                    removeSmb(e)
                    removeFile(e)
                }
                is SmbSource -> {
                    removeFile(e)
                    e.putString(TYPE, TYPE_SMB)
                    e.putString(HOST, src.host).putInt(PORT, src.port).putString(DOMAIN, src.domain)
                        .putString(USER, src.user).putString(SHARE, src.share)
                        .putString(PATH, src.filePath.joinToString(PATH_SEPARATOR))
                }
                is FileSource -> {
                    removeSmb(e)
                    e.putString(TYPE, TYPE_FILE)
                    e.putString(URI, src.uri).putString(NAME, src.displayName)
                    putOrRemove(e, PLACE, src.place)
                }
            }
        }

        private fun removeSmb(e: SharedPreferences.Editor) {
            e.remove(HOST).remove(PORT).remove(DOMAIN).remove(USER).remove(SHARE).remove(PATH)
        }

        private fun removeFile(e: SharedPreferences.Editor) {
            e.remove(URI).remove(NAME).remove(PLACE)
        }

        private fun putOrRemove(e: SharedPreferences.Editor, key: String, value: String?) {
            if (value == null) e.remove(key) else e.putString(key, value)
        }

        private fun uuidOrNull(text: String): UUID? = try {
            UUID.fromString(text)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun instantOrEpoch(p: SharedPreferences, key: String): Instant = Instant.ofEpochMilli(p.getLong(key, 0))
    }
}

/** In-memory [SettingsRepository], for tests and as fallback. */
internal class MemorySettingsRepository(initial: KpmSettings = KpmSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    private val lock = Any()

    override val settings: StateFlow<KpmSettings> = state.asStateFlow()

    override fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings = synchronized(lock) {
        transform(state.value).also { state.value = it }
    }
}
