# OpenCollab user guide

## Requirements

* IntelliJ IDEA **2026.2** or newer (build 262+). The free features are enough; the plugin only
  uses the IntelliJ Platform core.
* Every participant installs the plugin in their own IDE. Nothing else of the guest's IDE is
  changed: settings, themes, keymaps and other plugins stay local.
* Network: guests must reach the host's TCP port (LAN, VPN, or port forwarding with TLS), **or**
  everybody must reach a self-hosted OpenCollab relay (see [RELAY_SERVER.md](RELAY_SERVER.md)).

## Install

1. Build the ZIP (`./gradlew :plugin:buildPlugin`) or obtain `OpenCollab-<version>.zip`.
2. *Settings | Plugins | ⚙ (gear) | Install Plugin from Disk…* and pick the ZIP.
3. Restart the IDE if asked. An **OpenCollab** tool window appears on the right side bar and an
   *OpenCollab* submenu appears under *Tools*.

## Language / Idioma

The plugin UI is available in **English** and **Spanish**. It follows the IDE language: install
JetBrains' Spanish language pack (or pick *Español* in *Settings | Appearance & Behavior |
System Settings | Language and Region*) and restart the IDE. Any other language falls back to
English. Participants can use different languages in the same session.

La interfaz del plugin está en **inglés** y **español** y sigue el idioma del IDE.

## Configure (Settings | Tools | OpenCollab)

| Setting | Meaning |
|---|---|
| Display name | Shown to the other participants next to your cursor |
| Accept direct connections | The host listens for TLS WebSocket connections |
| Listen address / Port | Default `0.0.0.0:47800`. Use `127.0.0.1` to test on one machine. Port `0` picks a free port |
| Advertised hosts | Addresses written into invitations (e.g. a VPN IP or DNS name). Default: all local IPv4 addresses |
| Relay URL / fingerprint / host secret | See [RELAY_SERVER.md](RELAY_SERVER.md). The secret is stored in the IDE password safe |
| Default invite role | Role suggested for new invitations |
| Additional excludes / Explicit includes / Read-only for guests | Sharing rules, see below |
| Max file size | Files above this are never shared (default 10 MB, up to 2048 MB). Files larger than 8 MB are streamed to guests in chunks with flow control; guests can create/upload files up to 8 MB; text files up to 8 MB are edited live |
| Cursor update interval | Minimum gap between cursor updates (default 0 ms: every change is sent at once) |

## Start a session (host)

1. Open the project you want to share.
2. In the **OpenCollab** tool window click **Share this project** (or *Tools | OpenCollab | Start
   Hosting Session*). *Connection details* under the buttons shows the endpoints and the TLS
   certificate fingerprint of this session.
3. Click **Invite ▾**, choose the editor (can edit) or viewer (read only) invitation, and send
   the copied text (`oc1:…`) to the
   participant over a private channel (direct message, password manager share…). **An invitation
   is a secret**: anybody holding it can join with that role while the session runs.

Each session uses a new random certificate and new invitation tokens; invitations stop working
when the session ends.

## Join a session (guest)

1. In any project window, open the **OpenCollab** tool window and click **Join with an invitation**.
2. Paste the invitation and choose where the shared project goes:
   * **This project folder** — only offered if it is empty (IDE files like `.idea` are fine) or a
     folder used for an earlier OpenCollab session;
   * **A new folder** (default when the current folder is not empty) — suggested as
     `~/OpenCollab/<project>`. It is created, opened in a new window, and the join completes there
     automatically.
3. The shared project files are downloaded, and the IDE indexes them normally. You can navigate,
   search, and open files in your own IDE.

Re-using a folder from an earlier session only downloads what changed, and files you created
yourself are never deleted. The plugin never downloads into a non-empty folder that is not an
OpenCollab mirror, so it can never overwrite your own data.

### Inviting a remote participant (different network)

* **Port forwarding (router):**
  1. Pick a fixed *Port* in *Direct Connections* (e.g. `47800`; port forwarding cannot use `0`) and
     keep the listen address `0.0.0.0`.
  2. On your router, forward **TCP** of that port (or another external port) to this computer's
     LAN IP, and allow the IDE through the firewall (Windows: *Private* networks).
  3. In *Settings | Tools | OpenCollab | Port Forwarding* enable *The listen port is forwarded on
     my router*. Leave *Public address* empty to detect it automatically when the session starts
     (the plugin asks the *Public IP service*, `https://api.ipify.org` by default — only then), or
     enter a dynamic DNS name. Set *External port* if the router uses a different port.
  4. Start hosting and send a **new** invitation: it now also contains `wss://<public address>:<port>`.
     Guests try their own network first, then the public address, so the same invitation works
     for LAN and internet guests. The connection is TLS with the certificate pinned by the
     invitation, so a public endpoint is as safe as a LAN one.

  Port forwarding does not work behind carrier-grade NAT (CGNAT; your router's WAN address is
  `100.64.x.x`–`100.127.x.x` or another private address). Use the relay or a VPN then.
* **VPN:** connect both computers with a VPN (Tailscale, ZeroTier, WireGuard…) and set *Advertised
  hosts* to the host's VPN address.
