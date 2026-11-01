package de.lulurobb.keypassmobile.core.match

import de.lulurobb.keypassmobile.core.text.isAscii
import java.net.IDN
import java.util.Locale

/**
 * Public Suffix List (https://publicsuffix.org) with the rules of both the
 * ICANN and the private section, including wildcard and exception rules.
 * The list is bundled as a resource and never fetched from the network (SE-06).
 *
 * Rules are stored in ASCII (punycode) form, so lookups expect hosts that
 * went through [Domains] normalization.
 */
internal class PublicSuffixList private constructor(
    private val rules: Set<String>,
    /** Wildcard rules "*.ck", stored without the leading "*.". */
    private val wildcards: Set<String>,
    /** Exception rules "!www.ck", stored without the leading "!". */
    private val exceptions: Set<String>,
) {
    /**
     * Registrable domain (public suffix plus one label) of a normalized ASCII
     * host name, or null when the host is itself a public suffix.
     * Implements the algorithm from https://github.com/publicsuffix/list/wiki/Format.
     */
    fun registrableDomainOf(host: String): String? {
        // Start offsets of all suffixes, from the full host down to the last label.
        val starts = ArrayList<Int>(8)
        starts += 0
        for (i in host.indices) if (host[i] == '.') starts += i + 1
        val count = starts.size

        // An exception rule always prevails; its public suffix drops the leftmost label.
        for (i in 0 until count) {
            val suffix = host.substring(starts[i])
            if (suffix in exceptions) return suffix
        }

        // Otherwise the matching rule with the most labels prevails. Without
        // any match the implicit rule "*" makes the last label the suffix.
        var suffixIndex = count - 1
        for (i in 0 until count) {
            val suffix = host.substring(starts[i])
            val wildcardMatch = i + 1 < count && host.substring(starts[i + 1]) in wildcards
            if (suffix in rules || wildcardMatch) {
                suffixIndex = i
                break
            }
        }
        return if (suffixIndex == 0) null else host.substring(starts[suffixIndex - 1])
    }

    companion object {
        /** Absolute path, so the lookup survives class renaming by R8. */
        private const val RESOURCE = "/de/lulurobb/keypassmobile/core/match/public_suffix_list.dat"

        /** The bundled list, parsed on first use (about 16k lines). */
        val bundled: PublicSuffixList by lazy {
            val stream = PublicSuffixList::class.java.getResourceAsStream(RESOURCE)
                ?: error("Bundled Public Suffix List is missing")
            stream.bufferedReader(Charsets.UTF_8).useLines { parse(it) }
        }

        /** Parses the list format: one rule per line, "//" comments, rule ends at whitespace. */
        fun parse(lines: Sequence<String>): PublicSuffixList {
            val rules = HashSet<String>(16_384)
            val wildcards = HashSet<String>(256)
            val exceptions = HashSet<String>(64)
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                val token = line.substringBefore(' ').substringBefore('\t')
                when {
                    token.startsWith("!") -> exceptions += toAscii(token.substring(1))
                    token.startsWith("*.") -> wildcards += toAscii(token.substring(2))
                    else -> rules += toAscii(token)
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions)
        }

        private fun toAscii(rule: String): String {
            val lower = rule.lowercase(Locale.ROOT)
            if (lower.isAscii()) return lower
            return try {
                IDN.toASCII(lower, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
            } catch (e: IllegalArgumentException) {
                // Not representable in IDNA2003; such a rule can never match a normalized host.
                lower
            }
        }
    }
}
