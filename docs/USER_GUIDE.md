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
| Max file size | Larger files are never transferred (default 10 MB) |
| Cursor update interval | Presence throttling (default 75 ms) |

## Start a session (host)

1. Open the project you want to share.
2. In the **OpenCollab** tool window click **Start Hosting** (or *Tools | OpenCollab | Start
   Hosting Session*). The panel shows the endpoints and the TLS certificate fingerprint of this
   session.
3. Click **Copy Editor Invite** or **Copy Viewer Invite** and send the text (`oc1:…`) to the
   participant over a private channel (direct message, password manager share…). **An invitation
   is a secret**: anybody holding it can join with that role while the session runs.

Each session uses a new random certificate and new invitation tokens; invitations stop working
when the session ends.

## Join a session (guest)

1. Create or pick an **empty folder** and open it as a project (*File | Open…*). A folder that you
   used for an earlier OpenCollab session is also accepted — only files that changed are downloaded,
   and files you created yourself are never deleted.
2. In the **OpenCollab** tool window click **Join…**, paste the invitation and press **Join**.
3. The shared project files are downloaded into the folder, and the IDE indexes them normally.
   You can navigate, search, and open files in your own IDE.

The plugin refuses to join into a non-empty folder so it can never overwrite your own data.

### Inviting a remote participant (different network)

* **VPN / port forwarding:** set *Advertised hosts* to an address the guest can reach (VPN IP or
  a DNS name pointing to your router) and forward the TCP port. The connection is TLS with the
  certificate pinned by the invitation.
* **Relay (recommended for the internet):** deploy the relay ([RELAY_SERVER.md](RELAY_SERVER.md)),
  then on the host enable *Offer the session through a self-hosted relay*, enter the relay URL,
  its certificate fingerprint if self-signed, and the relay host secret. Invitations then contain
  both direct endpoints and the relay room; guests try direct first and fall back to the relay.
  Guests need no relay secret.

## Working together

* **Editing:** open any shared file and type. Concurrent edits are merged by the host; everybody
  converges to the same text. Unsaved changes are shared live; saving on the host saves the
  document for all participants.
* **Cursors and selections:** other participants' carets appear with a colored name tag, their
  selections as a translucent highlight.
* **Follow:** double-click a participant in the tool window (or right-click > *Follow*) to open
  the file they are looking at, at their caret.
* **Files:** creating, renaming, moving and deleting shared files in the Project view is
  propagated. Guests' changes are validated by the host first.
* **Roles** (host only, right-click a participant): *Make Editor*, *Make Viewer*, *Remove from
  Session*. Viewers' editors are read-only, and the host rejects any edit or file change from a
  viewer even if a modified client sends one.
* **Leaving:** guests click **Leave**; the host clicks **End Session**, which disconnects
  everybody. Closing the project also ends its session.
* **Network interruptions:** guests reconnect automatically (with backoff) and recover their
  documents from the host's operation history; edits typed while offline are kept and merged.

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
