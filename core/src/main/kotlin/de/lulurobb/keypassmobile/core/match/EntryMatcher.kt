package de.lulurobb.keypassmobile.core.match

import de.lulurobb.keypassmobile.core.text.foldName
import de.lulurobb.keypassmobile.core.text.wordsOf
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import java.net.IDN

/**
 * Decides which entries belong to an autofill target (AF-05) and whether
 * filling an entry into a web page needs the phishing warning (AF-06).
 *
 * Web: an entry matches when any of its URLs (url and additional URLs) has the
 * same registrable domain as the page host, so subdomains match each other
 * ("login.paypal.com" and "www.paypal.com"). IP addresses and hosts without a
 * registrable domain ("localhost") only match exactly.
 *
 * App: the app label is compared with the entry title and with the name part
 * of the registrable domain of the entry URLs ("paypal" of "www.paypal.com").
 * This is only a sorting aid, because every app chooses its label freely
 * (AF-07); there is no warning for apps.
 *
 * [EntryIndex][de.lulurobb.keypassmobile.core.index.EntryIndex.matching] uses the
 * same rules with precomputed data per entry.
 */
object EntryMatcher {

    /** True when [entry] belongs to [target] by the rules above. */
    fun matches(entry: VaultEntry, target: FillTarget): Boolean =
        matchDataOf(entry).matches(prepareTarget(target))

    /**
     * True when filling [entry] into the page needs the phishing warning
     * (AF-06): none of its URLs belongs to the page's registrable domain.
     * Entries without any usable URL always count as a mismatch.
     */
    fun isDomainMismatch(entry: VaultEntry, target: FillTarget.Web): Boolean = !matches(entry, target)

    /**
     * Domain of [entry] for the warning dialog: the registrable domain of its
     * first usable URL (the IP address or host where there is none), or null
     * when the entry has no usable URL. Returned in ASCII form (punycode), so
     * look-alike Unicode domains stay recognizable.
     */
    fun displayDomain(entry: VaultEntry): String? =
        entryUrls(entry).firstNotNullOfOrNull { Domains.hostOf(it) }?.let(Domains::siteKeyOf)

    /** Domain of the page for the warning dialog, same form as [displayDomain]. */
    fun displayDomain(target: FillTarget.Web): String =
        Domains.hostOf(target.host)?.let(Domains::siteKeyOf) ?: target.host

    // ---- internal, shared with EntryIndex ----

    internal class NameKey(val joined: String, val words: List<String>)

    internal class MatchData(
        /** Site keys of all usable URLs (see [Domains.siteKeyOf]). */
        val siteKeys: List<String>,
        /** Name part of the registrable domain of each URL, e.g. "paypal". */
        val urlNames: List<NameKey>,
        val title: NameKey?,
    ) {
        fun matches(target: PreparedTarget): Boolean = when (target) {
            is PreparedTarget.Web -> target.siteKey != null && target.siteKey in siteKeys
            is PreparedTarget.App -> {
                val app = target.name
                app != null && (
                    (title != null && namesMatch(app, title)) || urlNames.any { namesMatch(app, it) }
                    )
            }
        }
    }

    internal sealed interface PreparedTarget {
        class Web(val siteKey: String?) : PreparedTarget
        class App(val name: NameKey?) : PreparedTarget
    }

    internal fun prepareTarget(target: FillTarget): PreparedTarget = when (target) {
        is FillTarget.Web -> PreparedTarget.Web(Domains.hostOf(target.host)?.let(Domains::siteKeyOf))
        is FillTarget.App -> PreparedTarget.App(nameKeyOf(target.appLabel))
    }

    internal fun matchDataOf(entry: VaultEntry): MatchData {
        val siteKeys = ArrayList<String>(2)
        val urlNames = ArrayList<NameKey>(2)
        for (url in entryUrls(entry)) {
            val host = Domains.hostOf(url) ?: continue
            siteKeys += Domains.siteKeyOf(host)
            if (Domains.isNormalizedIp(host)) continue
            val registrable = Domains.registrableDomainOfNormalized(host) ?: continue
            val label = registrable.substringBefore('.')
            val unicode = try {
                IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED)
            } catch (e: IllegalArgumentException) {
                label
            }
            nameKeyOf(unicode)?.let { urlNames += it }
        }
        return MatchData(siteKeys, urlNames, nameKeyOf(entry.title))
    }

    /** Folded name, or null when it has fewer than 3 letters or digits. */
    internal fun nameKeyOf(text: String?): NameKey? {
        if (text.isNullOrBlank()) return null
        val words = wordsOf(foldName(text))
        val joined = words.joinToString("")
        return if (joined.length >= MIN_NAME_LENGTH) NameKey(joined, words) else null
    }

    /**
     * The shorter name matches the longer one when it is a prefix of it
     * ("paypal" in "PayPal Business"), when it is contained in it and has at
     * least 4 characters ("outlook" in "Microsoft Outlook"), or when it equals
     * one of its words ("ING" in "Mein ING Konto", but not in "Booking").
     */
    internal fun namesMatch(a: NameKey, b: NameKey): Boolean {
        val (short, long) = if (a.joined.length <= b.joined.length) a to b else b to a
        if (short.joined.length < MIN_NAME_LENGTH) return false
        if (long.joined.startsWith(short.joined)) return true
        if (short.joined.length >= 4 && long.joined.contains(short.joined)) return true
        return long.words.any { it == short.joined }
    }

    private const val MIN_NAME_LENGTH = 3

    private fun entryUrls(entry: VaultEntry): List<String> =
        if (entry.additionalUrls.isEmpty()) listOf(entry.url) else listOf(entry.url) + entry.additionalUrls
}
