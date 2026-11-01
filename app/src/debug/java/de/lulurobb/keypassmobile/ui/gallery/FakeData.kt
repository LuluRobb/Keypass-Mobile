package de.lulurobb.keypassmobile.ui.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import de.lulurobb.keypassmobile.model.EntrySection
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.displayHost
import de.lulurobb.keypassmobile.ui.setup.KdbxFile
import de.lulurobb.keypassmobile.ui.setup.ShareItem
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/*
 * Example data of the UI draft (ui-entwurf.html). No real credentials; the
 * three own icons are abstract placeholders drawn at runtime and encoded as
 * PNG, so the gallery runs through the same decode path as real KDBX icons.
 */
internal object FakeData {

    const val ROOT = "Passwörter"
    const val FILE = "Passwörter.kdbx"
    const val FILE_SIZE = 265_530L
    const val HOST = "192.168.178.10"
    const val SMB_USER = "kpm-leser"
    const val SERVER = "reitano-nas"
    const val GUID = "74696572-6e61-2d6f-6e61-730000000000"
    const val OTHER_GUID = "3f2a9c1e-8b47-4d2a-9e51-0c7b2f6a8d13"

    private val collator: Collator = Collator.getInstance(Locale.GERMANY)

    private val iconA = UUID.nameUUIDFromBytes("icon-a".toByteArray())
    private val iconB = UUID.nameUUIDFromBytes("icon-b".toByteArray())
    private val iconC = UUID.nameUUIDFromBytes("icon-c".toByteArray())

    val customIcons: Map<UUID, ByteArray> by lazy {
        mapOf(iconA to drawIcon('a'), iconB to drawIcon('b'), iconC to drawIcon('c'))
    }

    private fun entry(
        key: String, title: String, user: String, url: String, notes: String, tags: String,
        group: List<String>, icon: Int, custom: UUID? = null,
    ) = VaultEntry(
        id = UUID.nameUUIDFromBytes(key.toByteArray()),
        title = title,
        username = user,
        url = url,
        additionalUrls = emptyList(),
        notes = notes,
        tags = if (tags.isEmpty()) emptyList() else listOf(tags),
        groupPath = group,
        iconId = icon,
        customIconId = custom,
    )

    val entries: List<VaultEntry> = listOf(
        entry("amazon", "Amazon", "luca@example.de", "https://www.amazon.de", "", "Shopping", listOf("Internet", "Shopping"), 0, iconA),
        entry("adac", "ADAC", "12345678", "https://www.adac.de", "Mitgliedsnummer als Benutzername", "Auto", listOf("Verträge"), 0),
        entry("bahn", "Bahn", "luca@example.de", "https://www.bahn.de", "", "Reisen", listOf("Verträge"), 0),
        entry("telekom", "Deutsche Telekom", "luca@example.de", "https://www.telekom.de", "Festnetz und Internet", "Vertrag", listOf("Verträge"), 1),
        entry("dhl", "DHL", "luca@example.de", "https://www.dhl.de", "", "", listOf("Internet", "Shopping"), 0),
        entry("elster", "ELSTER", "luca.reitano", "https://www.elster.de", "Zertifikatsdatei liegt auf dem PC", "Steuer", listOf("Finanzen"), 67),
        entry("fritz", "FRITZ!Box", "admin", "http://192.168.178.1", "Router im Wohnzimmer", "Heimnetz", listOf("Heimnetz"), 3),
        entry("github", "GitHub", "lreitano", "https://github.com", "", "Entwicklung", listOf("Konten"), 0, iconB),
        entry("gmx", "GMX", "luca@example.de", "https://www.gmx.net", "", "Mail", listOf("Konten"), 19),
        entry("huk", "HUK24", "luca@example.de", "https://www.huk24.de", "", "Versicherung", listOf("Finanzen"), 0),
        entry("ionos", "IONOS", "123456789", "https://login.ionos.de", "Kundennummer", "Hosting", listOf("Finanzen", "Geschäftlich"), 35),
        entry("kleinanzeigen", "Kleinanzeigen", "luca@example.de", "https://www.kleinanzeigen.de", "", "Shopping", listOf("Internet", "Shopping"), 0),
        entry("microsoft", "Microsoft-Konto", "luca@example.de", "https://login.live.com", "", "", listOf("Konten"), 9),
        entry("nas", "NAS reitano-nas", "admin", "https://192.168.178.10:5001", "Verwaltung, nicht der Lese-Benutzer der App", "Heimnetz", listOf("Heimnetz"), 27),
        entry("netflix", "Netflix", "luca@example.de", "https://www.netflix.com", "", "Streaming", listOf("Internet", "Unterhaltung"), 0, iconC),
        entry("paypal", "PayPal", "luca@example.de", "https://www.paypal.com", "Zweiter Faktor: Bestätigung in der PayPal-App", "Bezahlen", listOf("Finanzen"), 66),
        entry("paypal2", "PayPal (geschäftlich)", "shop@example.de", "https://www.paypal.com", "", "Bezahlen", listOf("Finanzen", "Geschäftlich"), 66),
        entry("sparkasse", "Sparkasse", "Anmeldename 4711", "https://www.sparkasse.de", "", "Bank", listOf("Finanzen"), 37),
        entry("spotify", "Spotify", "luca@example.de", "https://accounts.spotify.com", "", "Streaming", listOf("Internet", "Unterhaltung"), 0),
        entry("steam", "Steam", "lreitano", "https://store.steampowered.com", "", "Spiele", listOf("Internet", "Unterhaltung"), 0),
        entry("vodafone", "Vodafone", "luca@example.de", "https://www.vodafone.de", "Mobilfunk", "Vertrag", listOf("Verträge"), 1),
        entry("zalando", "Zalando", "luca@example.de", "https://www.zalando.de", "", "Shopping", listOf("Internet", "Shopping"), 0),
    ).sortedWith { a, b -> collator.compare(a.title, b.title) }

