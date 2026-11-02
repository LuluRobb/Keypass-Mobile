package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectError
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.platform.session.SyncResult
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * Scripted [SourceSetupPort]: results come from the queues (the default when a
 * queue is empty), every call is recorded. The password is recorded as a
 * String and not wiped, the caller owns the array.
 */
class FakeSourceSetup : SourceSetupPort {

    data class ConnectCall(val address: ServerAddress, val password: String?, val acceptServerGuid: UUID?)

    data class FolderCall(val share: String, val path: List<String>)

    data class ChooseCall(val share: String, val filePath: List<String>)

    /** Results of connect() and connectWithStoredPassword(), in call order. */
    val connectResults = ArrayDeque<ConnectResult>()
    var defaultConnect: ConnectResult = ConnectResult.Failed(ConnectError.Unknown)
    val folderResults = ArrayDeque<FolderResult>()
    var defaultFolder: FolderResult = FolderResult.Listed(emptyList(), emptyList(), 0)
    val chooseResults = ArrayDeque<ChooseResult>()
    var defaultChoose: ChooseResult = ChooseResult.Saved(fileSizeBytes = 0L, fileChanged = true, sync = SyncResult.UpToDate)

    /** What [canUseStoredPassword] answers. */
    var storedPasswordUsable = false

    /** Set to suspend connect() until the test completes it (busy state). */
    var connectGate: CompletableDeferred<Unit>? = null

    /** Set to suspend chooseFile() until the test completes it (saving state). */
    var chooseGate: CompletableDeferred<Unit>? = null

    /** connect() calls; [ConnectCall.password] is null for connectWithStoredPassword(). */
    val connectCalls = mutableListOf<ConnectCall>()
    val folderCalls = mutableListOf<FolderCall>()
    val chooseCalls = mutableListOf<ChooseCall>()

    var closed = false
        private set

    override suspend fun connect(address: ServerAddress, password: CharArray, acceptServerGuid: UUID?): ConnectResult {
        connectCalls += ConnectCall(address, String(password), acceptServerGuid)
        connectGate?.await()
        return connectResults.removeFirstOrNull() ?: defaultConnect
    }

    override fun canUseStoredPassword(address: ServerAddress): Boolean = storedPasswordUsable

    override suspend fun connectWithStoredPassword(address: ServerAddress, acceptServerGuid: UUID?): ConnectResult {
        connectCalls += ConnectCall(address, null, acceptServerGuid)
        connectGate?.await()
        return connectResults.removeFirstOrNull() ?: defaultConnect
    }

    override suspend fun listFolder(share: String, path: List<String>): FolderResult {
        folderCalls += FolderCall(share, path)
        return folderResults.removeFirstOrNull() ?: defaultFolder
    }

    override suspend fun chooseFile(share: String, filePath: List<String>): ChooseResult {
        chooseCalls += ChooseCall(share, filePath)
        chooseGate?.await()
        return chooseResults.removeFirstOrNull() ?: defaultChoose
    }

    override fun close() {
        closed = true
    }
}
