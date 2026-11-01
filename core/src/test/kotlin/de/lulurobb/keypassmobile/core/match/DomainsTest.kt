package de.lulurobb.keypassmobile.core.match

import java.net.IDN
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainsTest {

    private fun checkPublicSuffix(host: String, expected: String?) {
        val expectedAscii = expected?.let { IDN.toASCII(it) }
        assertEquals(expectedAscii, Domains.registrableDomain(host), "registrable domain of $host")
    }

    /** Test vectors from https://github.com/publicsuffix/list/blob/main/tests/test_psl.txt */
    @Test
    fun officialPublicSuffixTestVectors() {
        // Mixed case.
        checkPublicSuffix("COM", null)
        checkPublicSuffix("example.COM", "example.com")
        checkPublicSuffix("WwW.example.COM", "example.com")
        // Leading dot.
        checkPublicSuffix(".com", null)
        checkPublicSuffix(".example", null)
        checkPublicSuffix(".example.com", null)
        checkPublicSuffix(".example.example", null)
        // Unlisted TLD.
        checkPublicSuffix("example", null)
        checkPublicSuffix("example.example", "example.example")
        checkPublicSuffix("b.example.example", "example.example")
        checkPublicSuffix("a.b.example.example", "example.example")
        // TLD with only 1 rule.
        checkPublicSuffix("biz", null)
        checkPublicSuffix("domain.biz", "domain.biz")
        checkPublicSuffix("b.domain.biz", "domain.biz")
        checkPublicSuffix("a.b.domain.biz", "domain.biz")
        // TLD with some 2-level rules.
        checkPublicSuffix("com", null)
        checkPublicSuffix("example.com", "example.com")
        checkPublicSuffix("b.example.com", "example.com")
        checkPublicSuffix("a.b.example.com", "example.com")
        checkPublicSuffix("uk.com", null)
        checkPublicSuffix("example.uk.com", "example.uk.com")
        checkPublicSuffix("b.example.uk.com", "example.uk.com")
        checkPublicSuffix("a.b.example.uk.com", "example.uk.com")
        checkPublicSuffix("test.ac", "test.ac")
        // TLD with only 1 (wildcard) rule.
        checkPublicSuffix("mm", null)
        checkPublicSuffix("c.mm", null)
        checkPublicSuffix("b.c.mm", "b.c.mm")
        checkPublicSuffix("a.b.c.mm", "b.c.mm")
        // More complex TLD.
        checkPublicSuffix("jp", null)
        checkPublicSuffix("test.jp", "test.jp")
        checkPublicSuffix("www.test.jp", "test.jp")
        checkPublicSuffix("ac.jp", null)
        checkPublicSuffix("test.ac.jp", "test.ac.jp")
        checkPublicSuffix("www.test.ac.jp", "test.ac.jp")
        checkPublicSuffix("kyoto.jp", null)
        checkPublicSuffix("test.kyoto.jp", "test.kyoto.jp")
        checkPublicSuffix("ide.kyoto.jp", null)
        checkPublicSuffix("b.ide.kyoto.jp", "b.ide.kyoto.jp")
        checkPublicSuffix("a.b.ide.kyoto.jp", "b.ide.kyoto.jp")
        checkPublicSuffix("c.kobe.jp", null)
        checkPublicSuffix("b.c.kobe.jp", "b.c.kobe.jp")
        checkPublicSuffix("a.b.c.kobe.jp", "b.c.kobe.jp")
        checkPublicSuffix("city.kobe.jp", "city.kobe.jp")
        checkPublicSuffix("www.city.kobe.jp", "city.kobe.jp")
        // TLD with a wildcard rule and exceptions.
        checkPublicSuffix("ck", null)
        checkPublicSuffix("test.ck", null)
        checkPublicSuffix("b.test.ck", "b.test.ck")
        checkPublicSuffix("a.b.test.ck", "b.test.ck")
        checkPublicSuffix("www.ck", "www.ck")
        checkPublicSuffix("www.www.ck", "www.ck")
        // US K12.
        checkPublicSuffix("us", null)
        checkPublicSuffix("test.us", "test.us")
        checkPublicSuffix("www.test.us", "test.us")
        checkPublicSuffix("ak.us", null)
        checkPublicSuffix("test.ak.us", "test.ak.us")
        checkPublicSuffix("www.test.ak.us", "test.ak.us")
        checkPublicSuffix("k12.ak.us", null)
        checkPublicSuffix("test.k12.ak.us", "test.k12.ak.us")
        checkPublicSuffix("www.test.k12.ak.us", "test.k12.ak.us")
        // IDN labels.
        checkPublicSuffix("食狮.com.cn", "食狮.com.cn")
        checkPublicSuffix("食狮.公司.cn", "食狮.公司.cn")
        checkPublicSuffix("www.食狮.公司.cn", "食狮.公司.cn")
        checkPublicSuffix("shishi.公司.cn", "shishi.公司.cn")
        checkPublicSuffix("公司.cn", null)
        checkPublicSuffix("食狮.中国", "食狮.中国")
        checkPublicSuffix("www.食狮.中国", "食狮.中国")
        checkPublicSuffix("shishi.中国", "shishi.中国")
        checkPublicSuffix("中国", null)
        // Same as above, but punycoded.
        checkPublicSuffix("xn--85x722f.com.cn", "xn--85x722f.com.cn")
        checkPublicSuffix("xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        checkPublicSuffix("www.xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        checkPublicSuffix("shishi.xn--55qx5d.cn", "shishi.xn--55qx5d.cn")
        checkPublicSuffix("xn--55qx5d.cn", null)
        checkPublicSuffix("xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        checkPublicSuffix("www.xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        checkPublicSuffix("shishi.xn--fiqs8s", "shishi.xn--fiqs8s")
        checkPublicSuffix("xn--fiqs8s", null)
    }

    @Test
    fun registrableDomainOfEverydayHosts() {
        assertEquals("paypal.com", Domains.registrableDomain("www.paypal.com"))
        assertEquals("bbc.co.uk", Domains.registrableDomain("news.bbc.co.uk"))
        assertEquals("sparkasse-koelnbonn.de", Domains.registrableDomain("banking.sparkasse-koelnbonn.de"))
        assertEquals("example.com", Domains.registrableDomain("www.example.com."))
        assertEquals("example.com", Domains.registrableDomain("  WWW.Example.COM  "))
        assertEquals("xn--mnchen-3ya.de", Domains.registrableDomain("www.münchen.de"))
        assertNull(Domains.registrableDomain("localhost"))
        assertNull(Domains.registrableDomain(""))
        assertNull(Domains.registrableDomain("exa mple.com"))
    }

    @Test
    fun privateSectionRulesSeparateSites() {
        assertNull(Domains.registrableDomain("github.io"))
        assertEquals("alice.github.io", Domains.registrableDomain("alice.github.io"))
        assertEquals("alice.github.io", Domains.registrableDomain("docs.alice.github.io"))
        // Wildcard in the private section: *.compute.amazonaws.com
        assertNull(Domains.registrableDomain("eu-1.compute.amazonaws.com"))
        assertEquals("host.eu-1.compute.amazonaws.com", Domains.registrableDomain("a.host.eu-1.compute.amazonaws.com"))
    }

    @Test
    fun ipAddressesReturnThemselves() {
        assertEquals("192.168.178.1", Domains.registrableDomain("192.168.178.1"))
        assertEquals("10.0.2.2", Domains.registrableDomain("010.0.2.2"))
        assertEquals("::1", Domains.registrableDomain("[::1]"))
        assertEquals("fe80::1", Domains.registrableDomain("FE80:0:0:0:0:0:0:1"))
        assertEquals("2001:db8::1:0:0:1", Domains.registrableDomain("2001:db8:0:0:1:0:0:1"))
        assertEquals("::ffff:c0a8:101", Domains.registrableDomain("::ffff:192.168.1.1"))
        assertTrue(Domains.isIpAddress("10.0.2.2"))
        assertTrue(Domains.isIpAddress("[2001:db8::1]"))
        assertFalse(Domains.isIpAddress("paypal.com"))
        assertFalse(Domains.isIpAddress("256.1.1.1"))
        // Numeric last label without being an IPv4 address: no registrable domain.
        assertNull(Domains.registrableDomain("2.10.0.2.2"))
        assertNull(Domains.registrableDomain("1:2:3"))
    }

    @Test
    fun hostOfParsesUrlsWithAndWithoutScheme() {
        assertEquals("www.example.com", Domains.hostOf("https://user@WWW.Example.com:8443/login?x=1#top"))
        assertEquals("paypal.com", Domains.hostOf("paypal.com/login"))
        assertEquals("paypal.com", Domains.hostOf("paypal.com:8080"))
        assertEquals("paypal.com", Domains.hostOf("paypal.com?next=/x"))
        assertEquals("www.paypal.com", Domains.hostOf("  HTTPS://WWW.PAYPAL.COM./  "))
        assertEquals("10.0.2.2", Domains.hostOf("http://10.0.2.2:8080/login.html"))
        assertEquals("::1", Domains.hostOf("http://[::1]:8080/"))
        assertEquals("fe80::1", Domains.hostOf("fe80::1"))
        assertEquals("xn--mnchen-3ya.de", Domains.hostOf("münchen.de/rathaus"))
        assertEquals("router", Domains.hostOf("http://router/"))
        assertNull(Domains.hostOf(""))
        assertNull(Domains.hostOf("   "))
        assertNull(Domains.hostOf(null))
        assertNull(Domains.hostOf("https://"))
        assertNull(Domains.hostOf("androidapp://com.paypal.android.p2pmobile"))
        assertNull(Domains.hostOf("cmd://putty.exe -ssh host"))
        assertNull(Domains.hostOf("{REF:A@I:46C9B1FFBD4ABC4BBB260C6190BAD20C}"))
        assertNull(Domains.hostOf("Mein Router"))
    }
}
