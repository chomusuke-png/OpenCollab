package dev.opencollab.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v2: streamed file transfer with chunk acknowledgements and content-less large file events.
 * v3: the host pings participants and broadcasts their round-trip times; Welcome carries the session age.
 * v4: session chat relayed and stamped by the host, with recent history for newcomers.
 * v5: shared consoles (the host's run and build output) and guest requests to run on the host.
 * v6: every edit is sent at once (several operations in flight per author); resuming lists them all.
 */
const val PROTOCOL_VERSION = 6

/**
 * Optional abilities a side announces ([Message.Hello.features], [Message.Welcome.features]).
 * Messages of a feature are only sent to a peer that announced it, so peers of the same protocol
 * version without it (e.g. 3.0.0) keep working.
 */
object Features {
    /** Test results as IntelliJ test events ([Message.ConsoleTests]). */
    const val TESTS = "tests"
    /** The host's debugger state and breakpoints ([Message.DebugState], [Message.Breakpoints], [Message.BreakpointRequest]). */
    const val DEBUG = "debug"
    /** The host's version control branch and changed files ([Message.VcsStatus]). */
    const val VCS = "vcs"
    /** Terminal commands requested by editors and run on the host after approval ([Message.CommandRequest]). */
    const val COMMANDS = "commands"
    /** The host's code style ([Message.CodeStyle]). */
    const val CODE_STYLE = "codeStyle"

    val ALL = listOf(TESTS, DEBUG, VCS, COMMANDS, CODE_STYLE)
}

/** Stands for the host's project folder in shared console text; each guest puts its own folder back. */
const val PROJECT_DIR_MACRO = "\$PROJECT_DIR\$"

@Serializable
enum class Role {
    HOST, EDITOR, VIEWER;

    val canEdit: Boolean get() = this == HOST || this == EDITOR
}

@Serializable
data class ParticipantInfo(
    val id: String,
    val displayName: String,
    val role: Role,
    val colorIndex: Int,
)

/** The host's sharing rules, sent to guests so they filter local changes the same way. */
@Serializable
data class SharingConfig(
    val excludes: List<String>,
    val includes: List<String>,
    val guestReadOnly: List<String>,
    val maxFileBytes: Long,
)

@Serializable
data class TextRange(val start: Int, val end: Int)

@Serializable
data class FileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val directory: Boolean = false,
)

@Serializable
enum class FileOpKind { CREATE_FILE, CREATE_DIRECTORY, WRITE, DELETE, RENAME }

/**
 * A filesystem change. For RENAME, [newPath] is the destination (moves are renames to
 * another directory). [contentBase64] carries the content of CREATE_FILE and WRITE when it is
 * small enough to travel inline; for larger files it is null and receivers fetch the file with
 * [Message.RequestFiles] (streamed in acknowledged chunks).
 */
@Serializable
data class FileMutation(
    val kind: FileOpKind,
    val path: String,
    val newPath: String? = null,
    val contentBase64: String? = null,
)

/** A chat message as relayed by the host, which assigns [id], the sender fields and [sentAtMillis]. */
@Serializable
data class ChatEntry(
    val id: Long,
    val participantId: String,
    val displayName: String,
    val colorIndex: Int,
    val text: String,
    /** Host wall-clock time (epoch milliseconds). */
    val sentAtMillis: Long,
)

@Serializable
enum class ConsoleStream { STDOUT, STDERR, SYSTEM }

/** Where guests show a console: IntelliJ's Run window (a process) or its Build window (a build). */
@Serializable
enum class ConsoleKind { RUN, BUILD }

/** Something guests may ask the host to run: a run configuration, or building the project. */
@Serializable
data class RunTarget(val id: String, val name: String, val kind: RunTargetKind)

@Serializable
enum class RunTargetKind { CONFIGURATION, BUILD }

/** A stack frame of the host's debugger; [path] is null outside the shared project (e.g. a library). */
@Serializable
data class DebugFrame(val name: String, val path: String? = null, val line: Int = -1)

/** A variable of the selected stack frame, as the host's debugger shows it. */
@Serializable
data class DebugVariable(val name: String, val value: String, val type: String? = null)

