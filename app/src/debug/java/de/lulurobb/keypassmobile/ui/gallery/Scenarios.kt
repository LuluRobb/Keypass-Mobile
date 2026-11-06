package de.lulurobb.keypassmobile.ui.gallery

import de.lulurobb.keypassmobile.ui.components.rememberSecretInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.CopiedValue
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.common.displayHost
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.detail.EntryDetailScreen
import de.lulurobb.keypassmobile.ui.detail.EntryDetailState
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerDialog
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerInfo
import de.lulurobb.keypassmobile.ui.dialogs.PhishingWarning
import de.lulurobb.keypassmobile.ui.icons.EntryIcons
import de.lulurobb.keypassmobile.ui.list.EntryListContent
import de.lulurobb.keypassmobile.ui.list.EntryListScreen
import de.lulurobb.keypassmobile.ui.list.EntryListState
import de.lulurobb.keypassmobile.ui.list.ListBanner
import de.lulurobb.keypassmobile.ui.picker.PickerScreen
import de.lulurobb.keypassmobile.ui.picker.PickerState
import de.lulurobb.keypassmobile.ui.settings.FingerprintAvailability
import de.lulurobb.keypassmobile.ui.settings.ServerIdentity
import de.lulurobb.keypassmobile.ui.settings.SettingsScreen
import de.lulurobb.keypassmobile.ui.settings.SettingsState
import de.lulurobb.keypassmobile.ui.settings.SourceInfo
import de.lulurobb.keypassmobile.ui.setup.BrowseContent
import de.lulurobb.keypassmobile.ui.setup.BrowseScreen
import de.lulurobb.keypassmobile.ui.setup.BrowseState
import de.lulurobb.keypassmobile.ui.setup.ConnectError
import de.lulurobb.keypassmobile.ui.setup.ConnectedServer
import de.lulurobb.keypassmobile.ui.setup.ServerField
import de.lulurobb.keypassmobile.ui.setup.ServerFormState
import de.lulurobb.keypassmobile.ui.setup.ServerScreen
import de.lulurobb.keypassmobile.ui.setup.SystemSetupScreen
import de.lulurobb.keypassmobile.ui.setup.SystemSetupState
import de.lulurobb.keypassmobile.ui.unlock.FirstRunUnlock
import de.lulurobb.keypassmobile.ui.unlock.PasswordError
import de.lulurobb.keypassmobile.ui.unlock.PasswordReason
import de.lulurobb.keypassmobile.ui.unlock.UnlockMode
import de.lulurobb.keypassmobile.ui.unlock.UnlockScreen
import de.lulurobb.keypassmobile.ui.unlock.UnlockState
import kotlinx.coroutines.delay
import java.time.LocalDate

/** One gallery entry. [content] gets a callback that returns to the gallery. */
internal class Scenario(
    val id: String,
    val group: String,
    val title: String,
    val content: @Composable (back: () -> Unit) -> Unit,
)

internal object Scenarios {

    private const val SETUP = "Einrichten"
    private const val UNLOCK = "Entsperren"
    private const val LIST = "Eintragsliste"
    private const val APP = "Kopier-Ansicht und Einstellungen"
    private const val FILL = "Ausfüllen"

