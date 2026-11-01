package de.lulurobb.keypassmobile.core

import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.model.VaultSecrets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Test entry with defaults for everything but the title. */
fun entry(
    title: String,
    username: String = "",
    url: String = "",
    additionalUrls: List<String> = emptyList(),
    notes: String = "",
    tags: List<String> = emptyList(),
    group: List<String> = emptyList(),
) = VaultEntry(
    id = UUID.randomUUID(),
    title = title,
    username = username,
    url = url,
    additionalUrls = additionalUrls,
    notes = notes,
    tags = tags,
    groupPath = group,
    iconId = 0,
    customIconId = null,
)

fun vaultOf(entries: List<VaultEntry>, rootName: String = "Root") =
    Vault(rootName, entries, emptyMap(), NoSecrets())

class NoSecrets : VaultSecrets {
    var closed = false
        private set

    override fun password(entryId: UUID): CharArray = CharArray(0)

    override fun close() {
        closed = true
    }
}

/** Clock that only moves when the test says so. */
class MutableClock(@Volatile var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
