package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSecretsTest {

    @Test
    fun `reveal before and after close`() {
        val vault = KdbxReader().open(TestDbs.file(TestDbs.ARGON2D_AES), TestDbs.hash())
        val github = vault.entries.single { it.title == "GitHub" }
        val baker = vault.entries.single { it.title == "Bäckerei Müller" }

        val first = vault.revealPassword(github.id)
        assertEquals("gh-Test-Passwort-1", String(first))
        // Every call returns a fresh array the caller may wipe.
        first.fill('\u0000')
        assertEquals("gh-Test-Passwort-1", String(vault.revealPassword(github.id)))
        assertEquals("Brötchen-42", String(vault.revealPassword(baker.id)))
        assertFailsWith<NoSuchElementException> { vault.revealPassword(UUID.randomUUID()) }

        vault.close()
        assertFailsWith<IllegalStateException> { vault.revealPassword(github.id) }
        assertFailsWith<IllegalStateException> { vault.revealPassword(baker.id) }
        vault.close() // idempotent
        assertFailsWith<IllegalStateException> { vault.revealPassword(github.id) }
    }

    @Test
    fun `passwords stay in kotpass' protected form`() {
        // The reflective access must work with the kotpass version in use;
        // otherwise every reveal would create a String.
        assertTrue(KotpassInternals.canReadEncryptedValue)
        val encrypted = EntryValue.Encrypted(EncryptedValue.fromString("geheim-ÄÖÜ"))
        val inner = KotpassInternals.encryptedValue(encrypted)
        assertEquals("geheim-ÄÖÜ", inner?.text)
        assertEquals("geheim-ÄÖÜ", String(ProtectedPassword.of(encrypted).reveal()))
        assertEquals("ProtectedPassword", ProtectedPassword.of(encrypted).toString())
    }

    @Test
    fun `secrets do not show values in toString`() {
        val secrets = KotpassVaultSecrets(mapOf(UUID(0, 1) to ProtectedPassword.of(EntryValue.Plain("geheim"))))
        assertFalse(secrets.toString().contains("geheim"))
        assertEquals("geheim", String(secrets.password(UUID(0, 1))))
        secrets.close()
        assertFailsWith<IllegalStateException> { secrets.password(UUID(0, 1)) }
    }

    @Test
    fun `utf-8 decoding without String`() {
        for (text in listOf("", "a", "Prüfung-Äß-2026", "€", "𝄞", "x😀y", "日本語")) {
            assertEquals(text, String(utf8ToChars(text.toByteArray(Charsets.UTF_8))))
        }
        // Invalid UTF-8 becomes U+FFFD instead of failing.
        assertEquals("a�b", String(utf8ToChars(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))))
    }

    @Test
    fun `mapping rules on synthetic content`() {
        val binId = UUID(1, 1)
        val templatesId = UUID(1, 2)
        val iconId = UUID(1, 3)
        val missingIconId = UUID(1, 4)
        fun entry(n: Long, title: String, password: EntryValue? = EntryValue.Encrypted(EncryptedValue.fromString("pw-$n")), icon: UUID? = null) = Entry(
            uuid = UUID(2, n),
            customIconUuid = icon,
            fields = EntryFields(
                buildMap {
                    put("Title", EntryValue.Plain(title))
                    if (password != null) put("Password", password)
                    put("KP2A_URL_2", EntryValue.Plain("https://c.example"))
                    put("KP2A_URL", EntryValue.Plain("https://a.example"))
                    put("KP2A_URL_1", EntryValue.Plain("  "))
                },
            ),
            history = listOf(Entry(uuid = UUID(2, n), fields = EntryFields(mapOf("Title" to EntryValue.Plain("$title alt"))))),
        )
        fun content(recycleBinEnabled: Boolean) = DatabaseContent(
            meta = Meta(
                recycleBinEnabled = recycleBinEnabled,
                recycleBinUuid = binId,
                entryTemplatesGroup = templatesId,
                customIcons = mapOf(iconId to CustomIcon(byteArrayOf(1, 2, 3), "icon", Instant.EPOCH)),
            ),
            group = Group(
                uuid = UUID(3, 0),
                name = "Wurzel",
                entries = listOf(
                    entry(1, "Eins", icon = iconId),
                    entry(2, "Zwei", password = null, icon = missingIconId),
                    entry(1, "Doppelte UUID"),
                    entry(3, "Ungeschützt", password = EntryValue.Plain("klar")),
                ),
                groups = listOf(
                    Group(uuid = binId, name = "Papierkorb", entries = listOf(entry(4, "Gelöscht")),
                        groups = listOf(Group(uuid = UUID(3, 1), name = "Tief", entries = listOf(entry(5, "Gelöscht tief"))))),
                    Group(uuid = templatesId, name = "Vorlagen", entries = listOf(entry(6, "Vorlage"))),
                    Group(uuid = UUID(3, 2), name = "Ä", groups = listOf(Group(uuid = UUID(3, 3), name = "Ö", entries = listOf(entry(7, "Tief"))))),
                ),
            ),
            deletedObjects = emptyList(),
        )

        val enabled = VaultMapper.map(content(recycleBinEnabled = true))
        assertEquals("Wurzel", enabled.rootGroupName)
        assertEquals(listOf("Eins", "Zwei", "Ungeschützt", "Tief"), enabled.entries.map { it.title })
        assertEquals(listOf("Ä", "Ö"), enabled.entries.last().groupPath)
        assertEquals(listOf("https://a.example", "https://c.example"), enabled.entries.first().additionalUrls)
        assertEquals(iconId, enabled.entries[0].customIconId)
        assertNull(enabled.entries[1].customIconId) // icon missing in Meta
        assertEquals(setOf(iconId), enabled.customIcons.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), enabled.customIcons[iconId])
        val secrets = KotpassVaultSecrets(enabled.passwords)
        assertEquals("pw-1", String(secrets.password(UUID(2, 1)))) // first of the duplicate UUIDs
        assertEquals("", String(secrets.password(UUID(2, 2))))
        assertEquals("klar", String(secrets.password(UUID(2, 3))))

        // Disabled recycle bin: KeePassXC shows its content like any group.
        val disabled = VaultMapper.map(content(recycleBinEnabled = false))
        assertEquals(listOf("Eins", "Zwei", "Ungeschützt", "Gelöscht", "Gelöscht tief", "Tief"), disabled.entries.map { it.title })
        assertEquals(listOf("Papierkorb", "Tief"), disabled.entries[4].groupPath)
    }
}
