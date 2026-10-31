package de.reitano.keypassmobile.prototype.autofill

/** One credential entry as the autofill picker sees it. */
data class AutofillEntry(
    val title: String,
    val username: String,
    val password: String,
    val url: String?,
) {
    // Keep the password out of toString() so it cannot end up in a log by accident.
    override fun toString(): String = "AutofillEntry(title=$title, url=$url)"
}

/** Entry plus whether it matches the requesting page or app. */
data class RankedEntry(val entry: AutofillEntry, val matches: Boolean)

/**
 * Matching of entries against the web domain (browser) or the app name (apps).
 * Prototype simplification: a domain matches when it is equal to the entry host
 * or one is a subdomain of the other (a leading "www." is ignored). The final
 * app compares registrable domains using a bundled Public Suffix List (AF-05).
 */
object EntryMatcher {

    /** Matching entries first, original order kept within both groups. */
    fun rank(entries: List<AutofillEntry>, webDomain: String?, appName: String?): List<RankedEntry> =
        entries
            .map { entry ->
                val matches = if (webDomain != null) {
                    domainMatches(webDomain, entry.url)
                } else {
                    appNameMatches(appName, entry)
                }
                RankedEntry(entry, matches)
            }
            .sortedByDescending { it.matches }

    fun domainMatches(pageDomain: String, entryUrl: String?): Boolean {
        val page = normalizeHost(hostOf(pageDomain)) ?: return false
        val entry = normalizeHost(hostOf(entryUrl)) ?: return false
        if (page == entry) return true
        if (isIpAddress(page) || isIpAddress(entry)) return false
        // Single-label hosts (localhost, a bare TLD) only match exactly.
        if ('.' !in page || '.' !in entry) return false
        return page.endsWith(".$entry") || entry.endsWith(".$page")
    }

    /** Sorting aid only (AF-07): app names are chosen freely by every app. */
    fun appNameMatches(appName: String?, entry: AutofillEntry): Boolean {
        val app = simplify(appName)
        if (app.length < 3) return false
        val title = simplify(entry.title)
        if (title.length >= 3 && (app in title || title in app)) return true
        val host = normalizeHost(hostOf(entry.url)) ?: return false
        if (isIpAddress(host)) return false
        val labels = host.split('.').dropLast(1).map(::simplify).filter { it.length >= 4 }
        return labels.any { it in app }
    }

    /**
     * Host part of a URL or bare domain, lower case, without scheme, user
     * info, port and path. Returns null for blank input.
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var rest = url.trim().lowercase()
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (rest.startsWith("[")) {
            rest.substringAfter('[').substringBefore(']')
        } else {
            rest.substringBefore(':')
        }
        return host.trimEnd('.').ifEmpty { null }
    }

    private fun normalizeHost(host: String?): String? = host?.removePrefix("www.")?.ifEmpty { null }

    private fun isIpAddress(host: String): Boolean =
        ':' in host || host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) } }

    private fun simplify(text: String?): String = text.orEmpty().lowercase().filter(Char::isLetterOrDigit)
}
