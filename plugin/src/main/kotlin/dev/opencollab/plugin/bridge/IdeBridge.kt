package dev.opencollab.plugin.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.TextRange
import dev.opencollab.core.session.ClientHandler
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.plugin.OpenCollabBundle
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Events the bridge reports to the session service / UI. */
interface BridgeListener {
    fun onStateChanged(state: ClientState, detail: String) {}
    fun onParticipantsChanged(participants: List<ParticipantInfo>) {}
    fun onRoleChanged(role: Role) {}
    fun onLog(message: String) {}
    /** The session has been running for [sessionAgeMillis] (sent by the host on every (re)connect). */
    fun onSessionAge(sessionAgeMillis: Long) {}
    fun onLatencies(millis: Map<String, Int>) {}
    fun onChat(entry: ChatEntry) {}
    fun onChatHistory(entries: List<ChatEntry>) {}
    /** Started following [participantId], or stopped (null). EDT. */
    fun onFollowingChanged(participantId: String?) {}
    fun onConsoleStarted(started: Message.ConsoleStarted) {}
    fun onConsoleOutput(output: Message.ConsoleOutput) {}
    fun onConsoleEnded(ended: Message.ConsoleEnded) {}
    fun onRunTargets(targets: List<RunTarget>) {}
    fun onRunResponse(response: Message.RunResponse) {}
}

/**
 * Connects one project's editors and virtual file system to a [CollabClient].
 *
 * The same bridge runs on the host (connected in-process to the authoritative session)
 * and on guests. All document work happens on the EDT inside write actions; network I/O
 * is never performed on the EDT (the client only enqueues messages).
 */
