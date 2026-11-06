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
            val source = run {
                val host = p.getString(HOST, null) ?: return@run null
                val user = p.getString(USER, null) ?: return@run null
                val share = p.getString(SHARE, null) ?: return@run null
                val path = p.getString(PATH, null)?.split(PATH_SEPARATOR)?.filter { it.isNotEmpty() }
                if (path.isNullOrEmpty()) return@run null
                SourceConfig(
                    host = host,
                    port = p.getInt(PORT, 445),
                    domain = p.getString(DOMAIN, "") ?: "",
                    user = user,
                    share = share,
                    filePath = path,
                )
            }
            val trustedGuid = p.getString(TRUSTED_GUID, null)?.let(::uuidOrNull)
            val intervalAtEntry = p.getString(INTERVAL_AT_ENTRY, null)
            return KpmSettings(
                source = source,
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
            e.putInt(VERSION, 1)
            val src = s.source
            if (src == null) {
                e.remove(HOST).remove(PORT).remove(DOMAIN).remove(USER).remove(SHARE).remove(PATH)
            } else {
                e.putString(HOST, src.host).putInt(PORT, src.port).putString(DOMAIN, src.domain)
                    .putString(USER, src.user).putString(SHARE, src.share)
                    .putString(PATH, src.filePath.joinToString(PATH_SEPARATOR))
            }
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
