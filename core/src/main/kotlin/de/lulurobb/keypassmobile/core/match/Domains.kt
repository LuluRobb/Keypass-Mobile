package de.lulurobb.keypassmobile.core.match

import de.lulurobb.keypassmobile.core.text.isAscii
import java.net.IDN
import java.util.Locale

/**
 * Host and domain handling for autofill matching (AF-05, AF-06). Works
 * offline with the bundled Public Suffix List (SE-06).
 */
object Domains {

    /** KeePass URL schemes that do not name a web host. */
    private val NON_WEB_SCHEMES = setOf("androidapp", "cmd", "file", "kdbx", "otpauth")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val HOST_NAME = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")

    /**
     * Host of a URL as stored in an entry or reported by a browser, normalized
     * (lower case, punycode, no trailing dot, IPv6 without brackets).
     * The scheme is optional, so "paypal.com/login" works as well as
     * "https://user@www.paypal.com:8443/login". Returns null for blank or
     * unparsable input and for non-web schemes like "androidapp://" or "cmd://".
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var rest = url.trim()
        SCHEME.find(rest)?.let { match ->
            if (match.groupValues[1].lowercase(Locale.ROOT) in NON_WEB_SCHEMES) return null
            rest = rest.substring(match.value.length)
        }
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore('\\')
        // KeePass placeholders like "{REF:A@I:...}" are no host, even though they contain "@".
        if (rest.any { it == '{' || it == '}' || it.isWhitespace() }) return null
        rest = rest.substringAfterLast('@')
        val host = when {
            rest.startsWith("[") -> rest.substring(1).substringBefore(']')
            // More than one colon without brackets: a bare IPv6 address.
            rest.count { it == ':' } > 1 -> rest
            else -> rest.substringBefore(':')
        }
        return normalizeHost(host)
    }

    /**
     * Registrable domain of [host] by the Public Suffix List: the public suffix
     * plus one label, e.g. "paypal.com" for "www.paypal.com" or "bbc.co.uk" for
     * "news.bbc.co.uk". Input is lower cased, a trailing dot is stripped and IDN
     * names are converted with [IDN.toASCII]; the result is ASCII.
     * IPv4 and IPv6 addresses return themselves (normalized).
     * Returns null when the host is invalid or itself a public suffix
     * (e.g. "com", "github.io", "localhost").
     */
    fun registrableDomain(host: String): String? {
        val normalized = normalizeHost(host) ?: return null
        return registrableDomainOfNormalized(normalized)
    }

    /** True for IPv4 and IPv6 addresses (with or without brackets). */
    fun isIpAddress(host: String): Boolean = normalizeHost(host)?.let(::isNormalizedIp) == true

    /** Parses the bundled Public Suffix List now instead of on first use (takes a few ms). */
    fun preload() {
        PublicSuffixList.bundled
    }

    // ---- internal helpers, shared with the matcher ----

    internal fun registrableDomainOfNormalized(
        host: String,
        list: PublicSuffixList = PublicSuffixList.bundled,
    ): String? {
        if (isNormalizedIp(host)) return host
        // No TLD is numeric; such hosts are malformed IPs and only match exactly.
        if (host.substringAfterLast('.').all { it in '0'..'9' }) return null
        return list.registrableDomainOf(host)
    }

    /**
     * Key under which two hosts belong to the same site: the registrable
     * domain, or the host itself for IP addresses and hosts without one
     * (single labels like "localhost", bare public suffixes).
     */
    internal fun siteKeyOf(normalizedHost: String): String =
        registrableDomainOfNormalized(normalizedHost) ?: normalizedHost

    internal fun isNormalizedIp(host: String): Boolean = ':' in host || parseIpv4(host) != null

    internal fun normalizeHost(raw: String): String? {
        var host = raw.trim()
        if (host.length >= 2 && host.startsWith('[') && host.endsWith(']')) {
            host = host.substring(1, host.length - 1)
        }
        host = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (host.isEmpty()) return null
        if (':' in host) return parseIpv6(host)?.let(::formatIpv6)
        parseIpv4(host)?.let { return it.joinToString(".") }
        val ascii = if (host.isAscii()) {
            host
        } else {
            try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return if (ascii.length <= 253 && HOST_NAME.matches(ascii)) ascii else null
    }

    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            result[i] = value
        }
        return result
    }

    /** Parses an IPv6 address into 8 groups. Zone ids ("%wlan0") are not accepted. */
    private fun parseIpv6(text: String): IntArray? {
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
        val head = if (gap >= 0) groupsOf(text.substring(0, gap), allowIpv4 = false) else groupsOf(text, allowIpv4 = true)
        val tail = if (gap >= 0) groupsOf(text.substring(gap + 2), allowIpv4 = true) else IntArray(0)
        if (head == null || tail == null) return null
        val total = head.size + tail.size
        if (gap < 0 && total != 8) return null
        if (gap >= 0 && total > 7) return null
        val groups = IntArray(8)
        head.copyInto(groups, 0)
        tail.copyInto(groups, 8 - tail.size)
        return groups
    }

    private fun groupsOf(part: String, allowIpv4: Boolean): IntArray? {
        if (part.isEmpty()) return IntArray(0)
        val pieces = part.split(':')
        val groups = ArrayList<Int>(8)
        for ((index, piece) in pieces.withIndex()) {
            if (allowIpv4 && index == pieces.lastIndex && '.' in piece) {
                val v4 = parseIpv4(piece) ?: return null
                groups += (v4[0] shl 8) or v4[1]
                groups += (v4[2] shl 8) or v4[3]
            } else {
                if (piece.isEmpty() || piece.length > 4) return null
                if (!piece.all { it in '0'..'9' || it in 'a'..'f' }) return null
                groups += piece.toInt(16)
            }
        }
        return groups.toIntArray()
    }

    /** RFC 5952 text form: lower case, no leading zeros, longest zero run as "::". */
    private fun formatIpv6(groups: IntArray): String {
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLength) {
                    bestStart = i
                    bestLength = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLength < 2) bestStart = -1
        val text = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                text.append("::")
                i += bestLength
                continue
            }
            if (text.isNotEmpty() && text.last() != ':') text.append(':')
            text.append(Integer.toHexString(groups[i]))
            i++
        }
        return text.toString()
    }
}