class IdeBridge(
    val project: Project,
    val root: Path,
    val isHost: Boolean,
    private val listener: BridgeListener,
) : ClientHandler, RemoteChangeSuppression, Disposable {
    lateinit var client: CollabClient

    val writer = VfsTreeWriter(root, this)
    private val log = logger<IdeBridge>()
    private val applyingRemote = ThreadLocal.withInitial { 0 }
    private val suppressedPaths = ConcurrentHashMap<String, Long>()
    private val participants = ConcurrentHashMap<String, ParticipantInfo>()
    private val renderer = PresenceRenderer(project)
    @Volatile private var installed = false
    @Volatile private var disposed = false

    /** Executor running tasks on the EDT in submission order (the client's document thread). */
    val edtExecutor = Executor { task ->
        ApplicationManager.getApplication().invokeLater({ if (!disposed && !project.isDisposed) task.run() }, ModalityState.nonModal())
    }

    // ---- Installation -----------------------------------------------------------------

    /** Registers listeners. EDT. */
    fun install() {
        if (installed) return
        installed = true
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(documentListener, this)
        multicaster.addCaretListener(caretListener, this)
        multicaster.addSelectionListener(selectionListener, this)
        multicaster.addEditorMouseListener(followMouseListener, this)
        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, this)
        val projectBus = project.messageBus.connect(this)
        projectBus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, fileEditorListener)
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(VirtualFileManager.VFS_CHANGES, vfsListener)
        appBus.subscribe(FileDocumentManagerListener.TOPIC, saveListener)
    }

    override fun dispose() {
        disposed = true
        ApplicationManager.getApplication().invokeLater({
            renderer.clearAll()
            setAllReadOnly(false)
        }, ModalityState.any())
    }

    // ---- Paths ------------------------------------------------------------------------

    fun pathOf(file: VirtualFile?): String? {
        if (file == null || !file.isInLocalFileSystem) return null
        return try { SafePath.relativize(root, file.toNioPath()) } catch (e: UnsupportedOperationException) { null }
    }

    private fun pathOf(document: Document): String? = pathOf(FileDocumentManager.getInstance().getFile(document))

    private fun fileOf(path: String): VirtualFile? {
        val target = try { SafePath.resolveInside(root, path) } catch (e: SecurityException) { return null }
        return LocalFileSystem.getInstance().findFileByNioFile(target)
    }

    private fun documentOf(path: String): Document? = fileOf(path)?.let { FileDocumentManager.getInstance().getDocument(it) }

    private fun isTextShareable(file: VirtualFile, path: String): Boolean =
        !file.isDirectory && !file.fileType.isBinary && client.sharingRules.isShared(path) &&
            client.sharingRules.isSizeAllowed(file.length) && file.length <= MessageCodec.MAX_DOCUMENT_CHARS

    // ---- Suppression ------------------------------------------------------------------

    override fun <T> whileApplyingRemote(block: () -> T): T {
        applyingRemote.set(applyingRemote.get() + 1)
        try {
            return block()
        } finally {
            applyingRemote.set(applyingRemote.get() - 1)
        }
    }

    override fun suppressPath(path: String) {
        suppressedPaths[path] = System.currentTimeMillis() + 60_000
    }

    private fun isSuppressed(path: String): Boolean {
        if (applyingRemote.get() > 0) return true
        val now = System.currentTimeMillis()
        suppressedPaths.entries.removeIf { it.value < now }
        return suppressedPaths.remove(path) != null
    }

    // ---- ClientHandler: state ---------------------------------------------------------

    override fun onStateChanged(state: ClientState, detail: String) {
        if (state == ClientState.SYNCING) writer.bulk = true
        if (state == ClientState.CONNECTED || state == ClientState.DISCONNECTED) writer.bulk = false
        if (state == ClientState.CONNECTED && !isHost) edtExecutor.execute { openEditorsDocuments() }
        listener.onStateChanged(state, detail)
    }

    override fun onWelcome(welcome: Message.Welcome) {
        listener.onSessionAge(welcome.sessionAgeMillis)
        if (!isHost) edtExecutor.execute { setAllReadOnly(welcome.role == Role.VIEWER) }
    }

    override fun onLatencies(millis: Map<String, Int>) = listener.onLatencies(millis)
    override fun onChat(entry: ChatEntry) = listener.onChat(entry)
    override fun onChatHistory(entries: List<ChatEntry>) = listener.onChatHistory(entries)
    override fun onConsoleStarted(started: Message.ConsoleStarted) = listener.onConsoleStarted(started)
    override fun onConsoleOutput(output: Message.ConsoleOutput) = listener.onConsoleOutput(output)
    override fun onConsoleEnded(ended: Message.ConsoleEnded) = listener.onConsoleEnded(ended)
    override fun onRunTargets(targets: List<RunTarget>) = listener.onRunTargets(targets)
    override fun onRunResponse(response: Message.RunResponse) = listener.onRunResponse(response)

    override fun onParticipantsChanged(participants: List<ParticipantInfo>) {
        this.participants.clear()
        participants.forEach { this.participants[it.id] = it }
        listener.onParticipantsChanged(participants)
    }

    override fun onRoleChanged(role: Role) {
        if (!isHost) edtExecutor.execute { setAllReadOnly(role == Role.VIEWER) }
        listener.onRoleChanged(role)
    }

    override fun onLog(message: String) = listener.onLog(message)

    // ---- ClientHandler: documents (EDT) -----------------------------------------------

    override fun applyRemoteEdit(path: String, op: TextOperation) {
        val document = documentOf(path)
        if (document == null) {
            listener.onLog(OpenCollabBundle.message("bridge.remote.edit.missing", path))
            return
        }
        if (document.textLength != op.baseLength) {
            // Local document diverged from what the OT client believes (should not happen):
            // re-register it and let the server state win via the rebase path.
            log.warn("OpenCollab: document $path length ${document.textLength} != ${op.baseLength}; resynchronising")
            client.closeDocument(path)
            client.openDocument(path, document.text)
            return
        }
        whileApplyingRemote {
            CommandProcessor.getInstance().runUndoTransparentAction {
                ApplicationManager.getApplication().runWriteAction {
                    val wasReadOnly = !document.isWritable
                    if (wasReadOnly) document.setReadOnly(false)
                    try {
                        applyOperation(document, op)
                    } finally {
                        if (wasReadOnly) document.setReadOnly(true)
                    }
                }
            }
        }
    }

    override fun onDocumentSaved(path: String) {
        if (isHost) return
        val document = documentOf(path) ?: return
        whileApplyingRemote { FileDocumentManager.getInstance().saveDocument(document) }
    }

    override fun onDocumentUnavailable(path: String, reason: String) {
        listener.onLog(OpenCollabBundle.message("bridge.doc.unavailable", path, reason))
    }

    override fun onPresence(presence: Message.PresenceBroadcast) {
        val who = participants[presence.participantId]
        renderer.update(presence, who?.displayName ?: "?", who?.colorIndex ?: 0, ::documentOf)
        if (presence.participantId == followingId) jumpTo(presence.participantId)
    }

    override fun onParticipantLeft(participantId: String) {
        renderer.remove(participantId)
        if (participantId == followingId) stopFollowing()
    }

    fun presenceOf(participantId: String) = renderer.presenceOf(participantId)

    // ---- Following (EDT) ---------------------------------------------------------------

    /** The participant whose file and caret this IDE keeps showing, if any. */
    var followingId: String? = null
        private set
    /** Caret moves until then come from our own navigation, not from the user. */
    private var ignoreCaretUntil = 0L

    /**
     * Keeps showing [participantId]'s file and caret until the user clicks or types in an
     * editor, or stops. Returns false if they have no shared file open right now (following
     * still starts and catches up once they open one).
     */
    fun startFollowing(participantId: String): Boolean {
        if (followingId != participantId) {
            followingId = participantId
            listener.onFollowingChanged(participantId)
        }
        return jumpTo(participantId)
    }

    fun stopFollowing() {
        if (followingId == null) return
        followingId = null
        listener.onFollowingChanged(null)
    }

    /** Opens the file a participant is looking at, at their caret, without taking focus. */
    private fun jumpTo(participantId: String): Boolean {
        val p = renderer.presenceOf(participantId) ?: return false
        val path = p.path ?: return false
        val file = fileOf(path) ?: return false
        ignoreCaretUntil = System.currentTimeMillis() + 300
        OpenFileDescriptor(project, file, p.caret).navigate(false)
        return true
    }

    /** A click in one of this project's editors means the user took over. */
    private val followMouseListener = object : EditorMouseListener {
        override fun mousePressed(event: EditorMouseEvent) {
            if (event.editor.project == project) stopFollowing()
        }
    }

    // ---- ClientHandler: files (client I/O thread) -------------------------------------

    override fun onFilesChanged(paths: Collection<String>, initial: Boolean) {
        if (initial) {
            val rootDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return
            VfsUtil.markDirtyAndRefresh(true, true, true, rootDir)
            listener.onLog(OpenCollabBundle.message("bridge.snapshot", paths.size))
        }
    }

    private val lastRestore = ConcurrentHashMap<String, Long>()

    override fun onFileRequestFailed(mutation: FileMutation, error: String) {
        listener.onLog(OpenCollabBundle.message("bridge.refused", OpenCollabBundle.message("file.op.${mutation.kind.name}"), mutation.path, error))
        // Bring host-owned files back to the host's version (at most once a minute per path,
        // so a tool that keeps rewriting a file cannot cause a restore loop).
        val now = System.currentTimeMillis()
        val paths = listOfNotNull(mutation.path, mutation.newPath).filter { path ->
            client.mirror?.isTracked(path) == true && (lastRestore.put(path, now)?.let { now - it > 60_000 } ?: true)
        }
        client.restoreFromHost(paths)
    }

    // ---- Editor listeners (EDT) ---------------------------------------------------------

    private fun openEditorsDocuments() {
        for (file in FileEditorManager.getInstance(project).openFiles) openDocumentFor(file)
    }

    private fun openDocumentFor(file: VirtualFile) {
        if (isHost || client.state != ClientState.CONNECTED) return
        val path = pathOf(file) ?: return
        if (!isTextShareable(file, path) || client.isDocumentOpen(path)) return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        client.openDocument(path, document.text)
        updateReadOnly(document)
    }

    private fun updateReadOnly(document: Document) {
        if (isHost) return
        val path = pathOf(document) ?: return
        val readOnly = client.role == Role.VIEWER || client.sharingRules.isGuestReadOnly(path)
        if (readOnly == !document.isWritable) return
        document.setReadOnly(readOnly)
    }

    private fun setAllReadOnly(readOnly: Boolean) {
        if (isHost) return
        for (file in FileEditorManager.getInstance(project).openFiles) {
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: continue
            if (pathOf(file) == null) continue
            if (!readOnly) document.setReadOnly(false) else updateReadOnly(document)
        }
    }

    private val documentListener = object : DocumentListener {
        override fun beforeDocumentChange(event: DocumentEvent) {
            if (applyingRemote.get() > 0 || isHost) return
            if (client.state != ClientState.CONNECTED || !client.role.canEdit) return
            val path = pathOf(event.document) ?: return
            if (client.isDocumentOpen(path)) return
            val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
            if (!isTextShareable(file, path)) return
            // Edited without an editor (refactoring, code generation): register with the pre-change text.
            client.openDocument(path, event.document.text)
        }

        override fun documentChanged(event: DocumentEvent) {
            if (applyingRemote.get() > 0) return
            val path = pathOf(event.document) ?: return
            if (!client.isDocumentOpen(path)) return
            val newLength = event.document.textLength
            val oldLength = newLength - event.newLength + event.oldLength
            val op = TextOperation.replace(oldLength, event.offset, event.oldLength, event.newFragment.toString())
            client.localEdit(path, op)
        }
    }

    private fun sendPresence(editor: Editor) {
        if (editor.project != null && editor.project != project) return
        val path = pathOf(editor.document) ?: return
        if (!client.sharingRules.isShared(path)) return
        val caretModel = editor.caretModel
        val selections = caretModel.allCarets.filter { it.hasSelection() }.map { TextRange(it.selectionStart, it.selectionEnd) }
        client.updatePresence(path, caretModel.offset, selections)
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            // Moving the caret yourself (typing, arrow keys) in a focused editor ends following.
            if (followingId != null && applyingRemote.get() == 0 && System.currentTimeMillis() > ignoreCaretUntil &&
                event.editor.contentComponent.hasFocus()
            ) stopFollowing()
            sendPresence(event.editor)
        }
    }

    private val selectionListener = object : SelectionListener {
        override fun selectionChanged(e: SelectionEvent) = sendPresence(e.editor)
    }

    private val editorFactoryListener = object : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val editor = event.editor
            if (editor.project != null && editor.project != project) return
            val path = pathOf(editor.document) ?: return
            renderer.editorOpened(editor, path) { id -> participants[id]?.let { it.displayName to it.colorIndex } ?: ("?" to 0) }
            if (!isHost) updateReadOnly(editor.document)
        }
    }

    private val fileEditorListener = object : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = openDocumentFor(file)

        override fun selectionChanged(event: FileEditorManagerEvent) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) sendPresence(editor) else client.updatePresence(null, 0, emptyList())
        }
    }

    private val saveListener = object : FileDocumentManagerListener {
        override fun beforeDocumentSaving(document: Document) {
            if (!isHost || applyingRemote.get() > 0) return
            val path = pathOf(document) ?: return
            if (client.isDocumentOpen(path)) client.documentSaved(path)
        }
    }

    // ---- VFS listener ---------------------------------------------------------------

    private val vfsListener = object : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            if (disposed || client.state != ClientState.CONNECTED) return
            for (event in events) {
                try {
                    handleVfsEvent(event)
                } catch (e: Exception) {
                    log.warn("OpenCollab: failed to handle $event", e)
                }
            }
        }
    }

    private fun relative(absolute: String): String? =
        try { SafePath.relativize(root, Path.of(absolute)) } catch (e: Exception) { null }

    private fun handleVfsEvent(event: VFileEvent) {
        if (!event.isFromRefresh && applyingRemote.get() > 0) return
        val mutation: FileMutation = when (event) {
            is VFileCreateEvent -> {
                val path = relative(event.path) ?: return
                if (isSuppressed(path)) return
                if (event.isDirectory) {
                    scanCreatedDirectory(path)
                    FileMutation(FileOpKind.CREATE_DIRECTORY, path)
                } else {
                    val content = readContent(event.file ?: return) ?: return
                    FileMutation(FileOpKind.CREATE_FILE, path, contentBase64 = content.base64)
                }
            }
            is VFileCopyEvent -> {
                val created = event.newParent.findChild(event.newChildName) ?: return
                val path = pathOf(created) ?: return
                if (isSuppressed(path)) return
                val content = readContent(created) ?: return
                FileMutation(FileOpKind.CREATE_FILE, path, contentBase64 = content.base64)
            }
            is VFileContentChangeEvent -> {
                val path = relative(event.path) ?: return
                if (isSuppressed(path)) return
                // Shared documents travel through OT; guests never upload their saves of them.
                if (!isHost && client.isDocumentOpen(path)) return
                val content = readContent(event.file) ?: return
                FileMutation(FileOpKind.WRITE, path, contentBase64 = content.base64)
            }
            is VFileDeleteEvent -> {
                val path = relative(event.path) ?: return
                if (isSuppressed(path)) return
                FileMutation(FileOpKind.DELETE, path)
            }
            is VFileMoveEvent -> {
                val from = relative(event.oldPath) ?: return
                val to = relative(event.newPath) ?: return
                if (isSuppressed(from) || isSuppressed(to)) return
                FileMutation(FileOpKind.RENAME, from, newPath = to)
            }
            is VFilePropertyChangeEvent -> {
                if (event.propertyName != VirtualFile.PROP_NAME) return
                val from = relative(event.oldPath) ?: return
                val to = relative(event.newPath) ?: return
                if (isSuppressed(from) || isSuppressed(to)) return
                FileMutation(FileOpKind.RENAME, from, newPath = to)
            }
            else -> return
        }
        val rules = client.sharingRules
        val involved = listOfNotNull(mutation.path, mutation.newPath)
        if (involved.none { rules.isShared(it) }) return
        if (!isHost) {
            if (!client.role.canEdit || involved.any { rules.isGuestReadOnly(it) }) {
                // Typically the guest IDE writing its own project files (.idea, *.iml): keep it local.
                log.debug("OpenCollab: local change to ${mutation.path} is not shared (read-only for this participant)")
                return
            }
            if (mutation.kind == FileOpKind.RENAME && involved.any { !rules.isShared(it) }) {
                log.debug("OpenCollab: rename of ${mutation.path} involves an unshared path; not sent")
                return
            }
        }
        client.requestFileChange(mutation)
    }

    /** Content of a changed file: inline when small, [base64] = null when guests must stream it. */
    private class Content(val base64: String?)

    /** Returns null when the change must not be sent at all. */
    private fun readContent(file: VirtualFile): Content? {
        if (file.isDirectory || !file.isValid) return null
        if (!client.sharingRules.isSizeAllowed(file.length)) return null
        if (file.length > MessageCodec.MAX_INLINE_FILE_BYTES) {
            if (isHost) return Content(null) // announced without content; guests download it in chunks
            listener.onLog(OpenCollabBundle.message("bridge.too.large.upload", pathOf(file) ?: file.name, MessageCodec.MAX_INLINE_FILE_BYTES / (1024 * 1024)))
            return null
        }
        return Content(MessageCodec.encodeBase64(file.contentsToByteArray()))
    }

    /** A directory that appears with content (unzip, paste) only produces one VFS event. */
    private fun scanCreatedDirectory(dirPath: String) {
        val dir = try { SafePath.resolveInside(root, dirPath) } catch (e: SecurityException) { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val rules = client.sharingRules
            try {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                        val rel = SafePath.relativize(root, file) ?: return@forEach
                        val size = Files.size(file)
                        if (!rules.isShared(rel) || !rules.isSizeAllowed(size)) return@forEach
                        val inline = size <= MessageCodec.MAX_INLINE_FILE_BYTES
                        if (!inline && !isHost) return@forEach // guests cannot upload large files
                        client.requestFileChange(
                            FileMutation(
                                FileOpKind.CREATE_FILE, rel,
                                contentBase64 = if (inline) MessageCodec.encodeBase64(Files.readAllBytes(file)) else null,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                log.warn("OpenCollab: could not scan $dirPath", e)
            }
        }
    }

    companion object {
        /** Applies [op] to [document]; caller holds the write lock. */
        fun applyOperation(document: Document, op: TextOperation) {
            var position = 0
            for (component in op.toWire()) {
                when {
                    component is String -> {
                        document.insertString(position, component)
                        position += component.length
                    }
                    (component as Int) > 0 -> position += component
                    else -> document.deleteString(position, position - component)
                }
            }
        }

    }

    init {
        Disposer.register(project, this)
    }
}
