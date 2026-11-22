# Developer guide

## Prerequisites

* JDK 21+ to run Gradle (tested with Temurin 25). `core`, `transport` and `relay-server` compile with a
  JDK 21 toolchain; the plugin compiles with JDK 25 (IntelliJ IDEA 2026.2 runs on Java 25). Toolchains are detected
  locally or provisioned automatically (foojay resolver).
* Gradle is provided by the wrapper (9.6.0).
* First build: internet access; the IntelliJ Platform Gradle Plugin (2.20.1) downloads IntelliJ
  IDEA 2026.2.3 (build 262.10968.63, ~1.6 GB) into the Gradle cache.
* Windows: keep the checkout path short to avoid path-length issues.

## Modules

| Module | Depends on | Contents |
|---|---|---|
| `core` | kotlinx-serialization | `ot` (TextOperation, ServerDocument, ClientDocument, TextDiff), `protocol` (messages, codec/validation), `files` (SafePath, SharingRules, ProjectFiles, MirrorWorkspace), `presence` (throttler, rate limiter), `session` (HostSession, CollabClient), `transport` (interfaces, in-memory) |
| `transport` | core, Java-WebSocket, BouncyCastle | TLS identity & pinning, direct WSS server/client, relay host/guest transports, `TlsTunnel` (end-to-end TLS over the relay), `Invite` |
| `relay-server` | transport | `RelayServer`, CLI `RelayMain`, fat JAR via Shadow |
| `plugin` | transport + IntelliJ Platform | Settings, tool window (`CollabPanel`, `ChatPanel`, `HostIdePanel`), actions, `CollabSessionService`, `IdeBridge`, `VfsTreeWriter`, `PresenceRenderer`; host side `HostConsoleSharing` (runs, builds, test events, run and command requests) and `HostIdeSharing` (debugger via `DebugSnapshot`, breakpoints, VCS status, code style); guest side `GuestRunMirror` (Run/Build windows, test trees) and `GuestIdeMirror` (execution point, breakpoints, code style) |

`core` and `transport` never reference `com.intellij.*`. The plugin compiles against the platform
modules `intellij.platform.smRunner`, `intellij.platform.testRunner` and
`intellij.platform.vcs.dvcs(.impl)` (declared with `bundledModule` in `plugin/build.gradle.kts`);
at runtime they are part of IntelliJ IDEA.

## Localization

Plugin texts live in `plugin/src/main/resources/messages/OpenCollabBundle.properties` (English,
default) and `OpenCollabBundle_es.properties` (Spanish, non-ASCII written as `\uXXXX`), loaded
through `OpenCollabBundle` (a `DynamicBundle`, so the IDE language decides). Action texts use the
`action.<id>.text` convention and the tool window/configurable/notification group use bundle keys
in `plugin.xml`. `BundleTest` fails if a key used in code is missing, if the Spanish file has
missing or extra keys, or if `{n}` placeholders differ. To add a language, add
`OpenCollabBundle_<lang>.properties` with the same keys.

Messages produced inside `core`/`transport` (protocol errors from the host, connection failure
details) and messages sent over the network stay in English.

## Build, test, run

```bash
./gradlew :core:test :transport:test :relay-server:test   # protocol, OT, files, sessions, TLS, relay (~30 s)
./gradlew :plugin:test                                     # IntelliJ Platform tests (headless IDE)
./gradlew build                                            # everything, incl. plugin tests
./gradlew :plugin:buildPlugin                              # plugin/build/distributions/OpenCollab-<version>.zip
./gradlew :plugin:verifyPlugin                             # JetBrains Plugin Verifier vs IntelliJ IDEA 2026.2.3
./gradlew :plugin:runIde                                   # sandbox IDE with the plugin
./gradlew :plugin:runIdeGuest                              # second sandbox IDE (separate config/system), in another terminal
./gradlew :relay-server:shadowJar                          # relay-server/build/libs/opencollab-relay-<version>-all.jar
./gradlew :relay-server:run --args="--self-signed --allow-anonymous-hosts --bind 127.0.0.1"   # local relay for development
```

### Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request: `./gradlew check
buildPlugin` (all modules and their tests) and then `:plugin:verifyPlugin`, on Linux with Java 21
and 25. It checks out the full history because the version comes from the release tags. When a
run fails, the test reports are attached to it; every run attaches the built plugin ZIP. Release
only from a commit whose run passed.

### Trying it with two IDEs on one machine

1. Terminal 1: `./gradlew :plugin:runIde`; open (or create) a project, *Settings | Tools |
   OpenCollab*: listen address `127.0.0.1`; in the OpenCollab tool window **Share this
   project**, then **Invite ▾** > editor invitation.
2. Terminal 2: `./gradlew :plugin:runIdeGuest`; open an empty folder as a project; **Join with
   an invitation**, paste the invitation.
3. Open the same file in both IDEs and type. On the host, run a test and debug with a
   breakpoint to see the guest's test tree, execution point and **Host** tab.

On Windows, if the checkout path is long, the sandbox IDEs cannot start their compile server
(`CreateProcess error=267`). Put the sandboxes in a short folder by adding
`-PsandboxDir=C:/oc-sandbox` to both `runIde` and `runIdeGuest`.

## Architecture

```
 Host IDE                                                          Guest IDE
 ┌────────────────────────────────────────────┐                   ┌──────────────────────────┐
 │ IdeBridge (EDT, write actions, VFS events) │                   │ IdeBridge                │
 │        │ in-memory connection              │                   │   │                      │
 │ CollabClient (host role)                   │                   │ CollabClient (guest)     │
 │        │                                   │   wss (pinned)    │   │  MirrorWorkspace     │
 │ HostSession  ◀── DirectHostTransport ◀─────┼───────────────────┼───┘                      │
 │  ServerDocument per file                   │                   └──────────────────────────┘
 │  ProjectFiles (rules, VFS writer)          │   relay + TlsTunnel (end-to-end TLS)
 │              ◀── RelayHostTransport ◀──────┼──── relay-server ◀──── other guests
 └────────────────────────────────────────────┘
```

* **Threads.** `HostSession` state lives on one serial thread; file reading/chunking uses a
  separate I/O thread. `CollabClient` processes inbound messages on its own thread and runs all
  document work on the *document executor* — in the plugin that is the EDT
  (`invokeLater(…, ModalityState.nonModal())`). Network I/O never runs on the EDT; sending only
  enqueues.
* **Host editor = ordinary participant.** The host's own IDE connects to its session through an
  in-memory connection with a process-local host token, so host and guests share one code path.
  When a document is first shared, the host's current editor text (including unsaved changes) is
  read and registered with the host bridge atomically on the EDT, so no keystroke is lost.
* **Documents.** `IdeBridge` listens to the editor event multicaster; local changes become
  `TextOperation`s. Remote operations are applied with `Document.insertString/deleteString` inside
  `CommandProcessor.runUndoTransparentAction` + write action, with a thread-local flag that
  prevents echo.
* **VFS.** Host and guest changes are captured by a `BulkFileListener`. Remote changes are applied
  by `VfsTreeWriter` inside `WriteAction.computeAndWait`; the initial snapshot is written with NIO
  and followed by one `VfsUtil.markDirtyAndRefresh`.
* **Permissions** are enforced only in `HostSession` (plus read-only editors on viewers for UX).

## Test suites

| Suite | What it covers |
|---|---|
| `core` `TextOperationTest` | apply/compose/transform (randomised TP1 convergence and compose, 300 fixed seeds each; diff, 100), surrogate-safe diff, caret transformation, wire validation |
| `core` `SyncEngineTest` | randomised multi-client simulations with random delays and duplicated deliveries (200 fixed seeds, plus 100 with many operations in flight per client); duplicate op detection; stale ops beyond checkpoint recovered by resync; invalid ops rejected; unsynced-document rebase; no double-apply after lost ack; an author's operations in flight transformed only against what it had not seen |
| `core` `HostSessionTest` | snapshot excludes secrets/local state; 3 participants typing concurrently from threads converge; viewer edits and file creation rejected and rolled back; role changes only by host, never to HOST; traversal/exclusion/read-only checks for file requests; raw traversal paths rejected at protocol layer; invalid token & protocol version refused; no processing before authentication; presence relay/attribution/cleanup; carets never arrive before their text and stay on their text while others type; cursor bursts keep the final position; incremental host file events incl. rename-to-excluded; unsaved document vs disk separation; pre-registration edits rebased; chunk hash verification; ping and session age; chat relay, history, typing and rate limit; consoles reach guests only and are replayed; run and command requests (editors only, known targets, host's answer, expiry); breakpoint requests only from editors and in shared files; host IDE state only to guests announcing the feature, latest replayed to later joiners |
| `core` `ReconnectTest` | offline edits recovered from history with participant identity resumed; recovery after history pruning without duplication; missed file events replayed; re-joining a mirror downloads only changes and keeps guest-local files; refusing non-empty folders |
| `core` `LargeFileTest` | 120 MB file streamed to a guest and hash-verified with no temp files left; host stops at the 8 MB unacknowledged window and resumes on `chunkAck`; large host changes announced without content and fetched; guest uploads above 8 MB refused; oversize inline content rejected by the codec |
| `core` `ProtocolAndRulesTest` | SafePath accept/reject lists, symlink escape, default/explicit/read-only sharing rules, codec validation of malformed messages (incl. features, commands, breakpoints, VCS and debugger state) and oversize frames, project folder hidden in console text, presence throttling and rate limiting |
| `transport` `TransportTest` | direct TLS messaging incl. 2 MB frames, wrong-fingerprint rejection, system-CA rejection of self-signed, `ws://` refused, TLS tunnel over an untrusted channel incl. wrong pin, invitation round-trip/validation, full host+guest session over direct TLS |
| `core` `SecurityTest` | newcomers wait for the host's approval (let in, refused, timeout, leaving while waiting, at most N waiting, older guests); resuming skips approval even after revocation; single-use, expiring and revoked invitations; permissions taken away refuse waiting and new run requests, commands, breakpoints and chat; viewers never get more than chat; role changes reset permissions; people leaving and joining again (same installation or name) keep the role and permissions given to them, never more than their invitation |
| `core` `AddressMaskTest` | IPv4/IPv6 addresses keep only their last part, host names keep their domain, loopback, certificate fingerprints and times are left alone |
| `transport` `PortForwardingTest`, `NetworkHintsTest` | public endpoint construction and validation, public-IP answer parsing, endpoint ordering (own subnet → public → other networks), detection of addresses from other private networks |
| `plugin` `PortForwardingAndJoinTest` | plugin host with port forwarding advertises `wss://<public>:<port>` and a guest joins using only that endpoint; port 0 refused with forwarding; pending join completes when the new project opens; suggested folder avoids non-empty folders |
| `plugin` `BundleTest` | English/Spanish key parity, placeholders, apostrophe rules, keys used in code exist, Spanish resolution |
| `plugin` `CollabPanelTest` | the bottom tabs (chat, activity, host) stay in the tool window when leaving and joining again |
| `plugin` `SharedTestTreeTest` | test events in the host's format build IntelliJ's test tree on a guest (suite, passed and failed tests, failure message) and locate the failing test on its line in the guest's copy |
| `relay-server` `RelayServerTest` | end-to-end encrypted relay messaging (relay logs never contain content), wrong host secret, unknown room, impostor host behind relay, relay certificate pinning, room capacity, host disconnect |
| `relay-server` `RelaySessionTest` | full session (snapshot + 2 guests co-editing) through the relay only |
| `plugin` `PluginIntegrationTest` | real IntelliJ Platform 2026.2.3: plugin **host** serving a remote guest over TLS (snapshot without secrets, guest→host document edits, host→guest edits, concurrent convergence, presence, guest file create via VFS, host VFS create mirrored, viewer refused); plugin **guest** joining a remote host (snapshot into VFS, edits without editor, edits from another guest applied to the IntelliJ document, files from others) |
| `plugin` `TwoIdeInstancesTest` | **two independent IntelliJ Platform processes** (separate JVMs and config/system dirs), both running the plugin, connected over TLS; both type 40 lines concurrently into the same document; asserts identical final text containing all 80 lines |

