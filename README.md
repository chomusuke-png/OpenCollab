# OpenCollab

[![CI](https://github.com/chomusuke-png/OpenCollab/actions/workflows/ci.yml/badge.svg)](https://github.com/chomusuke-png/OpenCollab/actions/workflows/ci.yml)

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
  recovery, chat.
* Guests see the host's IDE in their own windows: runs in the Run tool window, builds in the
  Build tool window, test runs as IntelliJ's test tree, where the host's debugger stopped
  (frames and variables), shared breakpoints, the Git branch and changed files, and the code
  style. Editors can ask the host to run a configuration, a build or a terminal command; the
  host approves each request.

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

Versions follow [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`) and are
computed from git, so there is nothing to edit by hand. Starting from the last release tag
(`vX.Y.Z`), the [Conventional Commits](https://www.conventionalcommits.org/) since it decide the
next version:

| Commits since the last release | Next version (from `v1.4.2`) |
|---|---|
| any breaking change: `feat!:` / `fix(core)!:` or a `BREAKING CHANGE:` footer (e.g. a new protocol version) | `v2.0.0` |
| any `feat:` | `v1.5.0` |
| only `fix:`, `perf:`, `refactor:`, `docs:`… | `v1.4.3` |

Local builds are labelled `<next>-dev.<commits since the tag>` (e.g. `1.5.0-dev.3`; also with
uncommitted changes), so building never uses up a version number.

To publish a release, commit and push, then run `./gradlew :plugin:publishRelease` (needs the
[GitHub CLI](https://cli.github.com/), logged in). It builds the computed version, creates GitHub
release and tag `v<version>` with the plugin ZIP and an `updatePlugins.xml`, and refuses when
there is nothing new since the last tag.

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

Guests never get a shell or unrestricted filesystem access on the host, and never control its
debugger or version control: they see them, and anything that runs on the host's machine runs
only after the host's user allows that request (see [docs/SECURITY.md](docs/SECURITY.md)).
