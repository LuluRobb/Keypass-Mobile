package de.lulurobb.keypassmobile.model

import java.util.UUID

/**
 * Unlocked database content. Exists only in memory while the app is unlocked
 * (SY-01, SY-02). Holds no passwords itself: they stay in the protected form
 * of the KDBX library and are revealed per entry only when needed (SE-04).
 *
 * Entries in the recycle bin and history entries are not part of [entries].
 */
class Vault(
    /** Name of the root group, used as section title for entries directly in the root. */
    val rootGroupName: String,
    val entries: List<VaultEntry>,
    /** Own icons stored in the KDBX (usually PNG bytes), keyed by icon UUID. */
    val customIcons: Map<UUID, ByteArray>,
    private val secrets: VaultSecrets,
) {
    /**
     * Returns a fresh copy of the entry password. The caller overwrites the
     * array (fill('\u0000')) right after use. Throws after [close].
     */
    fun revealPassword(entryId: UUID): CharArray = secrets.password(entryId)

    /** Drops all references to decrypted data. Called on lock. */
    fun close() = secrets.close()
}

/** Access to protected values; implemented by :kdbx. */
interface VaultSecrets {
    fun password(entryId: UUID): CharArray
    fun close()
}

/**
 * One entry as shown and searched in the app. Plain strings cannot be wiped,
 * so all references are dropped on lock. Deliberately not a data class, so no
 * generated toString ends up in logs (SE-07).
 */
class VaultEntry(
    val id: UUID,
    val title: String,
    val username: String,
    val url: String,
    /** KeePassXC "additional URLs" (attributes KP2A_URL, KP2A_URL_1, ...). */
    val additionalUrls: List<String>,
    val notes: String,
    val tags: List<String>,
    /** Group path below the root group, e.g. ["Finanzen", "Geschäftlich"]. Empty: entry is in the root group. */
    val groupPath: List<String>,
    /** KeePass standard icon number (0 to 68). */
    val iconId: Int,
    /** Own icon from [Vault.customIcons], overrides [iconId] when set and present. */
    val customIconId: UUID?,
) {
    override fun toString(): String = "VaultEntry($id)"
}
