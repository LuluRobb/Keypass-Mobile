package de.lulurobb.keypassmobile.ui.setup

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.setup.BrowseError
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectError as PlatformConnectError
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SourceSetupPort
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.toUi
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.SecretInput
import de.lulurobb.keypassmobile.ui.components.SetupStep
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

    /** "Quelle ändern" from the settings while unlocked. */
    ChangeUnlocked,

    /** "Quelle ändern" while locked (unlock screen). */
    ChangeLocked,
}

/** Which screen of the flow is showing; null in [SourceFlowModel.screen] = the flow shows nothing. */
enum class SourceFlowScreen { Server, Browse }

/**
 * State holder of the server form (setup step 1 / "Quelle ändern") and the
 * file browser (step 2), over one [SourceSetupPort] that is opened with the
 * first use. The connection (and with it a copy of the SMB password inside
 * :platform) lives until [close].
 *
 * [onSaved] is called with the downloaded file size after chooseFile saved the
 * source. In [SourceFlowMode.FirstRun] [screen] becomes null then and the
 * connection stays open, so H can come back with [reopen] from the unlock step;
 * the change modes post "Quelle gespeichert" and H leaves the flow.
 */
class SourceFlowModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    private val mode: SourceFlowMode,
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

    private val mutableScreen = MutableStateFlow<SourceFlowScreen?>(SourceFlowScreen.Server)

    /** Server form or browser; null after the first-run file was saved (until [reopen]). */
    val screen: StateFlow<SourceFlowScreen?> = mutableScreen.asStateFlow()

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
        val source = platform.settings.value.source
        val start = if (changing && source != null && source.sameServerAs(address.host, address.port)) {
            // DS-02: start at the folder of the current file, with the file picked.
            (listOf(source.share) + source.folderPath) to (listOf(source.share) + source.filePath)
        } else {
            emptyList<String>() to null
        }
        before = null
        mutableBrowse.value = BrowseState(
            server = server,
            path = emptyList(),
            content = sharesView,
            selected = start.second,
            step = step,
        )
        mutableForm.update { it.copy(connecting = false) }
        mutableScreen.value = SourceFlowScreen.Browse
        openPath(start.first)
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
     * form. False when the flow itself is to be left (back on the form): the
     * caller handles it (first run: leave the app, change: pop).
     */
    fun onBack(): Boolean {
        if (mutableScreen.value != SourceFlowScreen.Browse) return false
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

    /** "Diese Datei verwenden" / "Speichern": saves the picked file as the source. */
    fun onUseFile() {
        val selected = mutableBrowse.value?.selected ?: return
        if (selected.size < 2 || choosing) return
        choosing = true
        mutableBrowse.update { it?.copy(saving = true) }
        scope.launch {
            try {
                when (val result = port.chooseFile(share = selected[0], filePath = selected.drop(1))) {
                    is ChooseResult.Saved -> {
                        if (changing) {
                            messages.post(UiMessage.SourceSaved)
                        } else {
                            mutableScreen.value = null
                        }
                        onSaved(result.fileSizeBytes)
                    }
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
            } finally {
                choosing = false
                mutableBrowse.update { it?.copy(saving = false) }
            }
        }
    }

    /** First run: back from the unlock step to the browser (the connection is still open). */
    fun reopen() {
        mutableScreen.value = if (mutableBrowse.value != null) SourceFlowScreen.Browse else SourceFlowScreen.Server
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
        val source = platform.settings.value.source ?: return ServerFormState(step = null)
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
