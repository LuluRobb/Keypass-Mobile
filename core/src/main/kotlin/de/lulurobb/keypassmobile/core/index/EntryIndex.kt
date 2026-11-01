package de.lulurobb.keypassmobile.core.index

import de.lulurobb.keypassmobile.core.match.EntryMatcher
import de.lulurobb.keypassmobile.core.text.foldForSearch
import de.lulurobb.keypassmobile.core.text.splitAtWhitespace
import de.lulurobb.keypassmobile.model.EntrySection
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultEntry
import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/**
 * Entry list, full text search and autofill matching over one unlocked
 * [Vault] (UI-01, AF-05). Built once after unlock and kept in memory only;
 * nothing of it is ever written to disk (SY-01). Drop the index together with
 * the vault on lock, it holds copies of entry texts (SE-04).
 *
 * Sorting is A-Z by title, then username, with German collation. Immutable
 * after construction and safe to use from several threads.
 */
class EntryIndex(vault: Vault) {

    private class Item(
        val entry: VaultEntry,
        /** Folded title, username, URLs, notes, tags and group path, separated by [FIELD_SEPARATOR]. */
        val searchText: String,
        val foldedTitle: String,
        val topGroup: String?,
    )

    /** All entries in A-Z order. */
    private val items: List<Item>
    private val sectionList: List<EntrySection>
    private val topGroupList: List<String>

    /** Domains and names for matching, computed on first use (needs the Public Suffix List). */
    private val matchData: List<EntryMatcher.MatchData> by lazy {
        items.map { EntryMatcher.matchDataOf(it.entry) }
    }

    init {
        // A fresh instance per index: Collator is not guaranteed to be thread safe.
        val collator = Collator.getInstance(Locale.GERMAN)
        val names = Comparator<String> { a, b ->
            val c = collator.compare(a, b)
            if (c != 0) c else a.compareTo(b)
        }

        class Keyed(val entry: VaultEntry, val titleKey: CollationKey)

        val sorted = vault.entries
            .map { Keyed(it, collator.getCollationKey(it.title)) }
            .sortedWith(
                compareBy<Keyed> { it.titleKey }
                    .thenComparator { a, b -> names.compare(a.entry.title, b.entry.title) }
                    .thenComparator { a, b -> names.compare(a.entry.username, b.entry.username) }
                    .thenBy { it.entry.id },
            )
            .map { it.entry }

        items = sorted.map { entry ->
            Item(
                entry = entry,
                searchText = searchTextOf(entry),
                foldedTitle = foldForSearch(entry.title),
                topGroup = entry.groupPath.firstOrNull(),
            )
        }

        val byPath = LinkedHashMap<List<String>, MutableList<VaultEntry>>()
        for (item in items) byPath.getOrPut(item.entry.groupPath) { ArrayList() } += item.entry
        // Tree order: element-wise comparison, a parent path sorts before its subgroups.
        val pathOrder = Comparator<List<String>> { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                val c = names.compare(a[i], b[i])
                if (c != 0) return@Comparator c
            }
            a.size - b.size
        }
        sectionList = byPath.entries
            .sortedWith { a, b -> pathOrder.compare(a.key, b.key) }
            .map { EntrySection(it.key, it.value) }

        topGroupList = items.mapNotNull { it.topGroup }.distinct().sortedWith(names)
    }

    /** Distinct top level groups for the filter above the list, A-Z. Entries in the root group have none. */
    fun topGroups(): List<String> = topGroupList

    /**
     * The list for UI-01: one section per group path that has entries. The
     * root group comes first, then all paths in tree order (subgroups right
     * after their parent, siblings A-Z). Entries within a section are A-Z.
     *
     * @param topGroup only sections below this top level group (see [topGroups]);
     *   null for all sections including the root group.
     */
    fun sections(topGroup: String? = null): List<EntrySection> =
        if (topGroup == null) sectionList else sectionList.filter { it.groupPath.firstOrNull() == topGroup }

    /**
     * Full text search as a flat list (UI-01). Case and accent insensitive over
     * title, username, URL, additional URLs, notes, tags and group path. Several
     * words separated by spaces must all occur (in any field). Entries whose
     * title starts with the first word come first, then the rest; both parts A-Z.
     * A blank query returns all entries A-Z.
     *
     * @param topGroup restricts the result to entries below this top level group.
     */
    fun search(query: String, topGroup: String? = null): List<VaultEntry> {
        val words = splitAtWhitespace(foldForSearch(query))
        val first = words.firstOrNull()
        val titleHits = ArrayList<VaultEntry>()
        val otherHits = ArrayList<VaultEntry>()
        for (item in items) {
            if (topGroup != null && item.topGroup != topGroup) continue
            if (!containsAll(item.searchText, words)) continue
            if (first != null && item.foldedTitle.startsWith(first)) titleHits += item.entry else otherHits += item.entry
        }
        titleHits += otherHits
        return titleHits
    }

    /**
     * Entries that belong to the autofill [target] (AF-05), A-Z. Shown above
     * the full list in the autofill window. See [EntryMatcher] for the rules.
     */
    fun matching(target: FillTarget): List<VaultEntry> {
        val prepared = EntryMatcher.prepareTarget(target)
        val data = matchData
        val result = ArrayList<VaultEntry>()
        for (i in items.indices) {
            if (data[i].matches(prepared)) result += items[i].entry
        }
        return result
    }

    private companion object {
        /** Not produced by query splitting, so a search word never spans two fields. */
        const val FIELD_SEPARATOR = '\u0001'

        fun searchTextOf(entry: VaultEntry): String {
            val text = StringBuilder(
                entry.title.length + entry.username.length + entry.url.length + entry.notes.length + 64,
            )
            fun add(value: String) {
                if (value.isEmpty()) return
                if (text.isNotEmpty()) text.append(FIELD_SEPARATOR)
                text.append(value)
            }
            add(entry.title)
            add(entry.username)
            add(entry.url)
            entry.additionalUrls.forEach(::add)
            add(entry.notes)
            entry.tags.forEach(::add)
            add(entry.groupPath.joinToString("/"))
            return foldForSearch(text.toString())
        }

        fun containsAll(text: String, words: List<String>): Boolean {
            for (word in words) if (!text.contains(word)) return false
            return true
        }
    }
}
