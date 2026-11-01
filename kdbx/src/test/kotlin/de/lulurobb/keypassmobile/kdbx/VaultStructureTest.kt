package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultEntry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping checks against keepassxc-structure.kdbx, written by KeePassXC 2.7.11
 * (see testdata/create-structure-testdb.ps1 and structure.xml). The file uses
 * AES-KDF, so it is opened once for all tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultStructureTest {

    private val reader = KdbxReader()
    private val vault: Vault = reader.open(TestDbs.file(TestDbs.STRUCTURE), TestDbs.hash())

    /** The raw kotpass view, to prove that the test file contains what is excluded. */
    private val raw: KeePassDatabase = reader.decode(TestDbs.bytes(TestDbs.STRUCTURE), TestDbs.hash())

    @AfterAll
    fun close() = vault.close()

    private fun id(n: Int) = UUID(0L, n.toLong())

    private fun entry(title: String): VaultEntry =
        assertNotNull(vault.entries.singleOrNull { it.title == title }, "Eintrag fehlt: $title")

    private fun Group.allGroups(): List<Group> = listOf(this) + groups.flatMap { it.allGroups() }
    private fun Group.allEntries(): List<Entry> = entries + groups.flatMap { it.allEntries() }

    @Test
    fun `test file contains recycle bin, templates group and history written by KeePassXC`() {
        val meta = raw.content.meta
        val root = raw.content.group
        assertTrue(meta.recycleBinEnabled)
        val binId = assertNotNull(meta.recycleBinUuid)
        val bin = assertNotNull(root.groups.singleOrNull { it.uuid == binId }, "Papierkorb fehlt")
        println("Papierkorb von KeePassXC: '${bin.name}', ${bin.allEntries().size} Einträge, Untergruppen ${bin.groups.map { it.name }}")
        assertEquals(setOf("Wegwerfen", "Alter Zugang", "Archivierter Zugang"), bin.allEntries().map { it.fields.title?.content }.toSet())
        assertEquals(listOf("Alt"), bin.groups.map { it.name })
        assertEquals(listOf("Archiv"), bin.groups.single().groups.map { it.name })

        assertEquals(id(0x06), meta.entryTemplatesGroup)
        assertEquals(3, meta.customIcons.size)

        val all = root.allEntries()
        val router = all.single { it.uuid == id(0x1B) }
        assertEquals(listOf("admin"), router.history.map { it.fields.userName?.content })
        val mail = all.single { it.uuid == id(0x14) }
        assertEquals(listOf("Mailkonto (alt)"), mail.history.map { it.fields.title?.content })
        println("Gruppen laut kotpass: ${root.allGroups().map { it.name }}")
    }

    @Test
    fun `only regular entries are mapped`() {
        assertEquals(
            listOf(
                "Root-Eintrag", "Girokonto", "Online-Banking", "Auslandskonto",
                "Mailkonto", "Forum", "Router", "Ungeschützt",
            ),
            vault.entries.map { it.title },
        )
        // No history versions, nothing from the recycle bin or the templates group.
        val ids = vault.entries.map { it.id }.toSet()
        for (excluded in listOf(0x16, 0x17, 0x18, 0x19, 0x1A)) {
            assertTrue(id(excluded) !in ids)
            assertFailsWith<NoSuchElementException> { vault.revealPassword(id(excluded)) }
        }
        assertEquals(ids.size, vault.entries.size)
    }

    @Test
    fun `group paths below the root group`() {
        assertEquals("Tresor", vault.rootGroupName)
        val paths = vault.entries.associate { it.title to it.groupPath }
        assertEquals(emptyList(), paths["Root-Eintrag"])
        assertEquals(listOf("Finanzen"), paths["Girokonto"])
        assertEquals(listOf("Finanzen", "Geschäftlich"), paths["Online-Banking"])
        assertEquals(listOf("Finanzen", "Geschäftlich", "Übersee & Ausland"), paths["Auslandskonto"])
        assertEquals(listOf("E-Mail"), paths["Mailkonto"])
        assertEquals(listOf("Sonstiges"), paths["Router"])
    }

    @Test
    fun `standard icons are the KeePass icon numbers`() {
        val icons = vault.entries.associate { it.title to it.iconId }
        assertEquals(0, icons["Root-Eintrag"])
        assertEquals(66, icons["Girokonto"])
        // structure.xml says 37, but KeePassXC resets the standard icon to 0
        // when an entry has a custom icon (checked with keepassxc-cli export).
        assertEquals(0, icons["Online-Banking"])
        assertEquals(1, icons["Auslandskonto"])
        assertEquals(19, icons["Mailkonto"])
        assertEquals(68, icons["Forum"])
        assertEquals(3, icons["Router"])
        assertEquals(0, icons["Ungeschützt"])
    }

    @Test
    fun `custom icons keep the raw image bytes`() {
        val banking = entry("Online-Banking")
        assertEquals(id(0x20), banking.customIconId)
        // Only icons of mapped entries: not the group icon (0x21), not the icon
        // of the recycled entry (0x22).
        assertEquals(setOf(id(0x20)), vault.customIcons.keys)
        val png = assertNotNull(vault.customIcons[id(0x20)])
        assertContentEquals(Base64.getDecoder().decode(ICON_BANK_PNG), png)
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
        assertNull(entry("Girokonto").customIconId)
    }

    @Test
    fun `tags, additional urls and multi-line notes`() {
        val banking = entry("Online-Banking")
        println("Tags wie von KeePassXC gespeichert: ${banking.tags}")
        assertEquals(listOf("Bank", "Größe", "Wichtig"), banking.tags.sorted())
        assertEquals(
            listOf(
                "https://m.bank.example.com",
                "https://bank.example.de",
                "https://app.bank.example.com",
                "https://alt.bank.example.com",
            ),
            banking.additionalUrls,
        )
        assertEquals("https://bank.example.com/login", banking.url)
        assertEquals("firma-kunde", banking.username)
        assertEquals("Kundennummer steht im Brief.\nZweite Zeile: äöü ÄÖÜ ß\n\nNach der Leerzeile: Ende", banking.notes)
        assertEquals(listOf("Privat"), entry("Router").tags)
        assertEquals(emptyList(), entry("Girokonto").additionalUrls)
    }

    @Test
    fun `edited entry shows the current version`() {
        val router = entry("Router")
        assertEquals("administrator", router.username)
        assertEquals("router-Test-9", String(vault.revealPassword(router.id)))
        assertEquals("Mailkonto", entry("Mailkonto").title)
        assertEquals("mail-Test-4", String(vault.revealPassword(entry("Mailkonto").id)))
    }

    @Test
    fun `passwords incl supplementary characters, empty and unprotected ones`() {
        val banking = vault.revealPassword(entry("Online-Banking").id)
        assertEquals("Pässwort-€-𝄞-Test", String(banking))
        assertEquals(18, banking.size) // 17 code points, one of them a surrogate pair
        assertEquals(0, vault.revealPassword(entry("Forum").id).size)
        assertEquals("plain-Test-5", String(vault.revealPassword(entry("Ungeschützt").id)))
        assertEquals("root-Test-1", String(vault.revealPassword(entry("Root-Eintrag").id)))

        // How KeePassXC stored the password that was unprotected in the XML.
        val plain = raw.content.group.allEntries().single { it.uuid == id(0x1C) }.fields.password
        println("Ungeschütztes Passwort nach dem Speichern durch KeePassXC: ${plain?.javaClass?.simpleName}")
        assertTrue(plain is EntryValue.Encrypted || plain is EntryValue.Plain)
    }

    private companion object {
        /** Icon 0x20 from structure.xml. */
        const val ICON_BANK_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAIUlEQVR4XmMQTT1AEmLAFMKP6KzhPw4wqoG+GohBg1ADAB8TqtD9aLiBAAAAAElFTkSuQmCC"
    }
}