    fun byKey(key: String): VaultEntry = entries.first { it.id == UUID.nameUUIDFromBytes(key.toByteArray()) }

    val topGroups: List<String> = entries.mapNotNull { it.groupPath.firstOrNull() }.distinct().sortedWith(collator)

    /** Sections by full group path, A to Z (what :core will build). */
    fun sections(list: List<VaultEntry>): List<EntrySection> =
        list.groupBy { it.groupPath }
            .toSortedMap { a, b -> collator.compare(a.joinToString(" / "), b.joinToString(" / ")) }
            .map { (path, items) -> EntrySection(path, items.sortedWith { x, y -> collator.compare(x.title, y.title) }) }

    fun search(list: List<VaultEntry>, query: String): List<VaultEntry> {
        val q = query.trim().lowercase(Locale.GERMANY)
        if (q.isEmpty()) return list
        return list.filter { e ->
            listOf(e.title, e.username, e.url, e.notes, e.tags.joinToString(" "), e.groupPath.joinToString(" / "))
                .any { it.lowercase(Locale.GERMANY).contains(q) }
        }
    }

    fun inGroup(group: String?): List<VaultEntry> =
        if (group == null) entries else entries.filter { it.groupPath.firstOrNull() == group }

    /** Simplified registrable domain: last two labels, IPs as they are (the real one uses the PSL). */
    fun regDomain(host: String): String =
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) host else host.split('.').takeLast(2).joinToString(".")

    fun matches(entry: VaultEntry, target: FillTarget): Boolean = when (target) {
        is FillTarget.Web -> entry.url.isNotBlank() && regDomain(displayHost(entry.url)) == regDomain(target.host)
        is FillTarget.App -> target.appLabel?.lowercase(Locale.GERMANY)?.let { label ->
            entry.title.lowercase(Locale.GERMANY).contains(label) || displayHost(entry.url).contains(label)
        } ?: false
    }

    val webOk = FillTarget.Web("paypal.com", "com.brave.browser")
    val webPhish = FillTarget.Web("paypa1-login.com", "com.brave.browser")
    val app = FillTarget.App("com.paypal.android.p2pmobile", "PayPal")

    val shares = listOf(
        ShareItem("kpm", "KeePass fürs Handy"),
        ShareItem("Medien", "Fotos und Videos"),
        ShareItem("Backup", "Datensicherung"),
    )
    val deniedShares = setOf("Medien", "Backup")

    val folders: Map<String, Pair<List<String>, List<KdbxFile>>> = mapOf(
        "kpm" to (
            listOf("Archiv") to listOf(
                KdbxFile(FILE, FILE_SIZE, LocalDateTime.of(2026, 10, 31, 21, 38)),
                KdbxFile("Passwörter.old.kdbx", 245_760, LocalDateTime.of(2026, 9, 12, 18, 5)),
            )
        ),
        "kpm/Archiv" to (
            emptyList<String>() to listOf(
                KdbxFile("Passwörter-2025.kdbx", 236_544, LocalDateTime.of(2025, 12, 30, 10, 12)),
            )
        ),
    )
    val hiddenFiles: Map<String, Int> = mapOf("kpm" to 1)

    val lastSync: LocalDateTime get() = LocalDate.now().atTime(21, 40)
    val offlineCopy: LocalDateTime = LocalDateTime.of(2026, 10, 28, 8, 12)

    private fun drawIcon(kind: Char): ByteArray {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = size / 24f
        when (kind) {
            'a' -> {
                paint.color = 0xFF2F6FDE.toInt()
                canvas.drawRoundRect(RectF(2 * s, 2 * s, 22 * s, 22 * s), 5 * s, 5 * s, paint)
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(12 * s, 12 * s, 4.5f * s, paint)
            }
            'b' -> {
                paint.color = 0xFF4B5563.toInt()
                canvas.drawCircle(12 * s, 12 * s, 10 * s, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * s
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val p = Path().apply {
                    moveTo(8 * s, 9 * s); lineTo(12 * s, 12 * s); lineTo(8 * s, 15 * s)
                    moveTo(13 * s, 16 * s); lineTo(16.5f * s, 16 * s)
                }
                canvas.drawPath(p, paint)
            }
            else -> {
                paint.color = 0xFFD9572B.toInt()
                canvas.drawRoundRect(RectF(2 * s, 2 * s, 22 * s, 22 * s), 5 * s, 5 * s, paint)
                paint.color = 0xFFFFFFFF.toInt()
                val p = Path().apply { moveTo(10 * s, 7.5f * s); lineTo(10 * s, 16.5f * s); lineTo(17 * s, 12 * s); close() }
                canvas.drawPath(p, paint)
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
