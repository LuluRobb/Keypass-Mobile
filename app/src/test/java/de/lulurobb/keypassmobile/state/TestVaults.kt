package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.sync.CandidateState
import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.SyncStatus as CoreSyncStatus
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.model.VaultSecrets
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.ui.about.AboutInfo
import de.lulurobb.keypassmobile.ui.about.BuildVariant
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Entry with defaults for everything but the title. */
fun testEntry(
    title: String,
    username: String = "",
    url: String = "",
    group: List<String> = emptyList(),
    id: UUID = UUID.randomUUID(),
) = VaultEntry(
    id = id,
    title = title,
    username = username,
    url = url,
    additionalUrls = emptyList(),
    notes = "",
    tags = emptyList(),
    groupPath = group,
    iconId = 0,
    customIconId = null,
)

/** Secrets from a plain map; throws for unknown entries and after [close], like the real ones. */
class FakeVaultSecrets(private val passwords: Map<UUID, String> = emptyMap()) : VaultSecrets {
    var closed = false
        private set

    override fun password(entryId: UUID): CharArray {
        check(!closed) { "closed" }
        return (passwords[entryId] ?: error("unknown entry")).toCharArray()
    }

    override fun close() {
        closed = true
    }
}

fun testVault(
    entries: List<VaultEntry> = emptyList(),
    passwords: Map<UUID, String> = emptyMap(),
    rootName: String = "Root",
): Vault = Vault(rootName, entries, emptyMap(), FakeVaultSecrets(passwords))

/** An unlocked session over [vault] with a real [EntryIndex]. */
fun unlockedView(
    vault: Vault = testVault(),
    newVersionRejected: Boolean = false,
    version: Int = 1,
): SessionView.Unlocked = SessionView.Unlocked(vault, EntryIndex(vault), newVersionRejected, version)

fun testServerChange(
    host: String = "nas.local",
    rememberedGuid: UUID? = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000"),
    rememberedName: String? = "reitano-nas",
    newGuid: UUID = UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b"),
    newName: String? = null,
) = ServerChange(host, rememberedGuid, rememberedName, newGuid, newName)

/** Sync info with defaults for "unlocked, version of the share open, no problem". */
/** About facts of a CI build on a Pixel; tests change what they check with copy(). */
fun testAboutInfo(versionName: String = "0.1.0-test") = AboutInfo(
    versionName = versionName,
    versionCode = 7,
    variant = BuildVariant.Release,
    packageName = "de.lulurobb.keypassmobile",
    commit = "0123456789abcdef0123456789abcdef01234567",
    buildTime = Instant.parse("2026-11-07T12:30:00Z"),
    androidRelease = "16",
    apiLevel = 36,
    manufacturer = "Google",
    model = "Pixel 9",
)

fun testSyncInfo(
    openedVersion: OpenedVersion? = OpenedVersion.SHARE,
    lastSuccessfulSync: Instant? = Instant.parse("2026-11-01T10:00:00Z"),
    lastProblem: SyncProblem? = null,
    lastProblemAt: Instant? = null,
    syncing: Boolean = false,
    serverChange: ServerChange? = null,
    candidate: CandidateState = CandidateState.NONE,
) = SyncInfo(
    status = CoreSyncStatus(
        hasLocalDatabase = true,
        openedVersion = openedVersion,
        lastSuccessfulSync = lastSuccessfulSync,
        lastProblem = lastProblem,
        lastProblemAt = lastProblemAt,
        candidate = candidate,
    ),
    syncing = syncing,
    serverChange = serverChange,
)

/** Time source with a fixed instant and a fixed zone (UTC unless given). */
fun testTime(now: Instant = Instant.parse("2026-11-02T08:00:00Z"), zone: ZoneId = ZoneOffset.UTC) =
    TimeSource(Clock.fixed(now, ZoneOffset.UTC)) { zone }
