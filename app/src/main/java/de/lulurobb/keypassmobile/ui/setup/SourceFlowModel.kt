package de.lulurobb.keypassmobile.ui.setup

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.platform.setup.BrowseError
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectError as PlatformConnectError
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.SourceSetupPort
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.toOlderVersionInfo
import de.lulurobb.keypassmobile.state.toUi
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.SecretInput
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.UUID

/** Where the source flow is used. */
enum class SourceFlowMode {
    /** Setup steps 1 and 2; the flow ends with [SourceFlowModel.onSaved] and H goes on to the unlock step. */
    FirstRun,

    /** "Quelle verwalten" from the settings (or a banner action) while unlocked. */
    ChangeUnlocked,

    /** "Quelle verwalten" while locked (unlock screen). */
    ChangeLocked,
}

/**
 * Which screen of the flow is showing; null in [SourceFlowModel.screen] = the flow shows nothing.
 * [Choose] is the choice between SMB and a file on the phone: setup step 1 in the first run,
 * "Quelle verwalten" in the change modes.
 */
enum class SourceFlowScreen { Choose, Server, Browse }

/**
 * State holder of the source choice, the server form (setup step 1 / "Quelle verwalten") and the
 * file browser (step 2), over one [SourceSetupPort] that is opened with the
 * first use. The connection (and with it a copy of the SMB password inside
 * :platform) lives until [close].
 *
 * [start] is the first screen: [SourceFlowScreen.Choose] normally, [SourceFlowScreen.Server] for
 * the banner action "SMB-Freigabe ändern" (back then leaves the flow instead of going to Choose).
 * [onPickFile] opens the system file picker (FilePickController). The file path is not part of
 * this model: the controller reports a saved first-run file with [onFileSaved].
 *
 * [onSaved] is called with the downloaded file size after chooseFile saved the
 * source. In [SourceFlowMode.FirstRun] [screen] becomes null then and the
 * connection stays open, so H can come back with [reopen] from the unlock step;
 * the change modes post "Quelle gespeichert" (unless the switch locked the app) and H leaves the flow.
 */
class SourceFlowModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    private val mode: SourceFlowMode,
    private val start: SourceFlowScreen = SourceFlowScreen.Choose,
    private val onPickFile: () -> Unit = {},
    private val onSaved: (fileSizeBytes: Long?) -> Unit,
) : Closeable {

    private val changing = mode != SourceFlowMode.FirstRun

    private var openedPort: SourceSetupPort? = null

    private val port: SourceSetupPort
        get() = openedPort ?: platform.startSourceSetup().also { openedPort = it }

    /** The SMB password field. Owned here so the model can wipe it; the route hands it to the screen. */
    val password = SecretInput()

    /** Reads the typed password (a fresh copy). Seam for tests: a SecretInput has no text without an EditText. */
    internal var readPassword: () -> CharArray = { password.toCharArray() }

    private val mutableScreen = MutableStateFlow<SourceFlowScreen?>(start)

    /** Choice, server form or browser; null after the first-run file was saved (until [reopen]). */
    val screen: StateFlow<SourceFlowScreen?> = mutableScreen.asStateFlow()

    /**
     * What the choice screen shows: null in the first run (two option cards), else the kind of the
     * current source (the two rows of "Quelle verwalten", the current source first).
     */
    val manageKind: SourceKind? get() = if (changing) platform.settings.value.source?.kind else null

    private val mutableOlder = MutableStateFlow<OlderVersionInfo?>(null)

    /** "Ältere Version" dialog of an SMB file (DS-14); null = closed. */
    val olderDialog: StateFlow<OlderVersionInfo?> = mutableOlder.asStateFlow()

    /** The file the dialog asks about. */
    private var olderPath: List<String>? = null

    private val mutableForm = MutableStateFlow(initialForm())

    val form: StateFlow<ServerFormState> = mutableForm.asStateFlow()

    private val mutableBrowse = MutableStateFlow<BrowseState?>(null)

    /** Null until connected (and again after going back to the form). */
    val browse: StateFlow<BrowseState?> = mutableBrowse.asStateFlow()

    // Browser bookkeeping.
    private var sharesView: BrowseContent = BrowseContent.SharesNotListed
    private var connectedUser = ""
    private var acceptGuid: UUID? = null
    private var loadJob: Job? = null

    /** Location and content before the running navigation, for ACCESS_DENIED. Null: nothing sensible to return to. */
    private var before: Pair<List<String>, BrowseContent>? = null
    private var choosing = false

    // ---- choice ----

    /** "SMB-Freigabe" / "SMB-Freigabe ändern" / "Zu SMB-Freigabe wechseln": the server form. */
    fun onChooseSmb() {
        if (mutableScreen.value == SourceFlowScreen.Choose) mutableScreen.value = SourceFlowScreen.Server
    }

    /** "Datei auf dem Handy" / "Andere Datei wählen" / "Zu Datei wechseln": the system file picker. */
    fun onChooseFile() {
        if (mutableScreen.value == SourceFlowScreen.Choose) onPickFile()
    }

    /**
     * First run: a file was picked and saved. The flow shows nothing (the unlock step follows) and an
     * SMB connection of an earlier attempt is closed, it is of no use any more.
     */
    fun onFileSaved() {
        mutableScreen.value = null
        disconnect()
    }

    // ---- server form ----

    fun onHostChange(host: String) = editForm { it.copy(host = host, invalidFields = it.invalidFields - ServerField.Host) }

    fun onUserChange(user: String) = editForm { it.copy(user = user, invalidFields = it.invalidFields - ServerField.User) }

    fun onPortChange(port: String) = editForm { it.copy(port = port, invalidFields = it.invalidFields - ServerField.Port) }

    fun onDomainChange(domain: String) = editForm { it.copy(domain = domain) }

    fun onToggleAdvanced() = mutableForm.update { it.copy(advancedOpen = !it.advancedOpen) }

    /** Validates, then connects with the typed password (the stored SMB password is only for the automatic sync). */
    fun onConnect() {
        val current = mutableForm.value
        if (current.connecting) return
        val invalid = validate(current)
        if (invalid.isNotEmpty()) {
            mutableForm.update { it.copy(invalidFields = invalid, advancedOpen = it.advancedOpen || ServerField.Port in invalid) }
            return
        }
        connect(acceptServerGuid = null)
    }

    /** "Trotzdem verbinden" after [ConnectError.OtherServer]: the same connect, with the new GUID accepted. */
    fun onAcceptOtherServer() {
        if (mutableForm.value.connecting) return
        val guid = acceptGuid ?: return
        connect(acceptServerGuid = guid)
    }

    /** The password field changed (the field's length is observed by the route): the last error no longer applies. */
    fun onPasswordChange() {
        acceptGuid = null
        mutableForm.update { if (it.error != null) it.copy(error = null) else it }
    }

    private fun editForm(change: (ServerFormState) -> ServerFormState) {
        // Another address: the server question and the last error no longer apply.
        acceptGuid = null
        mutableForm.update { change(it).copy(error = null) }
    }

    private fun validate(form: ServerFormState): Set<ServerField> = buildSet {
        if (form.host.isBlank()) add(ServerField.Host)
        if (form.user.isBlank()) add(ServerField.User)
        if (portOf(form) == null) add(ServerField.Port)
    }

    private fun portOf(form: ServerFormState): Int? {
        val value = form.port.trim().toIntOrNull() ?: return null
        return if (value in 1..65535) value else null
    }

    private fun addressOf(form: ServerFormState): ServerAddress? {
        val port = portOf(form) ?: return null
        return ServerAddress(form.host.trim(), form.user.trim(), port, form.domain.trim())
    }

    private fun connect(acceptServerGuid: UUID?) {
        val address = addressOf(mutableForm.value) ?: return
        mutableForm.update { it.copy(connecting = true, error = null) }
        scope.launch {
            val typed = readPassword()
            val result = try {
                port.connect(address, typed, acceptServerGuid)
            } finally {
                typed.fill('\u0000')
            }
            when (result) {
                is ConnectResult.Connected -> onConnected(address, result)
                is ConnectResult.Failed -> {
                    val error = result.error
                    acceptGuid = (error as? PlatformConnectError.OtherServer)?.change?.newGuid
                    mutableForm.update { it.copy(connecting = false, error = error.toUi()) }
                }
            }
        }
    }

    private fun onConnected(address: ServerAddress, result: ConnectResult.Connected) {
        password.clear()
        acceptGuid = null
        connectedUser = address.user
        sharesView = result.shares
            ?.let { list -> BrowseContent.Shares(list.map { ShareItem(it.name, it.comment) }) }
            ?: BrowseContent.SharesNotListed
        val server = ConnectedServer(result.server.name, result.server.dialect, result.server.encrypted)
        val step = if (changing) null else SetupStep(2)
        val source = platform.settings.value.source as? SmbSource
        val first = if (changing && source != null && source.sameServerAs(address.host, address.port)) {
            // DS-02: start at the folder of the current file, with the file picked. A file that is gone
            // from the share is not preselected.
            val gone = platform.sync.value.status.lastProblem == SyncProblem.NOT_FOUND
            (listOf(source.share) + source.folderPath) to (if (gone) null else listOf(source.share) + source.filePath)
        } else {
            emptyList<String>() to null
        }
        before = null
        mutableBrowse.value = BrowseState(
            server = server,
            path = emptyList(),
            content = sharesView,
            selected = first.second,
            step = step,
        )
        mutableForm.update { it.copy(connecting = false) }
        mutableScreen.value = SourceFlowScreen.Browse
        openPath(first.first)
    }

    // ---- browser ----

    fun onOpenShare(share: String) = openPath(listOf(share))

    fun onOpenFolder(folder: String) {
        val path = mutableBrowse.value?.path ?: return
        openPath(path + folder)
    }

    /** [depth] 0 = the share list, 1 = the share, 2 = its first folder, ... */
    fun onOpenCrumb(depth: Int) {
        val path = mutableBrowse.value?.path ?: return
        if (depth < 0 || depth > path.size) return
        openPath(path.take(depth))
    }

    fun onSelectFile(path: List<String>) {
        mutableBrowse.update { it?.copy(selected = path) }
    }

    fun onRetry() {
        val path = mutableBrowse.value?.path ?: return
        openPath(path)
    }

    fun onOpenShareDialog() {
        mutableBrowse.update { it?.copy(shareNameDialog = "") }
    }

    fun onShareDialogNameChange(name: String) {
        mutableBrowse.update { if (it?.shareNameDialog != null) it.copy(shareNameDialog = name) else it }
    }

    fun onShareDialogConfirm() {
        val name = mutableBrowse.value?.shareNameDialog?.trim().orEmpty()
        if (name.isEmpty()) return
        mutableBrowse.update { it?.copy(shareNameDialog = null) }
        openPath(listOf(name))
    }

    fun onShareDialogDismiss() {
        mutableBrowse.update { it?.copy(shareNameDialog = null) }
    }

    /**
     * Back inside the flow: one folder up; from the share list to the server
     * form; from the form to the choice (when the flow started there). False when the
     * flow itself is to be left (back on the choice, or on a form the flow started with):
     * the caller handles it (first run: leave the app, change: pop).
     */
    fun onBack(): Boolean {
        when (mutableScreen.value) {
            SourceFlowScreen.Server -> {
                if (start != SourceFlowScreen.Choose) return false
                mutableScreen.value = SourceFlowScreen.Choose
                return true
            }
            SourceFlowScreen.Browse -> Unit
            SourceFlowScreen.Choose, null -> return false
        }
        val browse = mutableBrowse.value
        if (browse == null) {
            mutableScreen.value = SourceFlowScreen.Server
            return true
        }
        if (browse.path.isEmpty()) {
            backToForm()
        } else {
            openPath(browse.path.dropLast(1))
        }
        return true
    }

    /** UI path [share, folders...] to the platform's share and folder list. */
    private fun openPath(path: List<String>) {
        loadJob?.cancel()
        val browse = mutableBrowse.value ?: return
        if (browse.content !is BrowseContent.Loading) before = browse.path to browse.content
        if (path.isEmpty()) {
            mutableBrowse.value = browse.copy(path = path, content = sharesView)
            return
        }
        mutableBrowse.value = browse.copy(path = path, content = BrowseContent.Loading)
        loadJob = scope.launch {
            when (val result = port.listFolder(share = path[0], path = path.drop(1))) {
                is FolderResult.Listed -> mutableBrowse.update {
                    it?.copy(
                        content = BrowseContent.Folder(
                            folders = result.folders,
                            files = result.files.map { file -> file.toUi(time) },
                            hiddenCount = result.hiddenCount,
                        ),
                    )
                }
                is FolderResult.Failed -> onListFailed(result.error, path[0])
            }
        }
    }

    private fun onListFailed(error: BrowseError, share: String) {
        when (error) {
            BrowseError.SHARE_NOT_FOUND -> {
                messages.post(UiMessage.ShareNotFound(share))
                mutableBrowse.update { it?.copy(path = emptyList(), content = sharesView) }
            }
            BrowseError.ACCESS_DENIED -> {
                messages.post(UiMessage.ShareDenied(share, connectedUser))
                val previous = before
                mutableBrowse.update {
                    if (previous != null) {
                        it?.copy(path = previous.first, content = previous.second)
                    } else {
                        it?.copy(path = emptyList(), content = sharesView)
                    }
                }
            }
            // The connection is gone (closed, dropped): the form says so, the password has to be typed again.
            BrowseError.NOT_CONNECTED -> backToForm(ConnectError.Unknown)
            BrowseError.NOT_FOUND,
            BrowseError.UNREACHABLE,
            BrowseError.OTHER,
            -> mutableBrowse.update { it?.copy(content = BrowseContent.Failed) }
        }
    }

    private fun backToForm(error: ConnectError? = null) {
        loadJob?.cancel()
        mutableBrowse.value = null
        mutableForm.update { it.copy(connecting = false, error = error ?: it.error) }
        mutableScreen.value = SourceFlowScreen.Server
    }

    /**
     * "Diese Datei verwenden" / "Speichern": saves the picked file as the source. While unlocked, a file
     * that is older than the open version of the same database asks first (DS-14, [olderDialog]).
     * A change that was started while unlocked is dropped silently when the app locks before it is
     * saved (D10); the platform enforces that for the commit itself.
     */
    fun onUseFile() {
        val selected = mutableBrowse.value?.selected ?: return
        if (selected.size < 2 || choosing) return
        choosing = true
        mutableBrowse.update { it?.copy(saving = true) }
        scope.launch {
            try {
                if (mode == SourceFlowMode.ChangeUnlocked) {
                    val older = try {
                        port.olderVersion(share = selected[0], filePath = selected.drop(1))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No dialog; the switch itself reports a real problem.
                        null
                    }
                    // D10: locked while the check ran (it then finds nothing, too): nothing is saved, no message.
                    if (platform.session.value !is SessionView.Unlocked) return@launch
                    if (older != null) {
                        olderPath = selected
                        mutableOlder.value = toOlderVersionInfo(older, time)
                        return@launch
                    }
                }
                save(selected)
            } finally {
                choosing = false
                mutableBrowse.update { it?.copy(saving = false) }
            }
        }
    }

    /** "Trotzdem verwenden" in the "Ältere Version" dialog. */
    fun onOlderUseAnyway() {
        val selected = olderPath ?: return
        olderPath = null
        mutableOlder.value = null
        if (choosing) return
        choosing = true
        mutableBrowse.update { it?.copy(saving = true) }
        scope.launch {
            try {
                save(selected)
            } finally {
                choosing = false
                mutableBrowse.update { it?.copy(saving = false) }
            }
        }
    }

    /** "Abbrechen" or dismissing the dialog: nothing is saved, the browser stays. */
    fun onOlderCancel() {
        olderPath = null
        mutableOlder.value = null
    }

    private suspend fun save(selected: List<String>) {
        when (val result = port.chooseFile(share = selected[0], filePath = selected.drop(1))) {
            is ChooseResult.Saved -> {
                if (changing) {
                    // A switch that locked the app shows the lock notice only (DS-13).
                    if (!result.lockedForNewDatabase) messages.post(UiMessage.SourceSaved)
                } else {
                    mutableScreen.value = null
                }
                onSaved(result.fileSizeBytes)
            }
            // D10: the app locked before the commit; nothing changed, nothing to say (the unlock screen follows).
            ChooseResult.Discarded -> Unit
            is ChooseResult.Failed -> messages.post(
                when (result.problem) {
                    SyncProblem.ACCESS_DENIED -> UiMessage.ShareDenied(selected[0], connectedUser)
                    SyncProblem.UNREACHABLE -> UiMessage.SyncUnreachable
                    SyncProblem.SERVER_CHANGED,
                    SyncProblem.AUTH_FAILED,
                    SyncProblem.NOT_FOUND,
                    SyncProblem.REMOTE_ERROR,
                    SyncProblem.DOWNLOAD_INCOMPLETE,
                    SyncProblem.INVALID_FILE,
                    SyncProblem.NEW_VERSION_REJECTED,
                    SyncProblem.LOCAL_STORAGE,
                    -> UiMessage.SyncFailed
                },
            )
        }
    }

    /**
     * First run: back from the unlock step. A file source goes back to the choice, an SMB source to the
     * browser while the connection is still open, else to the server form.
     */
    fun reopen() {
        mutableScreen.value = when {
            platform.settings.value.source is FileSource -> SourceFlowScreen.Choose
            mutableBrowse.value != null -> SourceFlowScreen.Browse
            else -> SourceFlowScreen.Server
        }
    }

    /**
     * First run, unlock step (the flow shows nothing): closes the connection, so the SMB password
     * inside it does not stay in memory while the app waits in the background. [reopen] then shows the
     * server form. Does nothing while a screen of the flow is visible.
     */
    fun disconnect() {
        if (mutableScreen.value != null) return
        loadJob?.cancel()
        mutableBrowse.value = null
        mutableForm.update { it.copy(connecting = false) }
        openedPort?.close()
        openedPort = null
    }

    /** Closes the connection and wipes the password field. Call when leaving the flow. */
    override fun close() {
        loadJob?.cancel()
        password.clear()
        openedPort?.close()
        openedPort = null
    }

    private fun initialForm(): ServerFormState {
        if (!changing) return ServerFormState()
        val source = platform.settings.value.source as? SmbSource ?: return ServerFormState(step = null)
        return ServerFormState(
            host = source.host,
            user = source.user,
            port = source.port.toString(),
            domain = source.domain,
            advancedOpen = source.port != 445 || source.domain.isNotEmpty(),
            step = null,
        )
    }
}