    val all: List<Scenario> = listOf(
        Scenario("setup", SETUP, "Schritt 1: Server") { back -> ServerDemo(ServerFormState(host = FakeData.HOST, user = FakeData.SMB_USER), back) },
        Scenario("setup-advanced", SETUP, "Schritt 1: Erweitert offen, Pflichtfelder") { back ->
            ServerDemo(ServerFormState(advancedOpen = true, invalidFields = setOf(ServerField.Host, ServerField.User)), back)
        },
        Scenario("setup-connecting", SETUP, "Schritt 1: Verbinde") { back ->
            ServerDemo(ServerFormState(host = FakeData.HOST, user = FakeData.SMB_USER, connecting = true), back)
        },
        Scenario("setup-unreachable", SETUP, "Schritt 1: Server nicht erreichbar") { back ->
            ServerDemo(ServerFormState(host = FakeData.HOST, user = FakeData.SMB_USER, error = ConnectError.Unreachable), back)
        },
        Scenario("setup-login", SETUP, "Schritt 1: Anmeldung fehlgeschlagen") { back ->
            ServerDemo(ServerFormState(host = FakeData.HOST, user = FakeData.SMB_USER, error = ConnectError.LoginFailed), back)
        },
        Scenario("setup-edit", SETUP, "Quelle ändern: anderer Server") { back ->
            ServerDemo(
                ServerFormState(host = FakeData.HOST, user = FakeData.SMB_USER, step = null, error = ConnectError.OtherServer(FakeData.SERVER)),
                back,
            )
        },
        Scenario("browse", SETUP, "Schritt 2: Freigaben") { back -> BrowseDemo(emptyList(), null, back) },
        Scenario("browse-folder", SETUP, "Schritt 2: Ordner, Datei gewählt") { back ->
            BrowseDemo(listOf("kpm"), listOf("kpm", FakeData.FILE), back)
        },
        Scenario("browse-edit", SETUP, "Quelle ändern: Start im Ordner der Datei") { back ->
            BrowseDemo(listOf("kpm"), listOf("kpm", FakeData.FILE), back, step = null)
        },
        Scenario("browse-loading", SETUP, "Schritt 2: Lade Ordner") { back -> BrowseDemo(listOf("kpm"), null, back, loading = true) },
        Scenario("browse-unlisted", SETUP, "Schritt 2: Server listet keine Freigaben") { back ->
            BrowseDemo(emptyList(), null, back, unlisted = true)
        },
        Scenario("browse-dialog", SETUP, "Schritt 2: Freigabe von Hand") { back ->
            BrowseDemo(emptyList(), null, back, dialog = "kpm$")
        },
        Scenario("setup-password", SETUP, "Schritt 3: Master-Passwort") { back ->
            UnlockDemo(
                UnlockState(
                    fileName = FakeData.FILE,
                    mode = UnlockMode.Password,
                    fingerprintUsable = false,
                    firstRun = FirstRunUnlock(SetupStep(3), FakeData.FILE_SIZE, 7, fingerprintWanted = true, fingerprintAvailable = true),
                ),
                back,
            )
        },
        Scenario("setup-system", SETUP, "Schritt 4: System") { back -> SystemDemo(false, false) },
        Scenario("setup-system-half", SETUP, "Schritt 4: Autofill erledigt") { back -> SystemDemo(true, false) },

        Scenario("unlock", UNLOCK, "Fingerabdruck") { back ->
            UnlockDemo(UnlockState(FakeData.FILE, UnlockMode.Fingerprint, fingerprintUsable = true), back)
        },
        Scenario("unlock-password", UNLOCK, "Master-Passwort (nach Abbrechen)") { back ->
            UnlockDemo(UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = true), back)
        },
        Scenario("unlock-wrong", UNLOCK, "Falsches Passwort") { back ->
            UnlockDemo(
                UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = true, passwordError = PasswordError.Wrong),
                back,
            )
        },
        Scenario("unlock-due", UNLOCK, "Passwort fällig") { back ->
            UnlockDemo(
                UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = false, passwordReason = PasswordReason.Due(7)),
                back,
            )
        },
        Scenario("unlock-invalidated", UNLOCK, "Neuer Fingerabdruck registriert") { back ->
            UnlockDemo(
                UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = false, passwordReason = PasswordReason.FingerprintInvalidated),
                back,
            )
        },
        Scenario("unlock-rejected", UNLOCK, "Gespeicherter Wert passt nicht") { back ->
            UnlockDemo(
                UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = false, passwordReason = PasswordReason.StoredKeyRejected),
                back,
            )
        },
        Scenario("unlock-busy", UNLOCK, "Datenbank wird geöffnet") { back ->
            UnlockDemo(UnlockState(FakeData.FILE, UnlockMode.Password, fingerprintUsable = true, busy = true), back)
        },
        Scenario("unlock-fill", UNLOCK, "Zum Ausfüllen gesperrt") { back ->
            UnlockDemo(UnlockState(FakeData.FILE, UnlockMode.Fingerprint, fingerprintUsable = true, fillTarget = FakeData.webOk), back)
        },
        Scenario("unlock-autolock", UNLOCK, "Gesperrt nach Zeit ohne Nutzung") { back ->
            UnlockDemo(
                UnlockState(FakeData.FILE, UnlockMode.Fingerprint, fingerprintUsable = true, lockNotice = UiMessage.AutoLocked(120)),
                back,
            )
        },

        Scenario("list", LIST, "Eintragsliste") { back -> ListDemo(back) },
        Scenario("list-search", LIST, "Suche") { back -> ListDemo(back, query = "pay") },
        Scenario("list-search-empty", LIST, "Suche ohne Treffer") { back -> ListDemo(back, query = "xyz") },
        Scenario("list-filter", LIST, "Filter Finanzen") { back -> ListDemo(back, filter = "Finanzen") },
        Scenario("list-syncing", LIST, "Synchronisiere") { back -> ListDemo(back, syncing = true) },
        Scenario("list-offline", LIST, "Share nicht erreichbar") { back ->
            ListDemo(back, status = SyncStatus.OfflineCopy(FakeData.offlineCopy, OfflineReason.ShareUnreachable))
        },
        Scenario("list-server", LIST, "Anderer Server") { back ->
            ListDemo(
                back,
                status = SyncStatus.OfflineCopy(FakeData.offlineCopy, OfflineReason.OtherServer),
                banner = ListBanner.OtherServer(FakeData.HOST),
            )
        },
        Scenario("list-server-dialog", LIST, "Anderer Server: Details") { back ->
            ListDemo(
                back,
                status = SyncStatus.OfflineCopy(FakeData.offlineCopy, OfflineReason.OtherServer),
                banner = ListBanner.OtherServer(FakeData.HOST),
                serverDialog = true,
            )
        },
        Scenario("list-candidate", LIST, "Neue Version passt nicht") { back ->
            ListDemo(
                back,
                status = SyncStatus.OfflineCopy(FakeData.offlineCopy, OfflineReason.NewVersionRejected),
                banner = ListBanner.NewVersionRejected(FakeData.offlineCopy),
            )
        },
        Scenario("list-locked-msg", LIST, "Meldung nach Sync") { back -> ListDemo(back, message = UiMessage.SyncNoNewer) },

        Scenario("detail", APP, "Kopier-Ansicht") { back -> DetailDemo("paypal", back) },
        Scenario("detail-http", APP, "Kopier-Ansicht: Router über HTTP, kopiert") { back ->
            DetailDemo("fritz", back, UiMessage.Copied(CopiedValue.Password))
        },
        Scenario("detail-custom", APP, "Kopier-Ansicht: eigenes Icon") { back -> DetailDemo("netflix", back) },
        Scenario("settings", APP, "Einstellungen") { back -> SettingsDemo(back) },
        Scenario("settings-offline", APP, "Einstellungen: offline, Sync läuft, ohne Fingerabdruck") { back ->
            SettingsDemo(back, offline = true, syncing = true, fingerprint = false)
        },

        Scenario("picker", FILL, "Brave: paypal.com") { back -> PickerDemo(FakeData.webOk, back) },
        Scenario("picker-phish", FILL, "Brave: gefälschte Seite") { back -> PickerDemo(FakeData.webPhish, back) },
        Scenario("picker-phish-dialog", FILL, "Brave: Warnung") { back ->
            PickerDemo(FakeData.webPhish, back, warnFor = "paypal")
        },
        Scenario("picker-app", FILL, "PayPal-App") { back -> PickerDemo(FakeData.app, back) },
        Scenario("picker-search", FILL, "Suche im Auswahlfenster") { back -> PickerDemo(FakeData.webOk, back, query = "pay") },
    )
}

