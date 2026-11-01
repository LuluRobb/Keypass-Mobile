package de.lulurobb.keypassmobile.core.text

import java.text.Normalizer
import java.util.Locale

/** End of the Latin blocks (Basic Latin to Latin Extended-B) folded by table. */
private const val LATIN_END = 0x250

/** Folded form of each character from 0x80 to [LATIN_END], computed once like the slow path does. */
private val LATIN_FOLD: Array<String> = Array(LATIN_END - 0x80) { i ->
    val c = (0x80 + i).toChar()
    stripMarks(Normalizer.normalize(c.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD))
}

/**
 * Case and accent folding for the full text search (UI-01): lower case,
 * combining marks removed, so a u with umlaut matches a plain u in any case.
 * Text with Latin characters only (the usual case) is folded by table; other
 * text goes through Unicode normalization. Both paths give the same result
 * for Latin characters, so queries and entries always fold alike.
 */
internal fun foldForSearch(text: String): String {
    for (c in text) {
        if (c.code >= LATIN_END) return stripMarks(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD))
    }
    val folded = StringBuilder(text.length)
    for (c in text) {
        when {
            c in 'A'..'Z' -> folded.append(c + ('a' - 'A'))
            c.code < 0x80 -> folded.append(c)
            else -> folded.append(LATIN_FOLD[c.code - 0x80])
        }
    }
    return folded.toString()
}

/**
 * Folding for comparing names (app labels, entry titles, domain labels):
 * lower case, German umlauts transliterated (a umlaut -> ae, sharp s -> ss) because domain
 * names usually spell them that way, other accents removed.
 */
internal fun foldName(text: String): String {
    val lower = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)
    if (lower.isAscii()) return lower
    val transliterated = buildString(lower.length + 4) {
        for (c in lower) {
            when (c) {
                'ä' -> append("ae")
                'ö' -> append("oe")
                'ü' -> append("ue")
                'ß' -> append("ss")
                else -> append(c)
            }
        }
    }
    return stripMarks(Normalizer.normalize(transliterated, Normalizer.Form.NFD))
}

/** Splits [text] into runs of letters and digits; everything else separates words. */
internal fun wordsOf(text: String): List<String> {
    val words = ArrayList<String>()
    var start = -1
    for (i in text.indices) {
        if (Character.isLetterOrDigit(text[i])) {
            if (start < 0) start = i
        } else if (start >= 0) {
            words += text.substring(start, i)
            start = -1
        }
    }
    if (start >= 0) words += text.substring(start)
    return words
}

/** Splits a search query at whitespace, dropping empty parts. */
internal fun splitAtWhitespace(text: String): List<String> {
    val parts = ArrayList<String>()
    var start = -1
    for (i in text.indices) {
        if (Character.isWhitespace(text[i])) {
            if (start >= 0) {
                parts += text.substring(start, i)
                start = -1
            }
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) parts += text.substring(start)
    return parts
}

internal fun String.isAscii(): Boolean {
    for (c in this) if (c.code >= 0x80) return false
    return true
}

private fun stripMarks(decomposed: String): String = buildString(decomposed.length) {
    for (c in decomposed) {
        if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) append(c)
    }
}
