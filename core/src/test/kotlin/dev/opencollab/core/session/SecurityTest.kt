package dev.opencollab.core.session

import dev.opencollab.core.protocol.Features
import dev.opencollab.core.protocol.Permission
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.RunTargetKind
import dev.opencollab.core.session.Harness.Companion.awaitState
import dev.opencollab.core.session.Harness.Companion.eventually
import dev.opencollab.core.session.Harness.Companion.write
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Join approval, revocable and limited invitations, and per-participant permissions. */
class SecurityTest {
    @TempDir lateinit var tmp: Path
    private val host: Path get() = tmp.resolve("host").also { Files.createDirectories(it) }
    private fun guestDir(name: String) = tmp.resolve(name).also { Files.createDirectories(it) }

    private fun project() = write(host, "src/Main.kt", "fun main() {}\n")

    /** Disconnected after trying (a client also starts out disconnected). */
    private fun awaitRefused(ide: FakeIde) =
        eventually { ide.states.lastOrNull() == ClientState.DISCONNECTED && ide.stateDetails.isNotEmpty() }

    @Test
    fun `with approval on, newcomers wait until the host lets them in or refuses them`() {
        project()
        Harness(host, config = HostConfig("demo", approveJoins = true)).use { h ->
            val alice = h.guest("alice", guestDir("alice"))
            eventually { h.joinRequests.size == 1 && alice.joinPending != null }
            val request = h.joinRequests.single()
            assertEquals("alice", request.displayName)
            assertEquals(Role.EDITOR, request.role)
            assertFalse(alice.client.state == ClientState.CONNECTED, "nothing before the host answers")
            assertEquals(1, h.session.participants().size, "only the host is in")

            h.session.answerJoin(request.requestId, accepted = true)
            awaitState(alice, ClientState.CONNECTED)
            assertTrue(Files.exists(guestDir("alice").resolve("src/Main.kt")))
            assertTrue(request.requestId in h.closedJoinRequests)

            val mallory = h.guest("mallory", guestDir("mallory"))
            eventually { h.joinRequests.size == 2 }
            h.session.answerJoin(h.joinRequests.last().requestId, accepted = false)
            awaitRefused(mallory)
            assertTrue(mallory.stateDetails.last().contains("did not let you join"), mallory.stateDetails.toString())
            assertFalse(Files.exists(guestDir("mallory").resolve("src/Main.kt")), "a refused guest gets no files")
        }
    }

    @Test
    fun `unanswered join requests expire, and leaving while waiting closes the request`() {
        project()
        Harness(host, config = HostConfig("demo", approveJoins = true, joinApprovalTimeoutMillis = 300)).use { h ->
            val alice = h.guest("alice", guestDir("alice"))
            awaitRefused(alice)
            assertTrue(alice.stateDetails.last().contains("did not answer"), alice.stateDetails.toString())
            eventually { h.closedJoinRequests.size == 1 }
        }
        Harness(host, config = HostConfig("demo", approveJoins = true)).use { h ->
            val bob = h.guest("bob", guestDir("bob"))
            eventually { h.joinRequests.size == 1 }
            bob.client.close()
            eventually { h.joinRequests.single().requestId in h.closedJoinRequests }
        }
    }

    @Test
    fun `a leaked invitation cannot flood the host with join requests`() {
        project()
        Harness(host, config = HostConfig("demo", approveJoins = true, maxPendingJoins = 2)).use { h ->
            (1..2).forEach { h.guest("g$it", guestDir("g$it")) }
            eventually { h.joinRequests.size == 2 }
            val third = h.guest("g3", guestDir("g3"))
            awaitRefused(third)
            assertTrue(third.stateDetails.last().contains("Too many people"), third.stateDetails.toString())
            assertEquals(2, h.joinRequests.size)
        }
    }

    @Test
    fun `a participant reconnecting does not need approval again, even after invitations were revoked`() {
        project()
        Harness(host, config = HostConfig("demo", approveJoins = true)).use { h ->
            val alice = h.guest("alice", guestDir("alice"))
            eventually { h.joinRequests.size == 1 }
            h.session.answerJoin(h.joinRequests.single().requestId, accepted = true)
            awaitState(alice, ClientState.CONNECTED)
            h.session.revokeInvites()
            h.links.last().close("network down")
            eventually { alice.states.contains(ClientState.RECONNECTING) }
            awaitState(alice, ClientState.CONNECTED)
            assertEquals(1, h.joinRequests.size, "not asked again")
        }
    }

