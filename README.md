# OpenCollab

A free IntelliJ IDEA plugin for real-time collaboration between separate IntelliJ
installations. A **host** owns the authoritative project and invites other developers.
**Guests** use their own IDE, settings and plugins: they edit shared files together, see each
other's cursors and selections, and navigate the project in their own IDE.

* Works with the free features of IntelliJ IDEA 2026.2.x (depends only on the IntelliJ
  Platform core). No Remote Development / split mode, no Ultimate subscription.
* Direct TLS connections on a LAN/VPN, or a **self-hosted relay** for remote networks.
  Through the relay, traffic is end-to-end TLS between guest and host — the relay only sees
  ciphertext.
* Host-authoritative operational transformation (OT) for documents, incremental file
  synchronisation, presence, host-enforced roles (host / editor / viewer), reconnect with
  recovery.

| Document | Contents |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | Install the ZIP, start a session, invite people, roles, sharing rules |
| [docs/RELAY_SERVER.md](docs/RELAY_SERVER.md) | Deploy and configure the self-hosted relay |
| [docs/DEVELOPER.md](docs/DEVELOPER.md) | Build, test, run, architecture, module layout |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Wire protocol and synchronisation algorithm |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, what is protected, known limits |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common problems and fixes |

## Quick start

Prerequisites: JDK 21 or newer to run Gradle (JDK 21 and JDK 25 toolchains are used for compilation and are
auto-provisioned if missing), internet access for the first build (it downloads IntelliJ IDEA
2026.2.3, about 1.6 GB).

```bash
./gradlew :core:test :transport:test :relay-server:test   # fast tests (no IDE)
./gradlew :plugin:test                                     # IntelliJ Platform tests, incl. two IDE processes
./gradlew :plugin:buildPlugin                              # -> plugin/build/distributions/OpenCollab-<version>.zip
./gradlew :plugin:runIde                                   # sandbox IDE with the plugin
./gradlew :relay-server:shadowJar                          # -> relay-server/build/libs/opencollab-relay-<version>-all.jar
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Install the ZIP via *Settings | Plugins | ⚙ | Install Plugin from Disk…*, open the
**OpenCollab** tool window (right side bar), and click **Share this project**. Send the copied
invitation (`oc1:…`) privately to a teammate, who opens an **empty folder** as a project and
clicks **Join with an invitation**.

## Versions, releases and updates

The version is `opencollabVersion` in `gradle.properties`, as Major.Minor.Revision. Every
successful `:plugin:buildPlugin` raises Revision by one for the next build; raise Major or Minor
by hand and reset Revision to 0.

To publish a release, commit and push, then run `./gradlew :plugin:publishRelease` (needs the
[GitHub CLI](https://cli.github.com/), logged in). It creates GitHub release `v<version>` with
the plugin ZIP and an `updatePlugins.xml`.

Installed copies update themselves: on first start the plugin adds
`https://github.com/chomusuke-png/OpenCollab/releases/latest/download/updatePlugins.xml` to
*Settings | Plugins | ⚙ | Manage Plugin Repositories…*, and the IDE then offers each new
release as an ordinary plugin update. This needs the repository to be public.

## Project layout

```
OpenCollab/
├── core/          IntelliJ-independent protocol, OT engine, file sync, permissions, sessions
├── transport/     TLS WebSocket transports, relay client, end-to-end TLS tunnel, invitations
├── relay-server/  Self-hosted signaling/relay server (runnable fat JAR)
├── plugin/        IntelliJ IDEA plugin (tool window, settings, editor/VFS bridge)
└── docs/          User, developer, protocol, security and relay documentation
```

## Status

What is implemented and verified by automated tests is listed in
[docs/DEVELOPER.md](docs/DEVELOPER.md#verification-status), including an end-to-end test where
two independent IntelliJ Platform processes edit the same document simultaneously over TLS.
Known limitations are listed in [docs/SECURITY.md](docs/SECURITY.md#known-limitations) and
[docs/USER_GUIDE.md](docs/USER_GUIDE.md#limitations).

Terminal, debugger and run-configuration sharing are intentionally **not** implemented: they
need an authorization model that does not exist yet, and guests never get shell or
unrestricted filesystem access to the host.
