package de.lulurobb.keypassmobile.core.match

import de.lulurobb.keypassmobile.core.entry
import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.vaultOf
import de.lulurobb.keypassmobile.model.FillTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the prototype EntryMatcherTest, now with registrable domains from the Public Suffix List. */
class EntryMatcherTest {

    private fun web(host: String) = FillTarget.Web(host, "com.brave.browser")

    private fun app(label: String?) = FillTarget.App("com.example.app", label)

    private fun webMatches(pageHost: String, url: String, vararg more: String) =
        EntryMatcher.matches(entry("E", url = url, additionalUrls = more.toList()), web(pageHost))

    @Test
    fun domainMatchesSameDomainAndSubdomains() {
        assertTrue(webMatches("paypal.com", "https://www.paypal.com"))
        assertTrue(webMatches("www.paypal.com", "https://paypal.com/signin"))
        assertTrue(webMatches("login.paypal.com", "https://www.paypal.com"))
        assertTrue(webMatches("paypal.com", "https://login.paypal.com"))
        assertTrue(webMatches("paypal.com", "paypal.com/login"))
        assertTrue(webMatches("WWW.PayPal.com.", "https://www.paypal.com"))
        assertTrue(webMatches("news.bbc.co.uk", "https://www.bbc.co.uk/account"))
        assertTrue(webMatches("10.0.2.2", "http://10.0.2.2"))
        assertTrue(webMatches("localhost", "http://localhost:8080"))
        assertTrue(webMatches("[::1]", "http://[::1]:8080/"))
        assertTrue(webMatches("www.münchen.de", "https://xn--mnchen-3ya.de"))
    }

    @Test
    fun domainDoesNotMatchLookalikes() {
        assertFalse(webMatches("paypal.com.evil.example", "https://www.paypal.com"))
        assertFalse(webMatches("evilpaypal.com", "https://paypal.com"))
        assertFalse(webMatches("example.com", "https://www.paypal.com"))
        assertFalse(webMatches("com", "https://paypal.com"))
        assertFalse(webMatches("2.10.0.2.2", "http://10.0.2.2"))
        assertFalse(webMatches("192.168.1.10", "http://10.0.2.2"))
        assertFalse(webMatches("evil.co.uk", "https://www.bbc.co.uk"))
        // Private section of the list: different GitHub Pages users are different sites.
        assertFalse(webMatches("bob.github.io", "https://alice.github.io"))
        assertFalse(webMatches("github.io", "https://alice.github.io"))
        assertFalse(webMatches("paypal.com", ""))
        assertFalse(webMatches("paypal.com", "androidapp://com.paypal.android.p2pmobile"))
    }

    @Test
    fun additionalUrlsCount() {
        assertTrue(webMatches("signin.ebay.de", "https://www.ebay.com", "https://www.ebay.de"))
        assertFalse(webMatches("signin.ebay.at", "https://www.ebay.com", "https://www.ebay.de"))
    }

    @Test
    fun phishingCheckWarnsForForeignDomainsAndEntriesWithoutUrl() {
        val page = web("www.paypal.com")
        assertFalse(EntryMatcher.isDomainMismatch(entry("PayPal", url = "https://paypal.com"), page))
        assertTrue(EntryMatcher.isDomainMismatch(entry("PayPal", url = "https://paypal.de"), page))
        assertTrue(EntryMatcher.isDomainMismatch(entry("PayPal"), page))
        assertTrue(EntryMatcher.isDomainMismatch(entry("PayPal", url = "Mein Konto"), page))
    }

    @Test
    fun displayDomainForWarningDialog() {
        assertEquals("paypal.com", EntryMatcher.displayDomain(entry("P", url = "https://login.paypal.com/x")))
        assertEquals("ebay.de", EntryMatcher.displayDomain(entry("E", url = "", additionalUrls = listOf("ebay.de/login"))))
        assertEquals("10.0.2.2", EntryMatcher.displayDomain(entry("R", url = "http://10.0.2.2:8080")))
        assertEquals("localhost", EntryMatcher.displayDomain(entry("L", url = "http://localhost:3000")))
        assertNull(EntryMatcher.displayDomain(entry("N")))
        assertEquals("evil.example", EntryMatcher.displayDomain(web("login.paypal.com.evil.example")))
        assertEquals("paypal.com", EntryMatcher.displayDomain(web("WWW.PAYPAL.COM")))
    }

    @Test
    fun appNameMatchesTitleOrUrl() {
        assertTrue(EntryMatcher.matches(entry("PayPal"), app("PayPal")))
        assertTrue(EntryMatcher.matches(entry("Konto", url = "https://www.paypal.com"), app("PayPal Business")))
        assertTrue(EntryMatcher.matches(entry("KPM Prototyp (Testformular)"), app("KPM Prototyp")))
        assertTrue(EntryMatcher.matches(entry("Outlook"), app("Microsoft Outlook")))
        assertTrue(EntryMatcher.matches(entry("Girokonto", url = "sparkasse-koelnbonn.de"), app("Sparkasse")))
        assertTrue(EntryMatcher.matches(entry("Mein ING Konto"), app("ING")))
        assertTrue(EntryMatcher.matches(entry("Shop", url = "https://www.mueller.de"), app("Müller")))
        assertTrue(EntryMatcher.matches(entry("Shop", url = "https://www.müller.de"), app("Mueller")))
        assertFalse(EntryMatcher.matches(entry("PayPal", url = "https://www.paypal.com"), app("Brave")))
        assertFalse(EntryMatcher.matches(entry("Booking.com", url = "https://www.booking.com"), app("ING")))
        assertFalse(EntryMatcher.matches(entry("Router", url = "http://192.168.178.1"), app("FRITZ!App")))
        assertFalse(EntryMatcher.matches(entry("PayPal"), app(null)))
        assertFalse(EntryMatcher.matches(entry("X"), app("X")))
        assertFalse(EntryMatcher.matches(entry("Ab"), app("Ab")))
    }

    @Test
    fun indexMatchingReturnsMatchesAtoZ() {
        val index = EntryIndex(
            vaultOf(
                listOf(
                    entry("Testseite localhost", url = "http://localhost:8080"),
                    entry("PayPal privat", url = "https://www.paypal.com"),
                    entry("Example", url = "https://example.com"),
                    entry("Altes PayPal", url = "paypal.com/login"),
                    entry("PayPal geschäftlich", url = "https://business.paypal.com"),
                    entry("Testseite Emulator", url = "http://10.0.2.2:8080/login.html"),
                ),
            ),
        )
        assertEquals(
            listOf("Altes PayPal", "PayPal geschäftlich", "PayPal privat"),
            index.matching(web("www.paypal.com")).map { it.title },
        )
        assertEquals(listOf("Testseite Emulator"), index.matching(web("10.0.2.2")).map { it.title })
        assertEquals(
            listOf("Altes PayPal", "PayPal geschäftlich", "PayPal privat"),
            index.matching(app("PayPal")).map { it.title },
        )
        assertEquals(emptyList(), index.matching(app("Brave")))
        assertEquals(emptyList(), index.matching(web("evil.example")))
    }
}
