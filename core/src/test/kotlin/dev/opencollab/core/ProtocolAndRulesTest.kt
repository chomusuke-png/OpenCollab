package dev.opencollab.core

import dev.opencollab.core.files.SafePath
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.files.UnsafePathException
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.presence.PresenceThrottler
import dev.opencollab.core.presence.RateLimiter
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.protocol.ProtocolException
import dev.opencollab.core.protocol.TextRange
import dev.opencollab.core.util.Cancellable
import dev.opencollab.core.util.Scheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FakeScheduler : Scheduler {
    var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    override fun nowMillis() = now
    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val entry = (now + delayMillis) to task
        tasks += entry
        return Cancellable { tasks.remove(entry) }
    }
    fun advance(ms: Long) {
        now += ms
        while (true) {
            val due = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: break
            tasks.remove(due)
            due.second()
        }
    }
}

class ProtocolAndRulesTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `safe paths accept normal project paths`() {
        for (ok in listOf("a.txt", "src/main/kotlin/App.kt", ".idea/misc.xml", "dir with space/file.txt", "ünïcødé/文件.kt")) {
            assertEquals(ok, SafePath.normalizeOrNull(ok), ok)
        }
    }

    @Test
    fun `safe paths reject traversal, absolute and device paths`() {
        val bad = listOf(
            "", "/", "/etc/passwd", "../x", "a/../../x", "a/./b", "./a", "a//b", "a/", "C:/x", "C:x", "c:\\x",
            "a\\b", "..", ".", "CON", "nul.txt", "aux", "COM1.log", "file.", "file ", "a\u0000b", "a\nb", "x:stream",
        )
        for (b in bad) assertNull(SafePath.normalizeOrNull(b), "should reject '$b'")
    }

    @Test
    fun `resolveInside refuses to follow symlinks out of the root`() {
        val root = Files.createDirectories(tmp.resolve("root"))
        val outside = Files.createDirectories(tmp.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "s")
        val link = root.resolve("link")
        val created = try { Files.createSymbolicLink(link, outside); true } catch (e: Exception) { false }
        if (created) {
            assertThrows<UnsafePathException> { SafePath.resolveInside(root, "link/secret.txt") }
            assertThrows<UnsafePathException> { SafePath.resolveInside(root, "link") }
        }
        assertEquals(root.resolve("a/b.txt").toAbsolutePath().normalize(), SafePath.resolveInside(root, "a/b.txt"))
        assertThrows<UnsafePathException> { SafePath.resolveInside(root, "../outside/secret.txt") }
    }

    @Test
    fun `default sharing rules exclude secrets and local state`() {
        val rules = SharingRules()
        val excluded = listOf(
            ".env", "app/.env.production", "local.properties", "certs/server.key", "a/b/id_rsa", "id_ed25519.pub",
            ".git/config", "sub/.git/HEAD", ".idea/workspace.xml", "build/classes/A.class", "web/node_modules/x/index.js",
            "aws/credentials", ".aws/config", "config/secrets.yml", "infra/terraform.tfstate", "release.jks",
            ".gradle/caches/x", "app/google-services.json", ".opencollab/mirror.json",
        )
        excluded.forEach { assertFalse(rules.isShared(it), "$it must be excluded") }
        val shared = listOf("src/Main.kt", "build.gradle.kts", "settings.gradle.kts", ".idea/misc.xml", "README.md", "gradle.properties", "docs/building.md")
        shared.forEach { assertTrue(rules.isShared(it), "$it must be shared") }
    }

    @Test
    fun `explicit includes override default excludes but never hard excludes`() {
        val rules = SharingRules(includes = listOf("build/reports/**", ".env.example", ".git/config"))
        assertTrue(rules.isShared("build/reports/test/index.html"))
        assertTrue(rules.isShared(".env.example"))
        assertFalse(rules.isShared("build/classes/A.class"))
        assertFalse(rules.isShared(".git/config"))
        val custom = SharingRules(excludes = SharingRules.DEFAULT_EXCLUDES + listOf("docs/internal/**", "*.log"))
        assertFalse(custom.isShared("docs/internal/plan.md"))
        assertFalse(custom.isShared("logs/app.log"))
        assertTrue(custom.isShared("docs/public.md"))
    }

    @Test
    fun `guest read-only rules protect execution-related files`() {
        val rules = SharingRules()
        for (p in listOf("gradlew", "gradle/wrapper/gradle-wrapper.properties", ".idea/runConfigurations/App.xml", ".github/workflows/ci.yml", "Run.run.xml")) {
            assertTrue(rules.isGuestReadOnly(p), p)
        }
        assertFalse(rules.isGuestReadOnly("src/Main.kt"))
    }

    @Test
    fun `codec round trips and validates messages`() {
        val op = TextOperation.replace(5, 1, 2, "xy")
        val m = Message.DocOp("src/A.kt", "op-1", 3, MessageCodec.opToJson(op))
        val decoded = MessageCodec.decode(MessageCodec.encode(m)) as Message.DocOp
        assertEquals(op, MessageCodec.opFromJson(decoded.op))
        val presence = Message.Presence("a.kt", 3, listOf(TextRange(1, 3)), revision = 7)
        assertEquals(presence, MessageCodec.decode(MessageCodec.encode(presence)))

        val invalid = listOf(
            """not json""",
            """{"t":"unknownType"}""",
            """{"t":"op","path":"../x","opId":"1","baseRevision":0,"op":[1]}""",
            """{"t":"op","path":"a","opId":"1","baseRevision":-1,"op":[1]}""",
            """{"t":"op","path":"a","opId":"1","baseRevision":0,"op":[0]}""",
            """{"t":"op","path":"a","opId":"1","baseRevision":0,"op":[""]}""",
            """{"t":"op","path":"a","opId":"1","baseRevision":0,"op":{"x":1}}""",
            """{"t":"op","path":"a","opId":"1","baseRevision":0,"op":[1.5]}""",
            """{"t":"hello","protocolVersion":$PROTOCOL_VERSION,"displayName":"","token":"t"}""",
            """{"t":"hello","protocolVersion":$PROTOCOL_VERSION,"displayName":"a\u0007","token":"t"}""",
            """{"t":"presence","path":"a","caret":-1,"selections":[]}""",
            """{"t":"presence","path":"a","caret":1,"selections":[{"start":5,"end":2}]}""",
            """{"t":"presence","path":"a","caret":1,"selections":[],"revision":-2}""",
            """{"t":"commandRequest","requestId":"1","command":"ls\u0007"}""",
            """{"t":"commandRequest","requestId":"1","command":"  "}""",
            """{"t":"breakpointRequest","path":"../a","line":1,"add":true}""",
            """{"t":"breakpoints","breakpoints":[{"path":"a","line":-1}]}""",
            """{"t":"vcsStatus","branch":"main","changes":[{"path":"a","status":"exploded"}]}""",
            """{"t":"debugState","active":true,"variables":[{"name":"x","value":"a\u0000"}]}""",
            """{"t":"hello","protocolVersion":$PROTOCOL_VERSION,"displayName":"a","token":"t","features":[""]}""",
            """{"t":"fileRequest","requestId":"1","mutation":{"kind":"RENAME","path":"a"}}""",
            """{"t":"fileRequest","requestId":"1","mutation":{"kind":"WRITE","path":"../a"}}""",
            """{"t":"chunkAck","requestId":"1","receivedBytes":-5}""",
            """{"t":"chatSend","text":""}""",
            """{"t":"chatSend","text":"   "}""",
            """{"t":"chatSend","text":"bell\u0007"}""",
            """{"t":"chatSend","text":"${"x".repeat(MessageCodec.MAX_CHAT_CHARS + 1)}"}""",
            """{"t":"chat","entry":{"id":1,"participantId":"p","displayName":"a\u0007","colorIndex":0,"text":"hi","sentAtMillis":0}}""",
        )
        for (raw in invalid) assertThrows<ProtocolException>(raw) { MessageCodec.decode(raw) }
        assertThrows<ProtocolException> { MessageCodec.decode("x".repeat(MessageCodec.MAX_FRAME_CHARS + 1)) }
    }

    @Test
    fun `the host project folder is hidden in console text and each guest gets its own back`() {
        val hostDir = tmp.resolve("tests")
        val sep = java.io.File.separator
        val text = "error: $hostDir${sep}src${sep}Main.kt:12:5: boom\nunrelated: ${hostDir}2${sep}x.kt\n"
        val hidden = dev.opencollab.core.files.ProjectDirMacro(hostDir).hide(text)
        assertEquals("error: \$PROJECT_DIR\$/src/Main.kt:12:5: boom\nunrelated: ${hostDir}2${sep}x.kt\n", hidden)

        val mirror = tmp.resolve("mirror")
        val shown = dev.opencollab.core.files.ProjectDirMacro(mirror).reveal(hidden)
        val mirrorPath = mirror.toAbsolutePath().normalize().toString().replace('\\', '/')
        assertTrue(shown.startsWith("error: $mirrorPath/src/Main.kt:12:5: boom\n"), shown)
    }

    @Test
    fun `presence throttler sends first update immediately and latest on trailing edge`() {
        val clock = FakeScheduler()
        val sent = mutableListOf<Int>()
        val t = PresenceThrottler<Int>(100, clock) { sent += it }
        t.submit(1)
        t.submit(2)
        t.submit(3)
        assertEquals(listOf(1), sent)
        clock.advance(100)
        assertEquals(listOf(1, 3), sent)
        clock.advance(500)
        t.submit(4)
        assertEquals(listOf(1, 3, 4), sent)
        t.submit(5)
        t.close()
        clock.advance(1000)
        assertEquals(listOf(1, 3, 4), sent)
    }

    @Test
    fun `rate limiter drops floods`() {
        val clock = FakeScheduler()
        val limiter = RateLimiter(10, clock)
        val accepted = (1..100).count { limiter.tryAcquire() }
        assertEquals(10, accepted)
        clock.advance(500)
        assertEquals(5, (1..100).count { limiter.tryAcquire() })
    }
}