/** A line breakpoint in a shared file (0-based line). */
@Serializable
data class BreakpointLine(val path: String, val line: Int)

/** A changed file in the host's working copy; [status] is one of [VcsChange.ADDED] etc. */
@Serializable
data class VcsChange(val path: String, val status: String) {
    companion object {
        const val ADDED = "added"
        const val MODIFIED = "modified"
        const val DELETED = "deleted"
        const val MOVED = "moved"
        const val UNVERSIONED = "unversioned"
        val ALL = setOf(ADDED, MODIFIED, DELETED, MOVED, UNVERSIONED)
    }
}

@Serializable
data class DocHistoryEntry(val opId: String, val author: String, val revision: Int, val op: JsonElement)

/** All messages exchanged between a participant and the host. */
@Serializable
sealed class Message {
    // ---- Handshake & membership -------------------------------------------------------
    @Serializable @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val displayName: String,
        val token: String,
        val resumeKey: String? = null,
        val pluginVersion: String = "",
        /** [Features] this participant supports. */
        val features: List<String> = emptyList(),
    ) : Message()

    @Serializable @SerialName("welcome")
    data class Welcome(
        val sessionId: String,
        val participantId: String,
        val role: Role,
        val resumeKey: String,
        val resumed: Boolean,
        val projectName: String,
        val participants: List<ParticipantInfo>,
        val fileEventSeq: Long,
        val sharing: SharingConfig,
        /** How long the session has been running, so every participant shows the same timer. */
        val sessionAgeMillis: Long = 0,
        /** [Features] the host supports and has enabled. */
        val features: List<String> = emptyList(),
    ) : Message()

    @Serializable @SerialName("rejected")
    data class HandshakeRejected(val reason: String) : Message()

    @Serializable @SerialName("participantJoined")
    data class ParticipantJoined(val participant: ParticipantInfo) : Message()

    @Serializable @SerialName("participantLeft")
    data class ParticipantLeft(val participantId: String) : Message()

    @Serializable @SerialName("roleChanged")
    data class RoleChanged(val participantId: String, val role: Role) : Message()

    @Serializable @SerialName("setRole")
    data class SetRole(val participantId: String, val role: Role) : Message()

    @Serializable @SerialName("error")
    data class Error(val code: String, val message: String, val requestId: String? = null) : Message()

    @Serializable @SerialName("sessionEnded")
    data class SessionEnded(val reason: String) : Message()

    // ---- Project snapshot & files -----------------------------------------------------
    /** Asks for the manifest of all shared files. */
    @Serializable @SerialName("requestManifest")
    data class RequestManifest(val requestId: String) : Message()

    @Serializable @SerialName("manifest")
    data class Manifest(val requestId: String, val files: List<FileEntry>, val fileEventSeq: Long) : Message()

    /** Asks for the content of specific files (after comparing the manifest with local hashes). */
    @Serializable @SerialName("requestFiles")
    data class RequestFiles(val requestId: String, val paths: List<String>) : Message()

    @Serializable @SerialName("fileChunk")
    data class FileChunk(
        val requestId: String,
        val path: String,
        val offset: Long,
        val dataBase64: String,
        val last: Boolean,
        val sha256: String,
    ) : Message()

    @Serializable @SerialName("filesDone")
    data class FilesDone(val requestId: String, val missing: List<String>) : Message()

    /** Flow control: total bytes of request [requestId] written by the receiver so far. */
    @Serializable @SerialName("chunkAck")
    data class FileChunkAck(val requestId: String, val receivedBytes: Long) : Message()

    /** Guest → host: request to change the shared project tree. */
    @Serializable @SerialName("fileRequest")
    data class FileRequest(val requestId: String, val mutation: FileMutation) : Message()

    /** Host → participants: an applied change to the shared tree. */
    @Serializable @SerialName("fileEvent")
    data class FileEvent(val seq: Long, val origin: String, val mutation: FileMutation) : Message()

    /** Participant → host after reconnecting: replay file events after [lastSeq]. */
    @Serializable @SerialName("resumeFiles")
    data class ResumeFiles(val requestId: String, val lastSeq: Long) : Message()

    /** Host → participant: replay is impossible, fetch a fresh manifest instead. */
    @Serializable @SerialName("filesResyncRequired")
    data class FilesResyncRequired(val requestId: String) : Message()

    @Serializable @SerialName("result")
    data class RequestResult(val requestId: String, val ok: Boolean, val error: String? = null) : Message()

    // ---- Documents (OT) ---------------------------------------------------------------
    @Serializable @SerialName("openDoc")
    data class OpenDocument(val path: String) : Message()

    @Serializable @SerialName("closeDoc")
    data class CloseDocument(val path: String) : Message()

    /**
     * Full document state. [appliedCount] says how many of the requester's operations in flight
     * ([ResumeDocument.inFlightOpIds], oldest first) the host had already applied.
     */
    @Serializable @SerialName("docState")
    data class DocumentState(
        val path: String,
        val revision: Int,
        val text: String,
        val appliedCount: Int = 0,
    ) : Message()

    @Serializable @SerialName("resumeDoc")
    data class ResumeDocument(
        val path: String,
        val revision: Int,
        /** The requester's operations not acknowledged yet, oldest first. */
        val inFlightOpIds: List<String> = emptyList(),
        /** Skip history replay and send the full state (used after protocol errors). */
        val forceSnapshot: Boolean = false,
    ) : Message()

    @Serializable @SerialName("docCatchUp")
    data class DocumentCatchUp(val path: String, val fromRevision: Int, val entries: List<DocHistoryEntry>) : Message()

    @Serializable @SerialName("op")
    data class DocOp(val path: String, val opId: String, val baseRevision: Int, val op: JsonElement) : Message()

    @Serializable @SerialName("remoteOp")
    data class RemoteOp(val path: String, val opId: String, val author: String, val revision: Int, val op: JsonElement) : Message()

    @Serializable @SerialName("ack")
    data class OpAck(val path: String, val opId: String, val revision: Int) : Message()

    @Serializable @SerialName("opRejected")
    data class OpRejected(val path: String, val opId: String, val reason: String) : Message()

    @Serializable @SerialName("docUnavailable")
    data class DocumentUnavailable(val path: String, val reason: String) : Message()

    @Serializable @SerialName("docSaved")
    data class DocumentSaved(val path: String, val revision: Int) : Message()

    // ---- Presence ---------------------------------------------------------------------
    /** Sent by a participant; the host stamps the sender and relays it as [PresenceBroadcast]. */
    @Serializable @SerialName("presence")
    data class Presence(
        val path: String?,
        val caret: Int,
        val selections: List<TextRange>,
        /** Server revision of the document these offsets refer to (plus the sender's own edits sent); -1 if unknown. */
        val revision: Int = -1,
    ) : Message()

    @Serializable @SerialName("presenceBroadcast")
    data class PresenceBroadcast(
        val participantId: String,
        val path: String?,
        val caret: Int,
        val selections: List<TextRange>,
        /** Host revision the offsets were mapped to; -1 if they could not be mapped (sender's own coordinates). */
        val revision: Int = -1,
    ) : Message()

    /** Either side may ping; the other answers with a [Pong] carrying the same nonce. */
    @Serializable @SerialName("ping")
    data class Ping(val nonce: Long) : Message()

    @Serializable @SerialName("pong")
    data class Pong(val nonce: Long) : Message()

    /** Host → participants: each participant's round-trip time to the host, in milliseconds. */
    @Serializable @SerialName("latencies")
    data class Latencies(val millis: Map<String, Int>) : Message()

    // ---- Chat -------------------------------------------------------------------------
    /** Participant → host: a chat message; the host stamps the sender and relays it as [Chat]. */
    @Serializable @SerialName("chatSend")
    data class ChatSend(val text: String) : Message()

    /** Host → everyone, sender included: a chat message in session order. */
    @Serializable @SerialName("chat")
    data class Chat(val entry: ChatEntry) : Message()

    /** Participant → host: started or stopped typing a chat message (refreshed every few seconds while typing). */
    @Serializable @SerialName("chatTyping")
    data class ChatTyping(val typing: Boolean) : Message()

    /** Host → everyone else: [participantId] is (or is no longer) typing. Receivers let it expire on their own. */
    @Serializable @SerialName("chatTypingBroadcast")
    data class ChatTypingBroadcast(val participantId: String, val typing: Boolean) : Message()

    /** Host → a participant after (re)joining: the most recent messages, oldest first. Replaces any local copy. */
    @Serializable @SerialName("chatHistory")
    data class ChatHistory(val entries: List<ChatEntry>) : Message()

    // ---- Shared consoles and running on the host ----------------------------------------
    /**
     * Host → guests: a process or build started on the host. [requestedBy] is the participant
     * whose request started it, if any. Paths inside the project appear as [PROJECT_DIR_MACRO].
     */
    @Serializable @SerialName("consoleStarted")
    data class ConsoleStarted(
        val consoleId: String,
        val title: String,
        val requestedBy: String? = null,
        /** 2.0.0 hosts omit it (a run). */
        val kind: ConsoleKind = ConsoleKind.RUN,
    ) : Message()

    @Serializable @SerialName("consoleOutput")
    data class ConsoleOutput(val consoleId: String, val stream: ConsoleStream, val text: String) : Message()

    /** [exitCode] is null when unknown (e.g. a build that only reports success or failure). */
    @Serializable @SerialName("consoleEnded")
    data class ConsoleEnded(val consoleId: String, val exitCode: Int?) : Message()

    /** Host → guests: what may be requested with [RunRequest]; replaces the previous list. */
    @Serializable @SerialName("runTargets")
    data class RunTargets(val targets: List<RunTarget>) : Message()

    /** Editor → host: please run [targetId]. The host's user approves or refuses. */
    @Serializable @SerialName("runRequest")
    data class RunRequest(val requestId: String, val targetId: String) : Message()

    /** Host → the requester only (also answers [CommandRequest]). */
    @Serializable @SerialName("runResponse")
    data class RunResponse(val requestId: String, val accepted: Boolean, val reason: String? = null) : Message()

    // ---- Host IDE state (optional [Features]) -------------------------------------------------
    /**
     * Host → guests ([Features.TESTS]): test progress of console [consoleId], as IntelliJ test
     * service messages (one `##teamcity[...]` per line). Locations are `opencollab://path:line`.
     */
    @Serializable @SerialName("consoleTests")
    data class ConsoleTests(val consoleId: String, val text: String) : Message()

    /** Host → guests ([Features.DEBUG]): the host's debugger; [active] false when nothing is being debugged. */
    @Serializable @SerialName("debugState")
    data class DebugState(
        val active: Boolean,
        val paused: Boolean = false,
        val sessionName: String = "",
        /** The selected frame's file and 0-based line, when it is in the shared project. */
        val path: String? = null,
        val line: Int = -1,
        val frames: List<DebugFrame> = emptyList(),
        val variables: List<DebugVariable> = emptyList(),
    ) : Message()

    /** Host → guests ([Features.DEBUG]): all line breakpoints in shared files; replaces the previous list. */
    @Serializable @SerialName("breakpoints")
    data class Breakpoints(val breakpoints: List<BreakpointLine>) : Message()

    /** Editor → host ([Features.DEBUG]): add or remove a line breakpoint. */
    @Serializable @SerialName("breakpointRequest")
    data class BreakpointRequest(val path: String, val line: Int, val add: Boolean) : Message()

    /** Host → guests ([Features.VCS]): current branch (null if unknown) and changed files. */
    @Serializable @SerialName("vcsStatus")
    data class VcsStatus(val branch: String?, val changes: List<VcsChange>) : Message()

    /** Editor → host ([Features.COMMANDS]): run [command] in a shell in the project folder; the host's user decides. */
    @Serializable @SerialName("commandRequest")
    data class CommandRequest(val requestId: String, val command: String) : Message()

    /** Host → guests ([Features.CODE_STYLE]): the host's code style settings (IntelliJ XML). */
    @Serializable @SerialName("codeStyle")
    data class CodeStyle(val xml: String) : Message()
}
