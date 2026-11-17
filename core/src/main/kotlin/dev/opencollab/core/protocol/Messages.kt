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
    data class Presence(val path: String?, val caret: Int, val selections: List<TextRange>) : Message()

    @Serializable @SerialName("presenceBroadcast")
    data class PresenceBroadcast(
        val participantId: String,
        val path: String?,
        val caret: Int,
        val selections: List<TextRange>,
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

    /** Host → the requester only. */
    @Serializable @SerialName("runResponse")
    data class RunResponse(val requestId: String, val accepted: Boolean, val reason: String? = null) : Message()
}
