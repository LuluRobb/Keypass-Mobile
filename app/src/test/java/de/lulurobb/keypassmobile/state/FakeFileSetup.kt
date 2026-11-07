package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.FileCheck
import de.lulurobb.keypassmobile.platform.setup.FileSourceSetup
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.platform.setup.PickedFile
import kotlinx.coroutines.CompletableDeferred

/**
 * Scripted [FileSourceSetup]: results come from the queues (the default when a
 * queue is empty), every call is recorded.
 */
class FakeFileSetup : FileSourceSetup {

    data class InitialFolderCall(val rejectedUri: String?, val changing: Boolean)

    /** Results of accept(), in call order. */
    val acceptResults = ArrayDeque<FileCheck>()
    var defaultAccept: FileCheck = FileCheck.Unreadable("")

    /** Results of olderVersion(), in call order; null (no dialog) when empty. */
    val olderResults = ArrayDeque<OlderVersion?>()
    val chooseResults = ArrayDeque<ChooseResult>()
    var defaultChoose: ChooseResult = ChooseResult.Saved(fileSizeBytes = 0L, fileChanged = true, sync = SyncResult.UpToDate)

    /** The start folder handed out by initialFolder(). */
    var initialFolderFor: (rejectedUri: String?, changing: Boolean) -> String = { _, _ -> "content://fake.documents/document/primary%3A" }

    /** Set to suspend accept(), olderVersion() or choose() until the test completes it (busy state). */
    var acceptGate: CompletableDeferred<Unit>? = null
    var olderGate: CompletableDeferred<Unit>? = null
    var chooseGate: CompletableDeferred<Unit>? = null
    var initialFolderGate: CompletableDeferred<Unit>? = null

    val initialFolderCalls = mutableListOf<InitialFolderCall>()
    val acceptCalls = mutableListOf<String>()
    val olderCalls = mutableListOf<PickedFile>()
    val chooseCalls = mutableListOf<PickedFile>()
    val discarded = mutableListOf<String>()

    var closed = false
        private set

    override suspend fun initialFolder(rejectedUri: String?, changing: Boolean): String {
        initialFolderCalls += InitialFolderCall(rejectedUri, changing)
        initialFolderGate?.await()
        return initialFolderFor(rejectedUri, changing)
    }

    override suspend fun accept(uri: String): FileCheck {
        acceptCalls += uri
        acceptGate?.await()
        return acceptResults.removeFirstOrNull() ?: defaultAccept
    }

    override suspend fun olderVersion(file: PickedFile): OlderVersion? {
        olderCalls += file
        olderGate?.await()
        return olderResults.removeFirstOrNull()
    }

    override suspend fun choose(file: PickedFile): ChooseResult {
        chooseCalls += file
        chooseGate?.await()
        return chooseResults.removeFirstOrNull() ?: defaultChoose
    }

    override fun discard(uri: String) {
        discarded += uri
    }

    override fun close() {
        closed = true
    }
}
