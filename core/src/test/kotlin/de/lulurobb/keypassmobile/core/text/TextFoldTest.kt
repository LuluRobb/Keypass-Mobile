package de.lulurobb.keypassmobile.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextFoldTest {

    @Test
    fun searchFoldingIgnoresCaseAndAccents() {
        assertEquals("muller strasse", foldForSearch("Müller Strasse"))
        assertEquals("muller", foldForSearch("MÜLLER"))
        assertEquals("cafe creme", foldForSearch("Café Crème"))
        assertEquals("ß", foldForSearch("ß"))
        // Decomposed input (a + combining diaeresis) folds like the precomposed form.
        assertEquals(foldForSearch("ä"), foldForSearch("ä"))
    }

    @Test
    fun tableAndNormalizerPathsAgreeForAllLatinCharacters() {
        // The euro sign is outside the table range and forces the normalizer path.
        for (code in 0x20 until 0x250) {
            val c = code.toChar()
            assertEquals(foldForSearch("$c") + "€", foldForSearch("$c€"), "character U+%04X".format(code))
        }
    }

    @Test
    fun nameFoldingTransliteratesUmlauts() {
        assertEquals("muellerstrasse", foldName("Müllerstraße"))
        assertEquals("aerzte", foldName("Ärzte"))
        assertEquals("cafe", foldName("Café"))
        assertEquals(listOf("mein", "ing", "konto"), wordsOf(foldName("Mein ING-Konto!")))
    }
}
