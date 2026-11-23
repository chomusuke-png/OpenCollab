# What's new in OpenCollab

Every version, newest first. Hosts and guests should use the same major version: a new major
version changes the protocol, and older plugins cannot join it.

<!--
How to add a release: put the changes under "Unreleased" as you go. Before running
publishRelease, rename that heading to "## [X.Y.Z] - YYYY-MM-DD" with the version the build
prints, and add a new empty "Unreleased" above it. publishRelease refuses to publish a version
without its own section; the section becomes the release notes on GitHub and the plugin's
"What's New" in the IDE. Use only "### Added", "### Changed" and "### Fixed" with "- " items;
`code`, **bold** and [links](https://example.com) work inside them.
-->

## [Unreleased]

## [3.11.0] - 2026-11-23

### Added
- **The host learns who can use an invitation before sending it:** when all its addresses are
  private (or only this computer), the tool window says that only people on your network can
  join, with a link to the connection settings, and copying the invitation says it again.
- **Relay URL checks:** a listen address such as `wss://0.0.0.0:…` is refused (in the settings and
  when hosting starts), and `localhost` or a private address gets a warning, since guests could
  not reach it. A guest given such an invitation is told what the host must change.
- **One-command relay install:** `install.sh` (Linux, WSL, macOS) and `install.ps1` (Windows with
  Docker Desktop) check Docker, ask whether you have a domain or which port to use, create the
  host secret, start the relay and print what hosts enter in the plugin. Running it again
  updates the relay. See [the relay guide](docs/RELAY_SERVER.md#quick-install).

### Changed
- The plugin's author in *Settings | Plugins* is now **chomusuke-png & hi444** instead of
  "OpenCollab contributors".

## [3.10.0] - 2026-11-23

### Added
- **A new icon** for OpenCollab, on the plugin's page in *Settings | Plugins* and, simplified, on the tool window button in the side bar.

## [3.9.0] - 2026-11-22

### Added
- **Relay image on GitHub:** each release publishes the relay server as
  `ghcr.io/chomusuke-png/opencollab-relay` (amd64 and arm64). The compose files use it, so a
  server needs only them, not the source code. See [the relay guide](docs/RELAY_SERVER.md#docker).
- **Welcome after installing:** a notice explains how to share a project and how to join one,
  with buttons for the [user guide](docs/USER_GUIDE.md) and the tool window. The guide also opens
  from *Tools | OpenCollab | User Guide* and from the tool window.
- **Automatic port forwarding** (off by default): while you host, OpenCollab can ask your router
  (UPnP or NAT-PMP) to forward the listen port, so people on the internet can join without
  setting up the router. The *Router* line in the tool window shows what the router said; the
  mapping is removed when the session ends. If the router cannot do it, hosting goes on and
  manual forwarding or the relay still work. See the
  [user guide](docs/USER_GUIDE.md#inviting-a-remote-participant-different-network).

## [3.8.0] - 2026-11-22

### Added
- **Build files are read-only for guests** by default: build scripts and tool configuration
  (`build.gradle`, `pom.xml`, `package.json`, `Makefile`, `Dockerfile` and the like) decide what
  runs on the host's computer. *Guests can only read build files* turns this off.
- A guest's **request to run** something lists the files guests changed since the host last
  allowed a run, build files first, so the host knows what will run.
- **Passwords, tokens and keys are hidden** from guests in shared run and build output and in
  debugger values (a best effort). *Show guests the values of debugger variables* can turn the
  values off completely.

### Changed
- The IDE's **Notifications** are only for people asking to join or to run something on the
  host. Chat no longer pops up there (unread messages are counted on the Chat tab and in the
  status bar), and other messages appear on the OpenCollab tool window instead.

## [3.7.0] - 2026-11-22

### Added
- **What's new**: after an update the IDE says which version you now have, with a link to its
  changes. *Tools | OpenCollab | What's New* shows this page at any time, and the plugin's page
  in *Settings | Plugins* lists the latest changes.

### Changed
- While addresses are hidden, **ports are hidden too** (•••.•••.•••.54:•••), on screen, in the
  diagnostics and in the port fields on the settings page.

## [3.6.0] - 2026-11-22

### Added
- **Copy Diagnostics** (*Tools | OpenCollab*, or the copy icon in the Activity tab) copies a
  report for bug reports: versions, session and connection settings and the recent activity.
  It never contains the relay secret or invitations, and hides IP addresses, your user folder,
  the project path and the participants' names.
- Issue forms on GitHub for bug reports and ideas, a [beta testing guide](docs/BETA_TESTING.md)
  and how to report security problems privately.

## [3.5.0] - 2026-11-22

### Added
- **Run on host** also lists the temporary configurations IntelliJ makes when you run a file
  or a single test, marked as temporary.

### Changed
- While addresses are hidden, the address fields on the settings page show dots, except while
  you are typing in them.

## [3.4.0] - 2026-11-22

### Added
- The relay server runs in **Docker**, with ready-made compose files: one with a self-signed
  certificate and one behind Caddy with automatic Let's Encrypt certificates. See
  [the relay guide](docs/RELAY_SERVER.md).
- `--keep-certificate` keeps a self-signed relay's certificate, so its fingerprint stays the
  same across restarts.

## [3.3.1] - 2026-11-22

### Fixed
- Leaving a session waits until the guest's copy of the project is saved for the last time.

## [3.3.0] - 2026-11-22

### Added
- **Addresses are hidden on screen** (•••.•••.•••.54) in the tool window, join requests,
  notifications and status bar, for streaming and screen sharing. Turn on *Show IP addresses
  and host names* to see them in full.
- The host's tool window lists the people waiting to join, with **Let in** and **Reject**,
  in case the notification was missed.

### Changed
- A guest who leaves and joins again keeps the role and permissions the host gave them.

### Fixed
- When the host ends the session, guests see "The host ended the session" instead of trying
  to reconnect.

## [3.2.1] - 2026-11-21

### Fixed
- The tool window and settings page fit narrow layouts: buttons wrap instead of being cut off,
  long status lines are shortened with a tooltip.
- Join requests show where the guest comes from (their IP or the relay).
- A muted guest's chat field says that they are muted.

## [3.2.0] - 2026-11-21

### Added
- **Join approval** (on by default): newcomers wait until the host lets them in or rejects them.
- **Invitations expire** (after 24 hours by default) and can be single use; *Revoke all
  invitations* invalidates every one without disconnecting anybody.
- **Per-guest permissions** for run configurations and builds, terminal commands,
  breakpoints and chat, in the participant menu.

## [3.1.3] - 2026-11-17

### Fixed
- A run request for a configuration that changed or was deleted is refused instead of running
  something else.
- Terminal commands are confirmed in a dialog that shows the whole command.
- Large projects transfer more reliably: at most 4 files per guest at once, and files that
  failed are sent again.
- Edits that would make a file larger than the size limit are refused.

## [3.1.2] - 2026-11-17

### Fixed
- A host run appears in the guest's Run window even when the guest has never run anything.

## [3.1.1] - 2026-11-17

### Fixed
- Short programs (such as Hello World) show their output to guests instead of an empty tab
  that keeps running.
- Guests get a notification when the host accepts their run or command request.

## [3.1.0] - 2026-11-17

### Added
- Guests see the host's **test runs** as IntelliJ test trees, and double-clicking a test opens it.
- Guests see where the host's **debugger** stopped, with the frames and variables in the new
  Host tab.
- **Breakpoints** are shared both ways.
- The Host tab shows the host's **Git branch** and changed files.
- Guests format code with the host's **code style**.
- **Command on host**: editors can ask to run a terminal command in the host's project folder;
  the host approves each one.

## [3.0.0] - 2026-11-17

### Changed
- **Typing is live**: the others see each letter as it is typed, instead of in bursts.
- Remote carets and selections stay on their text while other people type.
- The host's runs and builds appear in the guest's own Run and Build windows, with compiler
  errors that open the file. Editors ask to run something with **Run on host**.
- Protocol 6: hosts and guests must both use OpenCollab 3.

## [2.0.0] - 2026-11-17

### Added
- Guests see the output of what the host **runs and builds**, and editors can ask the host to
  run a configuration or build the project; the host allows or denies each request.
- The chat shows who is typing.

### Changed
- The settings page is organized around everyday use: session, sharing, connection.
- Remote carets keep up with typing, and name tags are drawn above the text.
- Protocol 5: hosts and guests must both use OpenCollab 2.

## [1.0.6] - 2026-11-17

### Fixed
- The Chat and Activity tabs no longer disappear after leaving a session and joining again.

## [1.0.5] - 2026-11-17

### Added
- **Chat** for everyone in the session, with unread counts and a notification while the tool
  window is hidden.
- A status bar widget with the session state, the people in it and unread messages.

### Changed
- The tool window is redesigned around the session, with one **Invite** button for editor and
  viewer invitations.
- Following someone keeps showing their file and caret until you click or type.
- Each caret movement is sent at once.

## [1.0.4] - 2026-11-17

### Added
- First release: share a project from IntelliJ IDEA and edit it together, each from their own
  IDE, with live cursors and selections, file synchronisation and editor and viewer roles.
- Connections on the same network, through port forwarding or through a self-hosted relay,
  always encrypted with TLS.
- Large files are streamed; guests can join into a new, empty folder.
- Each participant's ping and the session time; leaving automatically when the host is gone.
- Updates arrive like any other plugin update.