## Verification status

Verified by the automated tests above (all passing):

* Multi-module build; plugin ZIP builds; JetBrains Plugin Verifier run against IntelliJ IDEA 2026.2.3
  (see the latest verifier report in `plugin/build/reports/pluginVerifier`).
* Transport-independent protocol with validation; host-authoritative OT with revisions, unique op
  ids, duplicate detection, stale-op recovery, acknowledgements, concurrent edits.
* Simultaneous editing between two independent IntelliJ Platform instances (headless) over TLS.
* Snapshots and incremental file sync (create, write, rename/move, delete), default secret
  exclusion, explicit include rules, guest read-only rules, unsaved-document vs disk separation.
* Presence relay with throttling, host attribution and cleanup on disconnect.
* Host-side enforcement of host/editor/viewer permissions for every operation.
* Reconnect with resume, operation-history catch-up, checkpoint/snapshot recovery, file event replay.
* Direct TLS with certificate pinning; self-hosted relay with host secret and end-to-end TLS.

Implemented but **not** covered by automated tests: the tool-window and settings UI (Swing), the
painting of remote carets/selections (presence *delivery* to the bridge is tested), *Follow*,
guest-side save propagation (`docSaved`), relay host re-registration after a relay restart,
read-only editor state for viewers (host-side rejection of viewer edits *is* tested), and the
IDE side of the host's IDE state: Run/Build window mirroring, collecting test events, debugger
frames and variables, breakpoint mirroring, VCS status, code style and running approved
commands (their messages and permissions *are* tested in `core`, and the guest test tree in
`SharedTestTreeTest`).