@Composable
private fun ServerDemo(initial: ServerFormState, back: () -> Unit) {
    var state by remember { mutableStateOf(initial) }
    val password = rememberSecretInput()
    LaunchedEffect(password) { password.set("beispiel".toCharArray()) }
    ServerScreen(
        state = state,
        password = password,
        onHostChange = { state = state.copy(host = it, invalidFields = state.invalidFields - de.lulurobb.keypassmobile.ui.setup.ServerField.Host) },
        onUserChange = { state = state.copy(user = it, invalidFields = state.invalidFields - de.lulurobb.keypassmobile.ui.setup.ServerField.User) },
        onPortChange = { state = state.copy(port = it) },
        onDomainChange = { state = state.copy(domain = it) },
        onToggleAdvanced = { state = state.copy(advancedOpen = !state.advancedOpen) },
        onConnect = { state = state.copy(connecting = true, error = null) },
        onBack = back,
        onAcceptOtherServer = { state = state.copy(error = null) },
    )
    if (state.connecting && !initial.connecting) {
        LaunchedEffect(Unit) {
            delay(1200)
            state = state.copy(connecting = false, error = ConnectError.LoginFailed)
        }
    }
}

@Composable
private fun BrowseDemo(
    startPath: List<String>,
    startSelected: List<String>?,
    back: () -> Unit,
    step: SetupStep? = SetupStep(2),
    unlisted: Boolean = false,
    dialog: String? = null,
    loading: Boolean = false,
) {
    var path by remember { mutableStateOf(startPath) }
    var selected by remember { mutableStateOf(startSelected) }
    var dialogName by remember { mutableStateOf(dialog) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    val content = when {
        loading -> BrowseContent.Loading
        path.isEmpty() && unlisted -> BrowseContent.SharesNotListed
        path.isEmpty() -> BrowseContent.Shares(FakeData.shares)
        else -> FakeData.folders[path.joinToString("/")]?.let { (dirs, files) ->
            BrowseContent.Folder(dirs, files, FakeData.hiddenFiles[path.joinToString("/")] ?: 0)
        } ?: BrowseContent.Folder(emptyList(), emptyList(), 0)
    }
    BrowseScreen(
        state = BrowseState(
            server = ConnectedServer(FakeData.SERVER, "SMB 3.1.1", encrypted = true),
            path = path,
            content = content,
            selected = selected,
            step = step,
            shareNameDialog = dialogName,
        ),
        onBack = { if (path.isNotEmpty()) path = path.dropLast(1) else back() },
        onOpenCrumb = { depth -> path = path.take(depth) },
        onOpenShare = { name ->
            if (name in FakeData.deniedShares) message = UiMessage.ShareDenied(name, FakeData.SMB_USER) else path = listOf(name)
        },
        onOpenFolder = { path = path + it },
        onSelectFile = { selected = it },
        onUseFile = back,
        onOpenShareDialog = { dialogName = "" },
        onShareDialogNameChange = { dialogName = it },
        onShareDialogConfirm = {
            val name = dialogName.orEmpty().trim()
            dialogName = null
            if (FakeData.folders.containsKey(name)) path = listOf(name) else message = UiMessage.ShareNotFound(name)
        },
        onShareDialogDismiss = { dialogName = null },
        message = message,
        onMessageDismissed = { message = null },
    )
}

@Composable
private fun UnlockDemo(initial: UnlockState, back: () -> Unit) {
    var state by remember { mutableStateOf(initial) }
    val password = rememberSecretInput()
    UnlockScreen(
        state = state,
        password = password,
        onFingerprint = { state = state.copy(busy = true) },
        onUsePassword = { state = state.copy(mode = UnlockMode.Password) },
        onSubmitPassword = {
            state = if (password.isEmpty) {
                state.copy(passwordError = PasswordError.Empty)
            } else {
                state.copy(passwordError = null, busy = true)
            }
        },
        onPasswordEdited = { if (state.passwordError != null) state = state.copy(passwordError = null) },
        onFingerprintWantedChange = { wanted ->
            state = state.copy(firstRun = state.firstRun?.copy(fingerprintWanted = wanted))
        },
        onBack = back,
        onCancelFill = back,
    )
    if (state.busy && !initial.busy) {
        LaunchedEffect(Unit) {
            delay(1500)
            state = state.copy(busy = false)
            back()
        }
    }
}

@Composable
private fun SystemDemo(autofill: Boolean, notifications: Boolean) {
    var state by remember { mutableStateOf(SystemSetupState(autofill, notifications)) }
    SystemSetupScreen(
        state = state,
        onChooseAutofill = { state = state.copy(autofillSelected = true) },
        onAllowNotifications = { state = state.copy(notificationsAllowed = true) },
        onFinish = {},
    )
}

@Composable
private fun ListDemo(
    back: () -> Unit,
    query: String = "",
    filter: String? = null,
    syncing: Boolean = false,
    status: SyncStatus = SyncStatus.Current(FakeData.lastSync),
    banner: ListBanner? = null,
    serverDialog: Boolean = false,
    message: UiMessage? = null,
) {
    val icons = remember { EntryIcons(FakeData.customIcons) }
    var q by remember { mutableStateOf(query) }
    var group by remember { mutableStateOf(filter) }
    var sync by remember { mutableStateOf(SyncState(status, syncing)) }
    var shownBanner by remember { mutableStateOf(banner) }
    var dialog by remember { mutableStateOf(serverDialog) }
    var msg by remember { mutableStateOf(message) }
    var detail by remember { mutableStateOf<VaultEntry?>(null) }
    detail?.let { entry ->
        androidx.activity.compose.BackHandler { detail = null }
        DetailDemo(entry, onBack = { detail = null })
        return
    }
    val scoped = FakeData.inGroup(group)
    val content = if (q.isBlank()) {
        EntryListContent.Grouped(FakeData.sections(scoped), scoped.size)
    } else {
        EntryListContent.Search(FakeData.search(scoped, q), scoped.size)
    }
    EntryListScreen(
        state = EntryListState(
            query = q,
            topGroups = FakeData.topGroups,
            groupFilter = group,
            sync = sync,
            content = content,
            rootGroupName = FakeData.ROOT,
            banner = shownBanner,
        ),
        icons = icons,
        onQueryChange = { q = it },
        onGroupFilterChange = { group = it },
        onEntryClick = { detail = it },
        onLock = back,
        onSettings = {},
        onSyncNow = { sync = sync.copy(syncing = true) },
        onBannerDetails = { dialog = true },
        onBannerDismiss = { shownBanner = null },
        message = msg,
        onMessageDismissed = { msg = null },
    )
    if (sync.syncing && !syncing) {
        LaunchedEffect(Unit) {
            delay(1100)
            sync = sync.copy(syncing = false)
            msg = if (sync.status is SyncStatus.OfflineCopy) UiMessage.SyncUnreachable else UiMessage.SyncNoNewer
        }
    }
    if (dialog) {
        OtherServerDialog(
            info = OtherServerInfo(FakeData.HOST, FakeData.SERVER, FakeData.GUID, null, FakeData.OTHER_GUID),
            onAccept = {
                dialog = false
                shownBanner = null
                sync = SyncState(SyncStatus.Current(FakeData.lastSync))
                msg = UiMessage.ServerAccepted
            },
            onDismiss = { dialog = false },
        )
    }
}

@Composable
private fun DetailDemo(key: String, back: () -> Unit, message: UiMessage? = null) =
    DetailDemo(FakeData.byKey(key), back, message)

@Composable
private fun DetailDemo(entry: VaultEntry, onBack: () -> Unit, message: UiMessage? = null) {
    val icons = remember { EntryIcons(FakeData.customIcons) }
    var msg by remember { mutableStateOf(message) }
    EntryDetailScreen(
        state = EntryDetailState(entry, FakeData.ROOT),
        icons = icons,
        onBack = onBack,
        onLock = onBack,
        onCopyUsername = { msg = UiMessage.Copied(CopiedValue.Username) },
        onCopyPassword = { msg = UiMessage.Copied(CopiedValue.Password) },
        message = msg,
        onMessageDismissed = { msg = null },
    )
}

@Composable
private fun SettingsDemo(back: () -> Unit, offline: Boolean = false, syncing: Boolean = false, fingerprint: Boolean = true) {
    var state by remember {
        mutableStateOf(
            SettingsState(
                source = SourceInfo(FakeData.HOST, "kpm", listOf(FakeData.FILE), FakeData.SMB_USER),
                server = ServerIdentity(FakeData.SERVER, LocalDate.of(2026, 10, 31), FakeData.GUID),
                sync = SyncState(
                    if (offline) SyncStatus.OfflineCopy(FakeData.offlineCopy, OfflineReason.ShareUnreachable)
                    else SyncStatus.Current(FakeData.lastSync),
                    syncing,
                ),
                fingerprintEnabled = fingerprint,
                fingerprintAvailability = FingerprintAvailability.Available,
                passwordDueOn = LocalDate.now().plusDays(6),
                passwordIntervalDays = 7,
                autoLockSeconds = 120,
                autofillSelected = true,
                notificationsAllowed = !offline,
                versionName = "0.1.0",
            ),
        )
    }
    SettingsScreen(
        state = state,
        onBack = back,
        onEditSource = {},
        onSyncNow = { state = state.copy(sync = state.sync.copy(syncing = true)) },
        onFingerprintChange = { state = state.copy(fingerprintEnabled = it) },
        onPasswordIntervalChange = { state = state.copy(passwordIntervalDays = it, passwordDueOn = LocalDate.now().plusDays(it.toLong())) },
        onAutoLockChange = { state = state.copy(autoLockSeconds = it) },
        onOpenAutofillSettings = {},
        onOpenNotificationSettings = { state = state.copy(notificationsAllowed = true) },
    )
}

@Composable
private fun PickerDemo(target: FillTarget, back: () -> Unit, query: String = "", warnFor: String? = null) {
    val icons = remember { EntryIcons(FakeData.customIcons) }
    var q by remember { mutableStateOf(query) }
    val pageDomain = (target as? FillTarget.Web)?.host
    fun warningFor(entry: VaultEntry) = PhishingWarning(
        entry,
        pageDomain.orEmpty(),
        entry.url.takeIf { it.isNotBlank() }?.let { FakeData.regDomain(displayHost(it)) },
    )
    var warning by remember { mutableStateOf(warnFor?.let { warningFor(FakeData.byKey(it)) }) }
    val matching = FakeData.entries.filter { FakeData.matches(it, target) }
    PickerScreen(
        state = PickerState(
            target = target,
            browserLabel = if (target is FillTarget.Web) "Brave" else null,
            query = q,
            matching = matching,
            sections = FakeData.sections(FakeData.entries),
            searchResults = if (q.isBlank()) null else FakeData.search(FakeData.entries, q),
            matchingIds = matching.map { it.id }.toSet(),
            rootGroupName = FakeData.ROOT,
            phishing = warning,
        ),
        icons = icons,
        onClose = back,
        onQueryChange = { q = it },
        onPick = { entry ->
            if (target is FillTarget.Web && !FakeData.matches(entry, target)) warning = warningFor(entry) else back()
        },
        onPhishingFillAnyway = back,
        onPhishingDismiss = { warning = null },
    )
}
