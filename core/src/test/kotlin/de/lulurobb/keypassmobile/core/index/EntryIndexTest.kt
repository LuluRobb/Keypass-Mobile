package de.lulurobb.keypassmobile.core.index

import de.lulurobb.keypassmobile.core.entry
import de.lulurobb.keypassmobile.core.match.Domains
import de.lulurobb.keypassmobile.core.vaultOf
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryIndexTest {

    private fun titles(entries: List<VaultEntry>) = entries.map { it.title }

    @Test
    fun topGroupsAreDistinctWithGermanCollation() {
        val index = EntryIndex(
            vaultOf(
                listOf(
                    entry("a", group = listOf("Zahnarzt")),
                    entry("b", group = listOf("Ärzte", "Hausarzt")),
                    entry("c", group = listOf("banken")),
                    entry("d", group = listOf("Apps")),
                    entry("e", group = listOf("Ärzte")),
                    entry("root"),
                ),
            ),
        )
        assertEquals(listOf("Apps", "Ärzte", "banken", "Zahnarzt"), index.topGroups())
    }

    @Test
    fun sectionsStartWithRootThenFollowTheTree() {
        val index = EntryIndex(
            vaultOf(
                listOf(
                    entry("Versicherung 1", group = listOf("Finanzen", "Versicherung")),
                    entry("Sparkasse", group = listOf("Finanzen", "Banken")),
                    entry("Steuer", group = listOf("Finanzen")),
                    entry("Router"),
                    entry("Praxis", group = listOf("Ärzte")),
                    entry("Jira", group = listOf("Arbeit")),
                    entry("Zahnarzt", group = listOf("Ärzte", "Zähne")),
                ),
            ),
        )
        assertEquals(
            listOf(
                emptyList(),
                listOf("Arbeit"),
                listOf("Ärzte"),
                listOf("Ärzte", "Zähne"),
                listOf("Finanzen"),
                listOf("Finanzen", "Banken"),
                listOf("Finanzen", "Versicherung"),
            ),
            index.sections().map { it.groupPath },
        )
        assertEquals(
            listOf(listOf("Finanzen"), listOf("Finanzen", "Banken"), listOf("Finanzen", "Versicherung")),
            index.sections("Finanzen").map { it.groupPath },
        )
        assertEquals(emptyList(), index.sections("Gibt es nicht"))
    }

    @Test
    fun entriesAreSortedByTitleThenUsername() {
        val index = EntryIndex(
            vaultOf(
                listOf(
                    entry("Zoo"),
                    entry("Konto", username = "zora"),
                    entry("Äpfel"),
                    entry("Bank"),
                    entry("apfel"),
                    entry("Konto", username = "anna"),
                ),
            ),
        )
        val section = index.sections().single()
        assertEquals(listOf("apfel", "Äpfel", "Bank", "Konto", "Konto", "Zoo"), titles(section.entries))
        assertEquals(listOf("anna", "zora"), section.entries.filter { it.title == "Konto" }.map { it.username })
    }

    private val searchIndex = EntryIndex(
        vaultOf(
            listOf(
                entry("PayPal", username = "luca@example.com", url = "https://www.paypal.com", group = listOf("Finanzen")),
                entry("Mein PayPal Business", username = "firma", url = "paypal.com", group = listOf("Arbeit")),
                entry("Müller Drogerie", username = "kunde42", url = "https://www.mueller.de", tags = listOf("Einkauf")),
                entry("eBay", username = "verkaeufer", url = "https://www.ebay.com", additionalUrls = listOf("https://www.ebay.de")),
                entry("Router", notes = "Fritzbox im Keller, Admin-Zugang", url = "http://192.168.178.1"),
                entry("Sparkasse", username = "abc", group = listOf("Finanzen", "Banken")),
                entry("Steuer", username = "def", group = listOf("Finanzen")),
            ),
        ),
    )

    @Test
    fun searchCoversAllFieldsCaseAndAccentInsensitive() {
        assertEquals(listOf("PayPal", "Mein PayPal Business"), titles(searchIndex.search("PAYPAL")))
        assertEquals(listOf("PayPal"), titles(searchIndex.search("luca@")))
        assertEquals(listOf("eBay"), titles(searchIndex.search("ebay.de")))
        assertEquals(listOf("Router"), titles(searchIndex.search("keller")))
        assertEquals(listOf("Müller Drogerie"), titles(searchIndex.search("einkauf")))
        assertEquals(listOf("Müller Drogerie"), titles(searchIndex.search("muller")))
        assertEquals(listOf("Müller Drogerie"), titles(searchIndex.search("MÜLLER")))
        assertEquals(listOf("Sparkasse"), titles(searchIndex.search("banken")))
        assertEquals(listOf("Sparkasse"), titles(searchIndex.search("finanzen/banken")))
        assertEquals(emptyList(), titles(searchIndex.search("nichtvorhanden")))
    }

    @Test
    fun severalWordsMustAllMatch() {
        assertEquals(listOf("Mein PayPal Business"), titles(searchIndex.search("paypal firma")))
        assertEquals(listOf("Mein PayPal Business"), titles(searchIndex.search("  firma   paypal ")))
        assertEquals(emptyList(), titles(searchIndex.search("paypal keller")))
        // A word never spans two fields (title "Sparkasse" + username "abc").
        assertEquals(emptyList(), titles(searchIndex.search("kasseabc")))
    }

    @Test
    fun titlePrefixHitsComeFirst() {
        val index = EntryIndex(
            vaultOf(
                listOf(
                    entry("Altes Konto", notes = "bank"),
                    entry("Bank Zwei"),
                    entry("Deutsche Bank"),
                    entry("Bank Eins"),
                ),
            ),
        )
        assertEquals(listOf("Bank Eins", "Bank Zwei", "Altes Konto", "Deutsche Bank"), titles(index.search("bank")))
    }

    @Test
    fun searchFiltersByTopGroupAndBlankQueryReturnsAll() {
        assertEquals(listOf("eBay", "Müller Drogerie", "Router", "Sparkasse"), titles(searchIndex.search("k")))
        assertEquals(listOf("Sparkasse"), titles(searchIndex.search("k", topGroup = "Finanzen")))
        assertEquals(listOf("PayPal", "Sparkasse", "Steuer"), titles(searchIndex.search("", topGroup = "Finanzen")))
        assertEquals(7, searchIndex.search("   ").size)
        assertEquals(
            listOf("eBay", "Mein PayPal Business", "Müller Drogerie", "PayPal", "Router", "Sparkasse", "Steuer"),
            titles(searchIndex.search("")),
        )
    }

    /** UI-01 and section 8: several thousand entries stay fluid. */
    @Test
    fun searchOver5000EntriesIsFast() {
        val random = Random(42)
        val words = listOf(
            "Bank", "Konto", "Mail", "Shop", "Versicherung", "Arzt", "Steuer", "Forum", "Router", "Cloud",
            "Spiel", "Reise", "Strom", "Müller", "Zeitung", "Verein", "Schule", "Handy", "Server", "Büro",
        )
        val groups = listOf("Finanzen", "Privat", "Arbeit", "Einkaufen", "Technik", "Ärzte")
        val entries = (0 until 5000).map { i ->
            val word = words[random.nextInt(words.size)]
            val other = words[random.nextInt(words.size)]
            entry(
                title = "$word $other $i",
                username = "user$i@example.com",
                url = "https://www.${word.lowercase()}$i.de/login",
                additionalUrls = if (i % 10 == 0) listOf("https://alt.${other.lowercase()}$i.com") else emptyList(),
                notes = (0 until 12).joinToString(" ") { words[random.nextInt(words.size)] + random.nextInt(1000) },
                tags = listOf(other),
                group = listOf(groups[random.nextInt(groups.size)], "Sub${random.nextInt(5)}"),
            )
        }
        var start = System.nanoTime()
        EntryIndex(vaultOf(entries))
        println("EntryIndex: cold build with 5000 entries took %.1f ms".format((System.nanoTime() - start) / 1e6))
        start = System.nanoTime()
        val index = EntryIndex(vaultOf(entries))
        println("EntryIndex: second build took %.1f ms".format((System.nanoTime() - start) / 1e6))
        start = System.nanoTime()
        Domains.preload()
        println("EntryIndex: loading the Public Suffix List took %.1f ms".format((System.nanoTime() - start) / 1e6))
        start = System.nanoTime()
        index.matching(FillTarget.Web("www.bank17.de", "com.brave.browser"))
        println("EntryIndex: first matching (computes domains) took %.1f ms".format((System.nanoTime() - start) / 1e6))

        val queries = listOf("bank", "müller konto", "e", "user4711", "nichtvorhanden", "finanzen sub3 strom", "BANK 1")
        repeat(30) { queries.forEach { index.search(it) } }

        for (query in queries) {
            val times = (0 until 25).map {
                val t = System.nanoTime()
                index.search(query)
                (System.nanoTime() - t) / 1e6
            }.sorted()
            val median = times[times.size / 2]
            val p90 = times[(times.size * 9) / 10]
            println(
                "EntryIndex: search \"%s\" -> %d hits, median %.2f ms, p90 %.2f ms, max %.2f ms"
                    .format(query, index.search(query).size, median, p90, times.last()),
            )
            assertTrue(median < 20.0, "median search time for \"$query\" was $median ms")
            assertTrue(p90 < 20.0, "p90 search time for \"$query\" was $p90 ms")
        }

        val matchTimes = (0 until 25).map {
            val t = System.nanoTime()
            index.matching(FillTarget.Web("login.bank17.de", "com.brave.browser"))
            (System.nanoTime() - t) / 1e6
        }.sorted()
        println("EntryIndex: matching median %.2f ms".format(matchTimes[matchTimes.size / 2]))
        assertTrue(matchTimes[matchTimes.size / 2] < 20.0)
    }
}