    @Test
    fun `revoked, expired and used up invitations stop working`() {
        project()
        Harness(host).use { h ->
            val single = h.session.createInvite(Role.VIEWER, maxUses = 1)
            val first = h.guest("first", guestDir("first"), token = single)
            awaitState(first, ClientState.CONNECTED)
            assertEquals(Role.VIEWER, first.client.role)
            val second = h.guest("second", guestDir("second"), token = single)
            awaitRefused(second)
            assertTrue(second.stateDetails.last().contains("Invalid or expired invitation"))

            val shortLived = h.session.createInvite(Role.EDITOR, ttlMillis = 100)
            Thread.sleep(250)
            val late = h.guest("late", guestDir("late"), token = shortLived)
            awaitRefused(late)

            val editorInvite = h.session.config.editorToken
            h.session.revokeInvites()
            val afterRevoke = h.guest("revoked", guestDir("revoked"), token = editorInvite)
            awaitRefused(afterRevoke)
            assertEquals(ClientState.CONNECTED, first.client.state, "people already in stay")

            val fresh = h.guest("fresh", guestDir("fresh"), token = h.session.createInvite(Role.EDITOR))
            awaitState(fresh, ClientState.CONNECTED)
        }
    }

    @Test
    fun `the host can take permissions away and every request is checked again`() {
        project()
        Harness(host).use { h ->
            val alice = h.guest("alice", guestDir("alice"))
            awaitState(alice, ClientState.CONNECTED)
            assertEquals(Permission.ALL.toSet(), alice.client.permissions())
            h.session.setRunTargets(listOf(RunTarget("build", "Build Project", RunTargetKind.BUILD)))
            eventually { alice.runTargets.isNotEmpty() }

            // A request already waiting is refused when the permission goes away.
            val waiting = alice.client.requestRun("build")!!
            eventually { h.runRequests.size == 1 }
            val id = alice.client.participantId
            h.session.setPermissions(id, setOf(Permission.CHAT))
            eventually { alice.permissionUpdates.lastOrNull() == setOf(Permission.CHAT) }
            eventually { alice.runResponses.any { it.requestId == waiting } }
            assertEquals(RunRefusal.NOT_ALLOWED, alice.runResponses.single().reason)
            val request = h.runRequests.single()
            assertFalse(h.session.answerRun(request.participantId, request.requestId, accepted = true).get(5, java.util.concurrent.TimeUnit.SECONDS))

            Thread.sleep(1_100) // run requests are limited to one per second
            val refused = alice.client.requestRun("build")!!
            eventually { alice.runResponses.any { it.requestId == refused } }
            assertEquals(RunRefusal.NOT_ALLOWED, alice.runResponses.last().reason)
            assertNull(alice.client.requestCommand("ls"), "the client does not even ask")
            assertFalse(alice.client.requestBreakpoint("src/Main.kt", 0, add = true))

            // Chat taken away: the host refuses messages even from a modified client.
            h.session.setPermissions(id, emptySet())
            eventually { alice.permissionUpdates.lastOrNull() == emptySet<String>() }
            assertFalse(alice.client.sendChat("hello"))
            h.hostIde.client.sendChat("still here")
            eventually { alice.chat.any { it.text == "still here" } }
            assertTrue(alice.chat.none { it.text == "hello" })

            // Everyone sees the change in the participant list.
            eventually { h.hostIde.client.participants().single { it.id == id }.effectivePermissions.isEmpty() }
        }
    }

    @Test
    fun `viewers can get at most chat, and a role change resets permissions to the role's defaults`() {
        project()
        Harness(host).use { h ->
            val vic = h.guest("vic", guestDir("vic"), token = h.session.config.viewerToken)
            awaitState(vic, ClientState.CONNECTED)
            assertEquals(setOf(Permission.CHAT), vic.client.permissions())
            h.session.setPermissions(vic.client.participantId, Permission.ALL.toSet())
            Thread.sleep(200)
            assertEquals(setOf(Permission.CHAT), vic.client.permissions(), "running or debugging is never given to viewers")

            val alice = h.guest("alice", guestDir("alice"))
            awaitState(alice, ClientState.CONNECTED)
            h.session.setPermissions(alice.client.participantId, emptySet())
            eventually { alice.client.permissions().isEmpty() }
            h.session.setRole(alice.client.participantId, Role.VIEWER)
            eventually { alice.client.role == Role.VIEWER && alice.client.permissions() == setOf(Permission.CHAT) }
            h.session.setRole(alice.client.participantId, Role.EDITOR)
            eventually { alice.client.permissions() == Permission.ALL.toSet() }
        }
    }

    @Test
    fun `older guests without the new features still join and are held to the same rules`() {
        project()
        Harness(host, config = HostConfig("demo", approveJoins = true)).use { h ->
            val old = h.guest("old", guestDir("old"), features = emptyList())
            eventually { h.joinRequests.size == 1 }
            assertNull(old.joinPending, "no message it would not understand")
            h.session.answerJoin(h.joinRequests.single().requestId, accepted = true)
            awaitState(old, ClientState.CONNECTED)
            assertFalse(Features.PERMISSIONS in old.client.sharedFeatures)
        }
    }
}
