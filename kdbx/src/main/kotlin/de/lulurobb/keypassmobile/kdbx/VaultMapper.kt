package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import de.lulurobb.keypassmobile.model.VaultEntry
import java.util.UUID

/** Result of [VaultMapper.map]: everything a Vault needs, nothing more. */
internal class VaultContent(
    val rootGroupName: String,
    val entries: List<VaultEntry>,
    val customIcons: Map<UUID, ByteArray>,
    val passwords: Map<UUID, ProtectedPassword>,
)

/**
 * Maps the decoded kotpass content to the app model.
 *
 * - Entries in document order: the entries of a group, then its subgroups.
 * - Left out: history entries, the recycle bin (Meta RecycleBinUUID, only
 *   when the recycle bin is enabled) and the entry templates group (Meta
 *   EntryTemplatesGroup), each including all subgroups. KeePassXC never
 *   shows these in its search either.
 * - groupPath: group names below the root group.
 * - Only custom icons that a mapped entry uses are kept; customIconId is
 *   null if the entry references an icon that does not exist.
 * - An entry whose UUID was already mapped (invalid file) is skipped, so
 *   [VaultContent.passwords] stays unambiguous.
 */
internal object VaultMapper {
    private val NIL_UUID = UUID(0L, 0L)

    /** KeePassXC "additional URLs": KP2A_URL, KP2A_URL_1, KP2A_URL_2, ... */
    private val ADDITIONAL_URL = Regex("KP2A_URL(?:_(\\d{1,9}))?")

    fun map(content: DatabaseContent): VaultContent {
        val meta = content.meta
        val excludedGroups = HashSet<UUID>()
        if (meta.recycleBinEnabled) meta.recycleBinUuid?.let(excludedGroups::add)
        meta.entryTemplatesGroup?.let(excludedGroups::add)
        excludedGroups.remove(NIL_UUID)

        val collector = Collector(excludedGroups, meta.customIcons.keys)
        val root = content.group
        collector.collect(root, emptyList())
        val icons = LinkedHashMap<UUID, ByteArray>()
        for (id in collector.usedIcons) {
            meta.customIcons[id]?.let { icons[id] = it.data }
        }
        return VaultContent(
            rootGroupName = root.name,
            entries = collector.entries,
            customIcons = icons,
            passwords = collector.passwords,
        )
    }

    private class Collector(
        private val excludedGroups: Set<UUID>,
        private val knownIcons: Set<UUID>,
    ) {
        val entries = ArrayList<VaultEntry>()
        val passwords = HashMap<UUID, ProtectedPassword>()
        val usedIcons = LinkedHashSet<UUID>()

        fun collect(group: Group, path: List<String>) {
            for (entry in group.entries) {
                if (passwords.containsKey(entry.uuid)) continue
                passwords[entry.uuid] = ProtectedPassword.of(entry.fields.password)
                entries += toVaultEntry(entry, path)
            }
            for (child in group.groups) {
                if (child.uuid in excludedGroups) continue
                collect(child, path + child.name)
            }
        }

        private fun toVaultEntry(entry: Entry, path: List<String>): VaultEntry {
            val fields = entry.fields
            val customIcon = entry.customIconUuid?.takeIf { it in knownIcons }
            if (customIcon != null) usedIcons += customIcon
            return VaultEntry(
                id = entry.uuid,
                title = fields.title?.content.orEmpty(),
                username = fields.userName?.content.orEmpty(),
                url = fields.url?.content.orEmpty(),
                additionalUrls = additionalUrls(entry),
                notes = fields.notes?.content.orEmpty(),
                tags = entry.tags.map(String::trim).filter(String::isNotEmpty),
                groupPath = path,
                iconId = entry.icon.ordinal,
                customIconId = customIcon,
            )
        }

        private fun additionalUrls(entry: Entry): List<String> {
            var found: MutableList<Pair<Int, String>>? = null
            for ((key, value) in entry.fields) {
                val match = ADDITIONAL_URL.matchEntire(key) ?: continue
                val url = value.content.trim()
                if (url.isEmpty()) continue
                val index = match.groupValues[1].toIntOrNull() ?: 0
                (found ?: ArrayList<Pair<Int, String>>().also { found = it }).add(index to url)
            }
            return found?.sortedBy { it.first }?.map { it.second } ?: emptyList()
        }
    }
}
