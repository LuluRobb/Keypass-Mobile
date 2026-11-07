package de.lulurobb.keypassmobile.ui.setup

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.platform.setup.BrowseError
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectError as PlatformConnectError
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.KdbxFileEntry
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.platform.setup.ServerInfo
import de.lulurobb.keypassmobile.platform.setup.ShareEntry
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.FakeSourceSetup
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.testSyncInfo
import de.lulurobb.keypassmobile.state.testVault
import de.lulurobb.keypassmobile.state.unlockedView
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SourceFlowModelTest {

    // Unconfined: launched work runs at once up to its first real suspension (the gates).
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val messages = AppMessages()
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-03-06T00:00:00Z"), ZoneOffset.UTC)) { ZoneOffset.UTC }
    private val setup = FakeSourceSetup()
    private val platform = FakeAppPlatform().also { it.nextSetup = { setup } }
    private val saved = mutableListOf<Long?>()

    private val serverGuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val server = ServerInfo(name = "reitano-nas", guid = serverGuid, dialect = "SMB 3.1.1", encrypted = true)
    private val docsShares = listOf(ShareEntry("Docs", "Dokumente"), ShareEntry("Fotos", ""))

    private fun source(host: String = "nas.local", port: Int = 445, domain: String = "") = SmbSource(
        host = host,
        port = port,
        domain = domain,
        user = "luca",
        share = "Docs",
        filePath = listOf("KeePass", "Db.kdbx"),
    )

    /** [start] is the server form here: most tests are about the form; the choice has its own tests below. */
    private fun model(
        mode: SourceFlowMode = SourceFlowMode.FirstRun,
        source: SmbSource? = null,
        start: SourceFlowScreen = SourceFlowScreen.Server,
        onPickFile: () -> Unit = {},
    ): SourceFlowModel {
        platform.settingsFlow.value = KpmSettings(source = source)
        // The unlocked change flow only exists while the app is unlocked.
        if (mode == SourceFlowMode.ChangeUnlocked) platform.sessionFlow.value = unlockedView(testVault(emptyList()))
        return SourceFlowModel(platform, scope, time, messages, mode, start, onPickFile) { saved += it }
    }

    /** First-run model with a filled form and a typed password. */
    private fun filled(password: String = "geheim"): SourceFlowModel = model().also { fill(it, password) }

    private fun fill(model: SourceFlowModel, password: String?) {
        model.onHostChange("nas.local")
        model.onUserChange("luca")
        model.readPassword = { (password ?: "").toCharArray() }
    }

    /** Connected first-run model at the share list. */
    private fun connectedFirstRun(shares: List<ShareEntry>? = docsShares): SourceFlowModel {
        setup.connectResults += ConnectResult.Connected(server, shares)
        return filled().also { it.onConnect() }
    }

    private fun connectedChange(mode: SourceFlowMode, source: SmbSource = source()): SourceFlowModel {
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = model(mode, source)
        model.readPassword = { "geheim".toCharArray() }
        model.onConnect()
        return model
    }

    private fun listed(folders: List<String> = emptyList(), files: List<KdbxFileEntry> = emptyList(), hidden: Int = 0) =
        FolderResult.Listed(folders, files, hidden)

    private fun kdbx(name: String, size: Long = 1000) =
        KdbxFileEntry(name, size, Instant.parse("2026-03-05T10:15:00Z"))

    // ---- form ----

    @Test
    fun `first run starts on step 1 with an empty form and the server screen`() {
        val model = model()
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertEquals(ServerFormState(), model.form.value)
        assertEquals(SetupStep(1), model.form.value.step)
        assertNull(model.browse.value)
    }

    @Test
    fun `change modes prefill the form from the source without a step`() {
        for (mode in listOf(SourceFlowMode.ChangeUnlocked, SourceFlowMode.ChangeLocked)) {
            val form = model(mode, source(host = "nas.fritz.box", port = 4455, domain = "HOME")).form.value
            assertEquals("nas.fritz.box", form.host)
            assertEquals("luca", form.user)
            assertEquals("4455", form.port)
            assertEquals("HOME", form.domain)
            assertTrue(form.advancedOpen)
            assertNull(form.step)
        }
        val plain = model(SourceFlowMode.ChangeUnlocked, source()).form.value
        assertEquals("445", plain.port)
        assertFalse(plain.advancedOpen)
    }

    @Test
    fun `blank host and user and a bad port are reported and nothing is sent`() {
        val model = model()
        model.onPortChange("abc")
        model.onConnect()
        assertEquals(setOf(ServerField.Host, ServerField.User, ServerField.Port), model.form.value.invalidFields)
        assertTrue(model.form.value.advancedOpen)
        assertFalse(model.form.value.connecting)
        assertTrue(setup.connectCalls.isEmpty())
        assertTrue(platform.calls.none { it == "startSourceSetup" })
    }

    @Test
    fun `host and user of only blanks are invalid`() {
        val model = model()
        model.onHostChange("  ")
        model.onUserChange(" ")
        model.onConnect()
        assertEquals(setOf(ServerField.Host, ServerField.User), model.form.value.invalidFields)
    }

    @Test
    fun `port must be a number from 1 to 65535`() {
        val model = filled()
        for (bad in listOf("", "0", "65536", "-1", "44 5", "4.5")) {
            model.onPortChange(bad)
            model.onConnect()
            assertEquals(setOf(ServerField.Port), model.form.value.invalidFields, "port '$bad'")
        }
        assertTrue(setup.connectCalls.isEmpty())
        for (good in listOf("1", "445", "65535")) {
            setup.connectResults += ConnectResult.Failed(PlatformConnectError.Unreachable)
            model.onPortChange(good)
            model.onConnect()
            assertTrue(model.form.value.invalidFields.isEmpty(), "port '$good'")
        }
        assertEquals(3, setup.connectCalls.size)
    }

    @Test
    fun `editing a field removes its error only`() {
        val model = model()
        model.onConnect()
        assertEquals(setOf(ServerField.Host, ServerField.User), model.form.value.invalidFields)
        model.onHostChange("nas")
        assertEquals(setOf(ServerField.User), model.form.value.invalidFields)
    }

    @Test
    fun `address is trimmed and the port converted`() {
        val model = model()
        model.onHostChange(" nas.local ")
        model.onUserChange(" luca ")
        model.onPortChange(" 4455 ")
        model.onDomainChange(" HOME ")
        model.readPassword = { "geheim".toCharArray() }
        model.onConnect()
        assertEquals(ServerAddress("nas.local", "luca", 4455, "HOME"), setup.connectCalls.single().address)
    }

    @Test
    fun `toggle advanced flips the flag`() {
        val model = model()
        model.onToggleAdvanced()
        assertTrue(model.form.value.advancedOpen)
        model.onToggleAdvanced()
        assertFalse(model.form.value.advancedOpen)
    }

    // ---- connect ----

    @Test
    fun `connect success shows the share list with the server`() {
        val model = connectedFirstRun()
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        assertFalse(model.form.value.connecting)
        val browse = assertNotNullState(model)
        assertEquals(ConnectedServer("reitano-nas", "SMB 3.1.1", true), browse.server)
        assertEquals(emptyList(), browse.path)
        assertEquals(
            BrowseContent.Shares(listOf(ShareItem("Docs", "Dokumente"), ShareItem("Fotos", ""))),
            browse.content,
        )
        assertEquals(SetupStep(2), browse.step)
        assertNull(browse.selected)
        assertEquals("geheim", setup.connectCalls.single().password)
        assertTrue(setup.folderCalls.isEmpty())
    }

    @Test
    fun `connect without a share list offers the manual entry`() {
        val model = connectedFirstRun(shares = null)
        assertEquals(BrowseContent.SharesNotListed, assertNotNullState(model).content)
    }

    @Test
    fun `connect is busy while it runs and a second tap is ignored`() {
        val gate = CompletableDeferred<Unit>()
        setup.connectGate = gate
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = filled()
        model.onConnect()
        assertTrue(model.form.value.connecting)
        model.onConnect()
        assertEquals(1, setup.connectCalls.size)
        gate.complete(Unit)
        assertFalse(model.form.value.connecting)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
    }

    @Test
    fun `connect failures map to the form error and keep the form`() {
        val expected = listOf(
            PlatformConnectError.Unreachable to ConnectError.Unreachable,
            PlatformConnectError.LoginFailed to ConnectError.LoginFailed,
            PlatformConnectError.Unsupported to ConnectError.Unsupported,
            PlatformConnectError.Unknown to ConnectError.Unknown,
        )
        for ((platformError, uiError) in expected) {
            setup.connectResults += ConnectResult.Failed(platformError)
            val model = filled()
            model.onConnect()
            assertEquals(uiError, model.form.value.error)
            assertFalse(model.form.value.connecting)
            assertEquals(SourceFlowScreen.Server, model.screen.value)
            assertNull(model.browse.value)
        }
    }

    @Test
    fun `a new edit removes the error`() {
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.LoginFailed)
        val model = filled()
        model.onConnect()
        assertEquals(ConnectError.LoginFailed, model.form.value.error)
        model.onUserChange("anna")
        assertNull(model.form.value.error)
    }

    @Test
    fun `other server is shown with the remembered name and accepting connects again with the new guid`() {
        val newGuid = UUID.fromString("99999999-8888-7777-6666-555555555555")
        val change = ServerChange("nas.local", UUID.randomUUID(), "altes-nas", newGuid, "neues-nas")
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.OtherServer(change))
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = filled()
        model.onConnect()
        assertEquals(ConnectError.OtherServer("altes-nas"), model.form.value.error)
        assertFalse(model.form.value.connecting)

        model.onAcceptOtherServer()

        assertEquals(2, setup.connectCalls.size)
        val second = setup.connectCalls[1]
        assertEquals(newGuid, second.acceptServerGuid)
        assertEquals("geheim", second.password)
        assertNull(setup.connectCalls[0].acceptServerGuid)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
    }

    @Test
    fun `accepting without an open question does nothing`() {
        val model = filled()
        model.onAcceptOtherServer()
        assertTrue(setup.connectCalls.isEmpty())
    }

    @Test
    fun `editing the address drops the open server question`() {
        val change = ServerChange("nas.local", null, null, UUID.randomUUID(), null)
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.OtherServer(change))
        val model = filled()
        model.onConnect()
        model.onHostChange("andere-nas")
        model.onAcceptOtherServer()
        assertEquals(1, setup.connectCalls.size)
    }

    // ---- SMB password: always typed ----

    @Test
    fun `every mode connects with the typed password, also an empty one`() {
        for (mode in SourceFlowMode.entries) {
            val fake = FakeSourceSetup()
            platform.nextSetup = { fake }
            fake.connectResults += ConnectResult.Connected(server, docsShares)
            val model = model(mode, if (mode == SourceFlowMode.FirstRun) null else source())
            fill(model, password = null)
            model.onConnect()
            val call = fake.connectCalls.single()
            assertEquals("", call.password, "$mode: the empty password is sent as typed")
            assertEquals(ServerAddress("nas.local", "luca", 445, ""), call.address, "$mode")
        }
    }

    @Test
    fun `a typed password is sent`() {
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = model(SourceFlowMode.ChangeUnlocked, source())
        model.readPassword = { "neu".toCharArray() }
        model.onConnect()
        assertEquals("neu", setup.connectCalls.single().password)
    }

    @Test
    fun `the source setup is opened only by the connect`() {
        val model = model(SourceFlowMode.ChangeUnlocked, source())
        model.onHostChange("andere-nas")
        model.onPortChange("4455")
        assertTrue(platform.calls.none { it == "startSourceSetup" })
    }

    @Test
    fun `another server is accepted with the typed password`() {
        val change = ServerChange("nas.local", null, null, UUID.randomUUID(), null)
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.OtherServer(change))
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = model(SourceFlowMode.ChangeUnlocked, source())
        model.readPassword = { "geheim".toCharArray() }
        model.onConnect()
        model.onAcceptOtherServer()
        assertEquals(listOf("geheim", "geheim"), setup.connectCalls.map { it.password })
        assertEquals(change.newGuid, setup.connectCalls[1].acceptServerGuid)
    }

    // ---- browse: paths ----

    @Test
    fun `ui path is share plus folders and maps to share and folder list`() {
        setup.folderResults += listed(folders = listOf("KeePass"))
        setup.folderResults += listed(
            folders = listOf("Alt"),
            files = listOf(kdbx("Db.kdbx", 2048)),
            hidden = 3,
        )
        val model = connectedFirstRun()

        model.onOpenShare("Docs")
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertEquals(BrowseContent.Folder(listOf("KeePass"), emptyList(), 0), model.browse.value?.content)

        model.onOpenFolder("KeePass")
        assertEquals(listOf("Docs", "KeePass"), model.browse.value?.path)
        assertEquals(
            BrowseContent.Folder(
                folders = listOf("Alt"),
                files = listOf(KdbxFile("Db.kdbx", 2048, LocalDateTime.of(2026, 3, 5, 10, 15))),
                hiddenCount = 3,
            ),
            model.browse.value?.content,
        )
        assertEquals(
            listOf(FakeSourceSetup.FolderCall("Docs", emptyList()), FakeSourceSetup.FolderCall("Docs", listOf("KeePass"))),
            setup.folderCalls,
        )
    }

    @Test
    fun `crumbs go to an earlier depth and depth 0 shows the share list again`() {
        val model = connectedFirstRun()
        model.onOpenShare("Docs")
        model.onOpenFolder("A")
        model.onOpenFolder("B")
        assertEquals(listOf("Docs", "A", "B"), model.browse.value?.path)
        val calls = setup.folderCalls.size

        model.onOpenCrumb(2)
        assertEquals(listOf("Docs", "A"), model.browse.value?.path)
        assertEquals(FakeSourceSetup.FolderCall("Docs", listOf("A")), setup.folderCalls.last())

        model.onOpenCrumb(1)
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertEquals(FakeSourceSetup.FolderCall("Docs", emptyList()), setup.folderCalls.last())

        model.onOpenCrumb(0)
        assertEquals(emptyList(), model.browse.value?.path)
        assertIs<BrowseContent.Shares>(model.browse.value?.content)
        assertEquals(calls + 2, setup.folderCalls.size)
    }

    @Test
    fun `select file keeps the full path and survives navigation`() {
        val model = connectedFirstRun()
        model.onOpenShare("Docs")
        model.onSelectFile(listOf("Docs", "Db.kdbx"))
        assertEquals(listOf("Docs", "Db.kdbx"), model.browse.value?.selected)
        model.onOpenFolder("A")
        assertEquals(listOf("Docs", "Db.kdbx"), model.browse.value?.selected)
    }

    @Test
    fun `manual share name opens the trimmed share`() {
        val model = connectedFirstRun(shares = null)
        model.onOpenShareDialog()
        assertEquals("", model.browse.value?.shareNameDialog)
        model.onShareDialogNameChange("  Docs ")
        assertEquals("  Docs ", model.browse.value?.shareNameDialog)
        model.onShareDialogConfirm()
        assertNull(model.browse.value?.shareNameDialog)
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertEquals(FakeSourceSetup.FolderCall("Docs", emptyList()), setup.folderCalls.single())
    }

    @Test
    fun `manual share dialog ignores a blank name and can be dismissed`() {
        val model = connectedFirstRun(shares = null)
        model.onOpenShareDialog()
        model.onShareDialogNameChange("   ")
        model.onShareDialogConfirm()
        assertEquals("   ", model.browse.value?.shareNameDialog)
        assertTrue(setup.folderCalls.isEmpty())
        model.onShareDialogDismiss()
        assertNull(model.browse.value?.shareNameDialog)
    }

    // ---- browse: errors ----

    @Test
    fun `share not found posts the message and returns to the share list`() {
        val model = connectedFirstRun()
        setup.folderResults += FolderResult.Failed(BrowseError.SHARE_NOT_FOUND)
        model.onOpenShare("Gibtsnicht")
        assertEquals(UiMessage.ShareNotFound("Gibtsnicht"), messages.message.value)
        assertEquals(emptyList(), model.browse.value?.path)
        assertIs<BrowseContent.Shares>(model.browse.value?.content)
    }

    @Test
    fun `share not found of a manual share returns to the manual entry`() {
        val model = connectedFirstRun(shares = null)
        setup.folderResults += FolderResult.Failed(BrowseError.SHARE_NOT_FOUND)
        model.onOpenShare("X")
        assertEquals(BrowseContent.SharesNotListed, model.browse.value?.content)
    }

    @Test
    fun `access denied posts the message and stays at the previous path`() {
        setup.folderResults += listed(folders = listOf("Privat"))
        val model = connectedFirstRun()
        model.onOpenShare("Docs")
        setup.folderResults += FolderResult.Failed(BrowseError.ACCESS_DENIED)

        model.onOpenFolder("Privat")

        assertEquals(UiMessage.ShareDenied("Docs", "luca"), messages.message.value)
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertEquals(BrowseContent.Folder(listOf("Privat"), emptyList(), 0), model.browse.value?.content)
    }

    @Test
    fun `access denied on a share returns to the share list`() {
        val model = connectedFirstRun()
        setup.folderResults += FolderResult.Failed(BrowseError.ACCESS_DENIED)
        model.onOpenShare("Docs")
        assertEquals(UiMessage.ShareDenied("Docs", "luca"), messages.message.value)
        assertEquals(emptyList(), model.browse.value?.path)
        assertIs<BrowseContent.Shares>(model.browse.value?.content)
    }

    @Test
    fun `not connected goes back to the server form`() {
        val model = connectedFirstRun()
        setup.folderResults += FolderResult.Failed(BrowseError.NOT_CONNECTED)
        model.onOpenShare("Docs")
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertNull(model.browse.value)
        assertFalse(model.form.value.connecting)
    }

    @Test
    fun `other errors show the failed content and retry reloads the same path`() {
        for (error in listOf(BrowseError.NOT_FOUND, BrowseError.UNREACHABLE, BrowseError.OTHER)) {
            setup.connectResults += ConnectResult.Connected(server, docsShares)
            val model = filled().also { it.onConnect() }
            setup.folderResults += FolderResult.Failed(error)
            model.onOpenShare("Docs")
            assertEquals(BrowseContent.Failed, model.browse.value?.content, "$error")
            assertEquals(listOf("Docs"), model.browse.value?.path)
            assertNull(messages.message.value)

            setup.folderResults += listed(folders = listOf("KeePass"))
            model.onRetry()
            assertEquals(BrowseContent.Folder(listOf("KeePass"), emptyList(), 0), model.browse.value?.content)
            assertEquals(
                listOf(FakeSourceSetup.FolderCall("Docs", emptyList()), FakeSourceSetup.FolderCall("Docs", emptyList())),
                setup.folderCalls.takeLast(2),
            )
        }
    }

    // ---- change modes: start path ----

    @Test
    fun `change modes start in the folder of the current file with the file picked`() {
        for (mode in listOf(SourceFlowMode.ChangeUnlocked, SourceFlowMode.ChangeLocked)) {
            setup.folderCalls.clear()
            setup.connectResults.clear()
            setup.folderResults.clear()
            setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
            val model = connectedChange(mode)
            val browse = assertNotNullState(model)
            assertEquals(listOf("Docs", "KeePass"), browse.path, "$mode")
            assertEquals(listOf("Docs", "KeePass", "Db.kdbx"), browse.selected)
            assertNull(browse.step)
            assertIs<BrowseContent.Folder>(browse.content)
            assertEquals(listOf(FakeSourceSetup.FolderCall("Docs", listOf("KeePass"))), setup.folderCalls)
        }
    }

    @Test
    fun `change mode with another host than the source starts at the share list`() {
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        val model = model(SourceFlowMode.ChangeUnlocked, source(host = "anderes-nas"))
        model.onHostChange("neues-nas")
        model.readPassword = { "geheim".toCharArray() }
        model.onConnect()
        val browse = assertNotNullState(model)
        assertEquals(emptyList(), browse.path)
        assertNull(browse.selected)
        assertIs<BrowseContent.Shares>(browse.content)
        assertTrue(setup.folderCalls.isEmpty())
    }

    @Test
    fun `change mode start folder that is denied falls back to the share list`() {
        setup.folderResults += FolderResult.Failed(BrowseError.ACCESS_DENIED)
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        assertEquals(UiMessage.ShareDenied("Docs", "luca"), messages.message.value)
        assertEquals(emptyList(), model.browse.value?.path)
        assertIs<BrowseContent.Shares>(model.browse.value?.content)
    }

    // ---- back ----

    @Test
    fun `back goes one folder up and from the share list to the form`() {
        val model = connectedFirstRun()
        model.onOpenShare("Docs")
        model.onOpenFolder("A")
        model.onOpenFolder("B")

        assertTrue(model.onBack())
        assertEquals(listOf("Docs", "A"), model.browse.value?.path)
        assertTrue(model.onBack())
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertTrue(model.onBack())
        assertEquals(emptyList(), model.browse.value?.path)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)

        assertTrue(model.onBack())
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertNull(model.browse.value)
    }

    @Test
    fun `back on the form is left to the caller`() {
        val model = model()
        assertFalse(model.onBack())
        assertEquals(SourceFlowScreen.Server, model.screen.value)
    }

    @Test
    fun `connecting again after going back works`() {
        val model = connectedFirstRun()
        model.onBack()
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        model.onConnect()
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        assertEquals(2, setup.connectCalls.size)
    }

    // ---- choose file ----

    @Test
    fun `first run saves the file, leaves the flow and keeps the connection`() {
        val model = connectedFirstRun()
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        model.onOpenShare("Docs")
        model.onSelectFile(listOf("Docs", "Db.kdbx"))
        setup.chooseResults += ChooseResult.Saved(fileSizeBytes = 4096, fileChanged = true, sync = SyncResult.CandidateReady)

        model.onUseFile()

        assertEquals(listOf(FakeSourceSetup.ChooseCall("Docs", listOf("Db.kdbx"))), setup.chooseCalls)
        assertEquals(listOf<Long?>(4096), saved)
        assertNull(model.screen.value)
        assertNull(messages.message.value)
        assertFalse(setup.closed)

        model.reopen()
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        assertEquals(listOf("Docs"), model.browse.value?.path)
        assertEquals(listOf("Docs", "Db.kdbx"), model.browse.value?.selected)
    }

    @Test
    fun `change modes post source saved and report the size`() {
        for (mode in listOf(SourceFlowMode.ChangeUnlocked, SourceFlowMode.ChangeLocked)) {
            saved.clear()
            messages.dismiss()
            setup.chooseCalls.clear()
            setup.folderResults.clear()
            setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
            val model = connectedChange(mode)
            setup.chooseResults += ChooseResult.Saved(fileSizeBytes = 777, fileChanged = false, sync = SyncResult.UpToDate)

            model.onUseFile()

            assertEquals(
                listOf(FakeSourceSetup.ChooseCall("Docs", listOf("KeePass", "Db.kdbx"))),
                setup.chooseCalls,
                "$mode",
            )
            assertEquals(UiMessage.SourceSaved, messages.message.value)
            assertEquals(listOf<Long?>(777), saved)
            // H leaves and closes; the model itself keeps showing the browser.
            assertEquals(SourceFlowScreen.Browse, model.screen.value)
        }
    }

    @Test
    fun `choose failures post a message and save nothing`() {
        val expected = mapOf(
            SyncProblem.ACCESS_DENIED to UiMessage.ShareDenied("Docs", "luca"),
            SyncProblem.UNREACHABLE to UiMessage.SyncUnreachable,
            SyncProblem.REMOTE_ERROR to UiMessage.SyncFailed,
            SyncProblem.INVALID_FILE to UiMessage.SyncFailed,
            SyncProblem.NOT_FOUND to UiMessage.SyncFailed,
        )
        for ((problem, message) in expected) {
            messages.dismiss()
            saved.clear()
            val model = connectedFirstRun()
            model.onSelectFile(listOf("Docs", "Db.kdbx"))
            setup.chooseResults += ChooseResult.Failed(problem)
            model.onUseFile()
            assertEquals(message, messages.message.value, "$problem")
            assertTrue(saved.isEmpty())
            assertEquals(SourceFlowScreen.Browse, model.screen.value)
        }
    }

    @Test
    fun `use file without a picked file does nothing`() {
        val model = connectedFirstRun()
        model.onUseFile()
        assertTrue(setup.chooseCalls.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a picked share without a file is not saved`() {
        val model = connectedFirstRun()
        model.onSelectFile(listOf("Docs"))
        model.onUseFile()
        assertTrue(setup.chooseCalls.isEmpty())
    }

    // ---- close ----

    @Test
    fun `close closes the port that was opened`() {
        val model = connectedFirstRun()
        assertFalse(setup.closed)
        model.close()
        assertTrue(setup.closed)
    }

    @Test
    fun `close before anything opened a port opens none`() {
        val model = model()
        model.close()
        assertTrue(platform.setups.isEmpty())
    }

    @Test
    fun `reopen without a connection shows the form`() {
        val model = model()
        model.reopen()
        assertEquals(SourceFlowScreen.Server, model.screen.value)
    }

    // ---- password edit ----

    @Test
    fun `a password edit removes the error and the server question`() {
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.LoginFailed)
        val model = filled()
        model.onConnect()
        assertEquals(ConnectError.LoginFailed, model.form.value.error)

        model.onPasswordChange()
        assertNull(model.form.value.error)

        // The server question goes with its notice: accepting does nothing any more.
        val other = filled()
        setup.connectResults += ConnectResult.Failed(PlatformConnectError.OtherServer(ServerChange("nas.local", null, null, UUID.randomUUID(), null)))
        other.onConnect()
        assertIs<ConnectError.OtherServer>(other.form.value.error)
        other.onPasswordChange()
        assertNull(other.form.value.error)
        val calls = setup.connectCalls.size
        other.onAcceptOtherServer()
        assertEquals(calls, setup.connectCalls.size)
    }

    @Test
    fun `a password edit without an error leaves the form as it is`() {
        val model = filled()
        val before = model.form.value
        model.onPasswordChange()
        assertEquals(before, model.form.value)
    }

    // ---- lost connection ----

    @Test
    fun `not connected shows the connect error on the form`() {
        val model = connectedFirstRun()
        setup.folderResults += FolderResult.Failed(BrowseError.NOT_CONNECTED)
        model.onOpenShare("Docs")
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertEquals(ConnectError.Unknown, model.form.value.error)
    }

    @Test
    fun `back to the form by the user shows no error`() {
        val model = connectedFirstRun()
        assertTrue(model.onBack())
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertNull(model.form.value.error)
    }

    // ---- saving ----

    @Test
    fun `the browser is saving while the file is saved and ignores a second tap`() {
        val model = connectedFirstRun()
        model.onSelectFile(listOf("Docs", "Db.kdbx"))
        assertFalse(assertNotNullState(model).saving)
        val gate = CompletableDeferred<Unit>()
        setup.chooseGate = gate

        model.onUseFile()
        assertTrue(assertNotNullState(model).saving)
        model.onUseFile()
        assertEquals(1, setup.chooseCalls.size)

        gate.complete(Unit)
        assertFalse(assertNotNullState(model).saving)
        assertEquals(listOf<Long?>(0L), saved)
    }

    @Test
    fun `a failed save ends the saving state and keeps the selection`() {
        val model = connectedFirstRun()
        model.onSelectFile(listOf("Docs", "Db.kdbx"))
        val gate = CompletableDeferred<Unit>()
        setup.chooseGate = gate
        setup.chooseResults += ChooseResult.Failed(SyncProblem.UNREACHABLE)

        model.onUseFile()
        assertTrue(assertNotNullState(model).saving)
        gate.complete(Unit)

        val state = assertNotNullState(model)
        assertFalse(state.saving)
        assertEquals(listOf("Docs", "Db.kdbx"), state.selected)
        assertEquals(UiMessage.SyncUnreachable, messages.message.value)
    }

    // ---- disconnect ----

    @Test
    fun `disconnect at the unlock step closes the connection and reopen shows the form`() {
        val model = connectedFirstRun()
        model.onSelectFile(listOf("Docs", "Db.kdbx"))
        model.onUseFile()
        assertNull(model.screen.value)
        assertFalse(setup.closed)

        model.disconnect()
        assertTrue(setup.closed)
        assertNull(model.browse.value)
        assertNull(model.screen.value)

        model.reopen()
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertNull(model.form.value.error)
        // The next connect opens a new connection.
        val opened = platform.setups.size
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        fill(model, "geheim")
        model.onConnect()
        assertEquals(opened + 1, platform.setups.size)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
    }

    @Test
    fun `disconnect does nothing while a screen of the flow shows`() {
        val model = connectedFirstRun()
        model.disconnect()
        assertFalse(setup.closed)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        assertTrue(model.browse.value != null)

        assertTrue(model.onBack())
        model.disconnect()
        assertFalse(setup.closed)
    }

    // ---- source choice ----

    private val fileSource = FileSource(
        uri = "content://docs/document/primary%3ASyncthing%2FPass.kdbx",
        displayName = "Pass.kdbx",
        place = "Interner Speicher / Syncthing",
    )

    private fun choiceModel(
        mode: SourceFlowMode = SourceFlowMode.FirstRun,
        source: SourceConfig? = null,
        start: SourceFlowScreen = SourceFlowScreen.Choose,
        onPickFile: () -> Unit = {},
    ): SourceFlowModel {
        platform.settingsFlow.value = KpmSettings(source = source)
        if (mode == SourceFlowMode.ChangeUnlocked) platform.sessionFlow.value = unlockedView(testVault(emptyList()))
        return SourceFlowModel(platform, scope, time, messages, mode, start, onPickFile) { saved += it }
    }

    @Test
    fun `a flow starts on the choice by default and keeps the form empty`() {
        val model = choiceModel()
        assertEquals(SourceFlowScreen.Choose, model.screen.value)
        assertEquals(ServerFormState(), model.form.value)
        assertNull(model.manageKind)
    }

    @Test
    fun `the manage rows depend on the kind of the current source`() {
        assertEquals(SourceKind.SMB, choiceModel(SourceFlowMode.ChangeUnlocked, source()).manageKind)
        assertEquals(SourceKind.FILE, choiceModel(SourceFlowMode.ChangeUnlocked, fileSource).manageKind)
        assertEquals(SourceKind.FILE, choiceModel(SourceFlowMode.ChangeLocked, fileSource).manageKind)
        // The first run shows the two option cards, also when the first-run file is saved and the user went back.
        assertNull(choiceModel(SourceFlowMode.FirstRun, fileSource).manageKind)
    }

    @Test
    fun `the choice opens the server form or the picker`() {
        var picks = 0
        val model = choiceModel(onPickFile = { picks++ })
        model.onChooseFile()
        assertEquals(1, picks)
        assertEquals(SourceFlowScreen.Choose, model.screen.value)
        model.onChooseSmb()
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        // Not on the choice any more: no second picker.
        model.onChooseFile()
        assertEquals(1, picks)
    }

    @Test
    fun `the server form of a change is filled from an SMB source and empty for a file source`() {
        val filled = choiceModel(SourceFlowMode.ChangeUnlocked, source())
        filled.onChooseSmb()
        assertEquals("nas.local", filled.form.value.host)
        assertEquals("luca", filled.form.value.user)
        // DS-03: the password is never prefilled.
        assertEquals(0, filled.password.length)

        val empty = choiceModel(SourceFlowMode.ChangeUnlocked, fileSource)
        empty.onChooseSmb()
        assertEquals(ServerFormState(step = null), empty.form.value)
    }

    @Test
    fun `back goes from the form to the choice and from the choice to the caller`() {
        val model = choiceModel(SourceFlowMode.ChangeUnlocked, source())
        model.onChooseSmb()
        assertTrue(model.onBack())
        assertEquals(SourceFlowScreen.Choose, model.screen.value)
        assertFalse(model.onBack())
        assertEquals(SourceFlowScreen.Choose, model.screen.value)
    }

    @Test
    fun `back through the browser of the first run ends on the choice`() {
        val model = choiceModel()
        model.onChooseSmb()
        fill(model, "geheim")
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        model.onConnect()
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        assertTrue(model.onBack())
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertTrue(model.onBack())
        assertEquals(SourceFlowScreen.Choose, model.screen.value)
        assertFalse(model.onBack())
    }

    @Test
    fun `a flow that starts on the form leaves instead of going to the choice`() {
        val model = choiceModel(SourceFlowMode.ChangeUnlocked, source(), start = SourceFlowScreen.Server)
        assertEquals(SourceFlowScreen.Server, model.screen.value)
        assertEquals("nas.local", model.form.value.host)
        assertFalse(model.onBack())
        assertEquals(SourceFlowScreen.Server, model.screen.value)
    }

    @Test
    fun `reopen goes back to the choice for a file source, else to the browser or the form`() {
        // A saved first-run file: the flow shows nothing, back from step 3 shows the choice.
        val fileModel = choiceModel(SourceFlowMode.FirstRun, fileSource)
        fileModel.onFileSaved()
        assertNull(fileModel.screen.value)
        fileModel.reopen()
        assertEquals(SourceFlowScreen.Choose, fileModel.screen.value)

        // SMB with the connection still open: the browser.
        val smb = choiceModel()
        smb.onChooseSmb()
        fill(smb, "geheim")
        setup.connectResults += ConnectResult.Connected(server, docsShares)
        smb.onConnect()
        platform.settingsFlow.value = KpmSettings(source = source())
        smb.reopen()
        assertEquals(SourceFlowScreen.Browse, smb.screen.value)

        // SMB without a connection (closed by the stop): the form.
        val gone = choiceModel(source = source())
        gone.onChooseSmb()
        gone.reopen()
        assertEquals(SourceFlowScreen.Server, gone.screen.value)
    }

    @Test
    fun `a saved first-run file closes the connection of an earlier SMB attempt`() {
        val model = connectedFirstRun()
        assertFalse(setup.closed)
        model.onFileSaved()
        assertNull(model.screen.value)
        assertNull(model.browse.value)
        assertTrue(setup.closed)
    }

    // ---- SMB older version (DS-14) ----

    private val older = OlderVersion(
        fileName = "Db.kdbx",
        fileModified = Instant.parse("2025-09-12T18:05:00Z"),
        openModified = Instant.parse("2026-02-28T21:38:00Z"),
    )

    @Test
    fun `an older SMB file asks first and Trotzdem verwenden saves it`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        setup.olderResults += older

        model.onUseFile()

        assertEquals(listOf(FakeSourceSetup.OlderCall("Docs", listOf("KeePass", "Db.kdbx"))), setup.olderCalls)
        assertTrue(setup.chooseCalls.isEmpty())
        assertEquals(
            OlderVersionInfo(
                fileName = "Db.kdbx",
                fileModified = LocalDateTime.of(2025, 9, 12, 18, 5),
                openModified = LocalDateTime.of(2026, 2, 28, 21, 38),
            ),
            model.olderDialog.value,
        )
        assertFalse(assertNotNullState(model).saving)

        model.onOlderUseAnyway()

        assertNull(model.olderDialog.value)
        assertEquals(listOf(FakeSourceSetup.ChooseCall("Docs", listOf("KeePass", "Db.kdbx"))), setup.chooseCalls)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
        assertEquals(1, saved.size)
    }

    @Test
    fun `D10 a lock while the SMB older version check runs saves nothing and shows nothing`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        // The check finds nothing because the app is locked by then (it returns null when locked).
        setup.olderGate = CompletableDeferred()
        model.onUseFile()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        setup.olderGate!!.complete(Unit)

        assertTrue(setup.chooseCalls.isEmpty(), "never reaches the commit")
        assertNull(model.olderDialog.value)
        assertNull(messages.message.value)
        assertTrue(saved.isEmpty())
        assertFalse(assertNotNullState(model).saving)
    }

    @Test
    fun `D10 a discarded SMB switch posts nothing and does not report saved`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        setup.chooseResults += ChooseResult.Discarded

        model.onUseFile()

        assertEquals(1, setup.chooseCalls.size)
        assertNull(messages.message.value)
        assertTrue(saved.isEmpty())
        assertFalse(assertNotNullState(model).saving)
    }

    @Test
    fun `a locked SMB change is not discarded by the lock check`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeLocked)

        model.onUseFile()

        assertEquals(1, setup.chooseCalls.size)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
        assertEquals(1, saved.size)
    }

    @Test
    fun `Abbrechen of the older dialog saves nothing and stays in the browser`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        setup.olderResults += older
        model.onUseFile()

        model.onOlderCancel()

        assertNull(model.olderDialog.value)
        assertTrue(setup.chooseCalls.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertEquals(SourceFlowScreen.Browse, model.screen.value)
        // Using the file again asks again.
        setup.olderResults += older
        model.onUseFile()
        assertEquals(2, setup.olderCalls.size)
        assertTrue(model.olderDialog.value != null)
    }

    @Test
    fun `a newer or other file shows no dialog and the first run and the locked change never ask`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val unlocked = connectedChange(SourceFlowMode.ChangeUnlocked)
        unlocked.onUseFile()
        assertEquals(1, setup.olderCalls.size)
        assertNull(unlocked.olderDialog.value)
        assertEquals(1, setup.chooseCalls.size)

        setup.olderCalls.clear()
        setup.chooseCalls.clear()
        val locked = connectedChange(SourceFlowMode.ChangeLocked)
        locked.onUseFile()
        val firstRun = connectedFirstRun()
        firstRun.onSelectFile(listOf("Docs", "Db.kdbx"))
        firstRun.onUseFile()
        assertTrue(setup.olderCalls.isEmpty())
        assertEquals(2, setup.chooseCalls.size)
    }

    @Test
    fun `the older check is busy like a save and ignores a second tap`() {
        val gate = CompletableDeferred<Unit>()
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        setup.olderGate = gate
        model.onUseFile()
        model.onUseFile()
        assertTrue(assertNotNullState(model).saving)
        assertEquals(1, setup.olderCalls.size)
        gate.complete(Unit)
        assertFalse(assertNotNullState(model).saving)
    }

    // ---- DS-13: the lock notice is the only message ----

    @Test
    fun `a change that locked the app posts no source saved`() {
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        setup.chooseResults += ChooseResult.Saved(
            fileSizeBytes = 10,
            fileChanged = true,
            sync = SyncResult.CandidateReady,
            lockedForNewDatabase = true,
        )

        model.onUseFile()

        assertNull(messages.message.value)
        assertEquals(listOf<Long?>(10), saved)
    }

    // ---- NOT_FOUND: no preselection ----

    @Test
    fun `a file that is gone from the share is not preselected`() {
        platform.syncFlow.value = testSyncInfo(lastProblem = SyncProblem.NOT_FOUND)
        setup.folderResults += listed(files = listOf(kdbx("Db.kdbx")))
        val model = connectedChange(SourceFlowMode.ChangeUnlocked)
        val browse = assertNotNullState(model)
        // Still starts in the folder of the file, but without the file picked.
        assertEquals(listOf("Docs", "KeePass"), browse.path)
        assertNull(browse.selected)
    }

    private fun assertNotNullState(model: SourceFlowModel): BrowseState =
        model.browse.value ?: error("no browse state")
}