* **Relay (recommended for the internet):** deploy the relay ([RELAY_SERVER.md](RELAY_SERVER.md)),
  then on the host enable *Offer the session through a self-hosted relay*, enter the relay URL,
  its certificate fingerprint if self-signed, and the relay host secret. Invitations then contain
  both direct endpoints and the relay room; guests try direct first and fall back to the relay.
  Guests need no relay secret.

## Working together

* **Editing:** open any shared file and type. Concurrent edits are merged by the host; everybody
  converges to the same text. Unsaved changes are shared live; saving on the host saves the
  document for all participants.
* **Cursors and selections:** other participants' carets appear in their color, their
  selections as a translucent highlight. The name tag above a caret shows for three seconds after
  that person moves or types, then fades so it does not cover your code.
* **Follow:** hover a participant and click the eye icon (or double-click them, or right-click >
  *Follow*). Your editor then keeps showing their file and caret; "Following …" appears above the
  list. Clicking or typing in an editor, *Stop following*, or that person leaving ends it.
* **Participant list:** each guest's connection to the host shows as signal bars (hover for the
  ping in ms). The host's ⋯ icon on a guest has *Make Editor*, *Make Viewer* and *Remove from
  Session*.
* **Shared console:** while hosting, everything the host runs from the IDE (run configurations,
  tests, Gradle tasks) and every build (compiler and Gradle output, errors with their location)
  is shown live to guests in the **Console** tab. Paths in the project become links that open the
  file in the guest's copy, and the host's own folder is never shown (it appears as
  `$PROJECT_DIR$` before being mapped). The host can turn this off in *Settings | Tools |
  OpenCollab | Run and Build*.
* **Run on host:** an editor clicks **Run on host ▶** in the Console tab and picks one of the
  host's run configurations or *Build project*. The host gets a notification — *Allow*, *Allow
  for this session*, or *Deny* — and nothing runs without that answer; unanswered requests expire
  after two minutes. Everything runs on the host's machine, so the project stays in one place
  and in sync. Viewers can watch but not ask.
* **Status bar:** while in a session the IDE status bar shows the status (green connected, amber
  connecting, red disconnected), the number of people, the session time and unread chat
  messages; click it to open the tool window.
* **Chat:** the **Chat** tab at the bottom of the tool window talks to everyone in the session,
  viewers included; press Enter to send. The host stamps who sent each message, keeps the last
  200 and shows them to anyone who joins later. While someone writes, the others see
  “Name is typing…” above the input. Unread messages are counted on the tab, and a
  notification pops up while the tool window is hidden. The **Activity** tab next to it has the
  session log. Participants' round-trip time to the host (ms) is shown next to their role, and
  the session time under the status.
* **Files:** creating, renaming, moving and deleting shared files in the Project view is
  propagated. Guests' changes are validated by the host first.
* **Roles** (host only, ⋯ icon or right-click on a participant): *Make Editor*, *Make Viewer*, *Remove from
  Session*. Viewers' editors are read-only, and the host rejects any edit or file change from a
  viewer even if a modified client sends one.
* **Leaving:** guests click **Leave**; the host clicks **End Session**, which disconnects
  everybody. Closing the project also ends its session.
* **Network interruptions:** guests reconnect automatically (with backoff) and recover their
  documents from the host's operation history; edits typed while offline are kept and merged.
  If the host ends the session, removes the guest, or stays unreachable for about two minutes,
  the guest leaves on its own; the status line says why.

## Sharing rules

By default the host shares every file of the project **except**:

* VCS internals: `.git`, `.hg`, `.svn` (never shareable), and OpenCollab's `.opencollab` folder.
* Local IDE state: `.idea/workspace.xml`, `.idea/shelf`, data sources, HTTP client files, …
* Build outputs and caches: `build`, `out`, `target`, `.gradle`, `node_modules`, `.venv`, …
* Secrets: `.env`, `.env.*`, `local.properties`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `id_rsa*`,
  `.npmrc`, `.netrc`, `.aws`, `.ssh`, `credentials*`, `secrets*`, `*.tfstate`, `*.tfvars`,
  `google-services.json`, …

Guests can read but never modify files that would make the host run code without review:
`.idea` (run configurations), `*.iml`, `.run`, `*.run.xml`, `gradlew`, `gradle/wrapper`, `mvnw`,
`.github/workflows`, `.gitlab-ci.yml`, `.gitattributes`, `.envrc`, …

Change the rules in *Settings | Tools | OpenCollab* (one glob per line):

* `*` matches inside one path segment, a double star matches any number of segments, a pattern
  without `/` matches a name at any depth (`*.log`), and `dir/sub` matches that folder and all
  of its content.
* **Explicit includes** override default excludes (for example `.env.example`), except the VCS
  folders.

Rules are applied when the session starts.

## Limitations

* Undo: remote edits are applied as undo-transparent actions; *Undo* in your editor may also
  revert a nearby remote change.
* Code intelligence on guests depends on their local SDK/JDK configuration: the project files
  (including shared `.idea` files) arrive, but SDKs, Gradle/Maven sync and build output are
  local to each IDE.
* Binary files are synchronised as files only (no live editing); files above the size limit are
  not shared.
* Terminals, debuggers, run configurations and servers are not shared.
* A guest's own local-only files (created outside shared paths) stay local.
* The relay forwards end-to-end encrypted traffic but learns metadata (IP addresses, room ids,
  traffic volume and timing).
