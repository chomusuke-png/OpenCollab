package dev.opencollab.core.protocol

import dev.opencollab.core.files.SafePath
import dev.opencollab.core.ot.TextOperation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

class ProtocolException(message: String) : RuntimeException(message)

/**
 * Encodes/decodes protocol messages as JSON and validates every inbound message before it
 * reaches session logic. Nothing received from the network is trusted.
 */
object MessageCodec {
    const val MAX_FRAME_CHARS = 16 * 1024 * 1024
    const val MAX_CHUNK_BYTES = 512 * 1024
    /** Bytes sent per file chunk. */
    const val CHUNK_BYTES = 256 * 1024
    /** Files up to this size may travel inline in a file event/request; larger ones are streamed. */
    const val MAX_INLINE_FILE_BYTES = 8 * 1024 * 1024
    /** Unacknowledged bytes the host may have in flight per transfer (flow control). */
    const val TRANSFER_WINDOW_BYTES = 8L * 1024 * 1024
    /** Largest text document edited live (OT); bigger files are synchronised as files only. */
    const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024

    fun base64Length(bytes: Int): Int = (bytes + 2) / 3 * 4
    const val MAX_NAME = 64
    const val MAX_ID = 128
    const val MAX_OP_COMPONENTS = 100_000
    const val MAX_SELECTIONS = 64
    const val MAX_PATHS_PER_REQUEST = 20_000
    const val MAX_LATENCY_ENTRIES = 256
    /** Operations a client may list as in flight when resuming a document. */
    const val MAX_IN_FLIGHT = 10_000
    const val MAX_CHAT_CHARS = 2_000
    const val MAX_CHAT_HISTORY = 500
    const val MAX_TITLE = 200
    const val MAX_RUN_TARGETS = 500
    /** Console output travels in chunks of at most this many characters. */
    const val MAX_CONSOLE_CHUNK_CHARS = 64 * 1024
    const val MAX_FEATURES = 32
    const val MAX_DEBUG_FRAMES = 200
    const val MAX_DEBUG_VARIABLES = 200
    const val MAX_DEBUG_VALUE = 1_000
    const val MAX_BREAKPOINTS = 5_000
    const val MAX_VCS_CHANGES = 5_000
    const val MAX_COMMAND_CHARS = 1_000
    const val MAX_CODE_STYLE_CHARS = 512 * 1024

    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    fun decode(text: String): Message {
        if (text.length > MAX_FRAME_CHARS) throw ProtocolException("Frame too large (${text.length} chars)")
        val message = try {
            json.decodeFromString(Message.serializer(), text)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed message: ${e.message?.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed message: ${e.message?.take(200)}")
        }
        validate(message)
        return message
    }

    fun opToJson(op: TextOperation): JsonElement = JsonArray(op.toWire().map {
        when (it) {
            is String -> JsonPrimitive(it)
            is Int -> JsonPrimitive(it)
            else -> error("unreachable")
        }
    })

    fun opFromJson(element: JsonElement): TextOperation {
        val array = element as? JsonArray ?: throw ProtocolException("Operation must be an array")
        if (array.size > MAX_OP_COMPONENTS) throw ProtocolException("Operation has too many components")
        val components: List<Any> = array.map { c ->
            val p = c as? JsonPrimitive ?: throw ProtocolException("Invalid operation component")
            if (p.isString) p.content else p.intOrNull ?: throw ProtocolException("Invalid operation component")
        }
        return try {
            TextOperation.fromWire(components)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Invalid operation: ${e.message}")
        }
    }

    fun decodeBase64(data: String): ByteArray = try {
        Base64.getDecoder().decode(data)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException("Invalid base64 payload")
    }

    fun encodeBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun validate(m: Message) {
        when (m) {
            is Message.Hello -> {
                check(m.displayName.isNotBlank() && m.displayName.length <= MAX_NAME, "Invalid display name")
                check(m.displayName.none { it.isISOControl() }, "Invalid display name")
                check(m.token.length in 1..256, "Invalid token")
                m.resumeKey?.let { check(it.length <= MAX_ID, "Invalid resume key") }
                features(m.features)
            }
            is Message.SetRole -> id(m.participantId)
            is Message.RequestManifest -> id(m.requestId)
            is Message.RequestFiles -> {
                id(m.requestId)
                check(m.paths.size <= MAX_PATHS_PER_REQUEST, "Too many paths")
                m.paths.forEach(::path)
            }
            is Message.FileChunk -> {
                id(m.requestId); path(m.path)
                check(m.offset >= 0 && m.dataBase64.length <= MAX_CHUNK_BYTES * 2, "Invalid chunk")
            }
            is Message.FileChunkAck -> { id(m.requestId); check(m.receivedBytes >= 0, "Invalid acknowledgement") }
            is Message.Manifest -> { id(m.requestId); m.files.forEach { path(it.path) } }
            is Message.FileRequest -> { id(m.requestId); mutation(m.mutation) }
            is Message.FileEvent -> mutation(m.mutation)
            is Message.ResumeFiles -> { id(m.requestId); check(m.lastSeq >= 0, "Invalid sequence") }
            is Message.OpenDocument -> path(m.path)
            is Message.CloseDocument -> path(m.path)
            is Message.DocumentState -> { path(m.path); check(m.revision >= 0 && m.appliedCount >= 0, "Invalid revision") }
            is Message.ResumeDocument -> {
                path(m.path); check(m.revision >= 0, "Invalid revision")
                check(m.inFlightOpIds.size <= MAX_IN_FLIGHT, "Too many operations in flight")
                m.inFlightOpIds.forEach(::id)
            }
            is Message.DocumentCatchUp -> { path(m.path); m.entries.forEach { id(it.opId); opFromJson(it.op) } }
            is Message.DocOp -> {
                path(m.path); id(m.opId)
                check(m.baseRevision >= 0, "Invalid revision")
                opFromJson(m.op)
            }
            is Message.RemoteOp -> { path(m.path); id(m.opId); opFromJson(m.op) }
            is Message.OpAck -> { path(m.path); id(m.opId) }
            is Message.OpRejected -> { path(m.path); id(m.opId) }
            is Message.DocumentSaved -> path(m.path)
            is Message.DocumentUnavailable -> path(m.path)
            is Message.Presence -> {
                m.path?.let(::path)
                check(m.caret >= 0 && m.revision >= -1, "Invalid caret")
                check(m.selections.size <= MAX_SELECTIONS, "Too many selections")
                check(m.selections.all { it.start in 0..it.end }, "Invalid selection")
            }
            is Message.PresenceBroadcast -> {
                m.path?.let(::path)
                check(m.selections.size <= MAX_SELECTIONS, "Too many selections")
            }
            is Message.Latencies -> {
                check(m.millis.size <= MAX_LATENCY_ENTRIES, "Too many latency entries")
                m.millis.forEach { (participant, millis) -> id(participant); check(millis >= 0, "Invalid latency") }
            }
            is Message.Welcome -> { check(m.sessionAgeMillis >= 0, "Invalid session age"); features(m.features) }
            is Message.ChatSend -> chatText(m.text)
            is Message.ChatTypingBroadcast -> id(m.participantId)
            is Message.Chat -> chatEntry(m.entry)
            is Message.ChatHistory -> {
                check(m.entries.size <= MAX_CHAT_HISTORY, "Chat history too long")
                m.entries.forEach(::chatEntry)
            }
            is Message.ConsoleStarted -> { id(m.consoleId); title(m.title); m.requestedBy?.let(::id) }
            is Message.ConsoleOutput -> { id(m.consoleId); check(m.text.length <= MAX_CONSOLE_CHUNK_CHARS, "Console chunk too large") }
            is Message.ConsoleEnded -> id(m.consoleId)
            is Message.RunTargets -> {
                check(m.targets.size <= MAX_RUN_TARGETS, "Too many run targets")
                m.targets.forEach { id(it.id); title(it.name) }
            }
            is Message.RunRequest -> { id(m.requestId); id(m.targetId) }
            is Message.RunResponse -> { id(m.requestId); m.reason?.let(::title) }
            is Message.ConsoleTests -> { id(m.consoleId); check(m.text.length <= MAX_CONSOLE_CHUNK_CHARS, "Console chunk too large") }
            is Message.DebugState -> {
                if (m.sessionName.isNotEmpty()) title(m.sessionName)
                m.path?.let(::path)
                check(m.line >= -1, "Invalid line")
                check(m.frames.size <= MAX_DEBUG_FRAMES && m.variables.size <= MAX_DEBUG_VARIABLES, "Too much debugger state")
                m.frames.forEach { title(it.name); it.path?.let(::path); check(it.line >= -1, "Invalid line") }
                m.variables.forEach { v ->
                    title(v.name); v.type?.let(::title)
                    check(v.value.length <= MAX_DEBUG_VALUE && v.value.none { it.isISOControl() }, "Invalid value")
                }
            }
            is Message.Breakpoints -> {
                check(m.breakpoints.size <= MAX_BREAKPOINTS, "Too many breakpoints")
                m.breakpoints.forEach { path(it.path); check(it.line >= 0, "Invalid line") }
            }
            is Message.BreakpointRequest -> { path(m.path); check(m.line >= 0, "Invalid line") }
            is Message.VcsStatus -> {
                m.branch?.let(::title)
                check(m.changes.size <= MAX_VCS_CHANGES, "Too many changes")
                m.changes.forEach { path(it.path); check(it.status in VcsChange.ALL, "Invalid status") }
            }
            is Message.CommandRequest -> {
                id(m.requestId)
                check(m.command.isNotBlank() && m.command.length <= MAX_COMMAND_CHARS, "Invalid command")
                check(m.command.none { it.isISOControl() }, "Invalid command")
            }
            is Message.CodeStyle -> check(m.xml.length <= MAX_CODE_STYLE_CHARS, "Code style too large")
            else -> Unit
        }
    }

    private fun mutation(mu: FileMutation) {
        path(mu.path)
        when (mu.kind) {
            FileOpKind.RENAME -> path(mu.newPath ?: throw ProtocolException("Rename without destination"))
            FileOpKind.CREATE_FILE, FileOpKind.WRITE -> mu.contentBase64?.let { content ->
                // Larger files travel without inline content and are fetched in chunks.
                check(content.length <= base64Length(MAX_INLINE_FILE_BYTES), "Inline content too large")
            }
            else -> Unit
        }
    }

    /** Non-blank, bounded, and free of control characters other than line breaks and tabs. */
    private fun chatText(text: String) {
        check(text.isNotBlank() && text.length <= MAX_CHAT_CHARS, "Invalid chat message")
        check(text.none { it.isISOControl() && it != '\n' && it != '\t' }, "Invalid chat message")
    }

    private fun chatEntry(e: ChatEntry) {
        id(e.participantId)
        check(e.displayName.length <= MAX_NAME && e.displayName.none { it.isISOControl() }, "Invalid display name")
        check(e.id >= 0 && e.sentAtMillis >= 0, "Invalid chat entry")
        chatText(e.text)
    }

    private fun features(list: List<String>) {
        check(list.size <= MAX_FEATURES, "Too many features")
        list.forEach(::id)
    }

    /** Console titles, run target names and refusal reasons: one bounded line. */
    private fun title(value: String) =
        check(value.isNotBlank() && value.length <= MAX_TITLE && value.none { it.isISOControl() }, "Invalid title")

    private fun id(value: String) =
        check(value.isNotEmpty() && value.length <= MAX_ID && value.none { it.isISOControl() }, "Invalid identifier")

    private fun path(value: String) {
        if (SafePath.normalizeOrNull(value) != value) throw ProtocolException("Invalid or unsafe path")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw ProtocolException(message)
    }
}
