package de.reitano.keypassmobile.prototype.autofill

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryMatcherTest {

    private fun entry(title: String, url: String?) = AutofillEntry(title, "user", "secret", url)

    @Test
    fun hostOfStripsSchemePortPathAndUserInfo() {
        assertEquals("www.example.com", EntryMatcher.hostOf("https://user@WWW.Example.com:8443/login?x=1#top"))
        assertEquals("example.com", EntryMatcher.hostOf("example.com/login"))
        assertEquals("10.0.2.2", EntryMatcher.hostOf("http://10.0.2.2:8080/login.html"))
        assertEquals("::1", EntryMatcher.hostOf("http://[::1]:8080/"))
        assertNull(EntryMatcher.hostOf(""))
        assertNull(EntryMatcher.hostOf(null))
    }

    @Test
    fun domainMatchesSameDomainAndSubdomains() {
        assertTrue(EntryMatcher.domainMatches("paypal.com", "https://www.paypal.com"))
        assertTrue(EntryMatcher.domainMatches("www.paypal.com", "https://paypal.com/signin"))
        assertTrue(EntryMatcher.domainMatches("login.paypal.com", "https://www.paypal.com"))
        assertTrue(EntryMatcher.domainMatches("paypal.com", "https://login.paypal.com"))
        assertTrue(EntryMatcher.domainMatches("10.0.2.2", "http://10.0.2.2"))
        assertTrue(EntryMatcher.domainMatches("localhost", "http://localhost:8080"))
    }

    @Test
    fun domainDoesNotMatchLookalikes() {
        assertFalse(EntryMatcher.domainMatches("paypal.com.evil.example", "https://www.paypal.com"))
        assertFalse(EntryMatcher.domainMatches("evilpaypal.com", "https://paypal.com"))
        assertFalse(EntryMatcher.domainMatches("example.com", "https://www.paypal.com"))
        assertFalse(EntryMatcher.domainMatches("com", "https://paypal.com"))
        assertFalse(EntryMatcher.domainMatches("2.10.0.2.2", "http://10.0.2.2"))
        assertFalse(EntryMatcher.domainMatches("192.168.1.10", "http://10.0.2.2"))
        assertFalse(EntryMatcher.domainMatches("paypal.com", null))
    }

    @Test
    fun appNameMatchesTitleOrUrl() {
        assertTrue(EntryMatcher.appNameMatches("PayPal", entry("PayPal", null)))
        assertTrue(EntryMatcher.appNameMatches("PayPal Business", entry("Konto", "https://www.paypal.com")))
        assertTrue(EntryMatcher.appNameMatches("KPM Prototyp", entry("KPM Prototyp (Testformular)", null)))
        assertFalse(EntryMatcher.appNameMatches("Brave", entry("PayPal", "https://www.paypal.com")))
        assertFalse(EntryMatcher.appNameMatches(null, entry("PayPal", null)))
        assertFalse(EntryMatcher.appNameMatches("X", entry("X", null)))
    }

    @Test
    fun rankPutsMatchesFirstAndKeepsOrder() {
        val ranked = EntryMatcher.rank(TestEntries.ALL, webDomain = "10.0.2.2", appName = null)
        assertEquals("Testseite Emulator", ranked.first().entry.title)
        assertEquals(listOf(true, false, false, false, false), ranked.map { it.matches })
        assertEquals(
            listOf("Testseite localhost", "Example", "PayPal", "KPM Prototyp (Testformular)"),
            ranked.drop(1).map { it.entry.title },
        )
    }

    @Test
    fun rankUsesAppNameWithoutWebDomain() {
        val ranked = EntryMatcher.rank(TestEntries.ALL, webDomain = null, appName = "KPM Prototyp")
        assertEquals("KPM Prototyp (Testformular)", ranked.first().entry.title)
        assertEquals(1, ranked.count { it.matches })
    }

    @Test
    fun entryToStringHidesPassword() {
        assertFalse("secret" in entry("PayPal", null).toString())
    }
}
