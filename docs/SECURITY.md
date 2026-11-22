# Security model

## Trust assumptions

* The **host** is trusted by guests to serve the project; guests are trusted by the host only as
  far as their role allows.
* An **editor** can change shared source files. Source code, build scripts (`build.gradle.kts`,
  `pom.xml`, `package.json`) and tests are code that the host may later build or run. Only invite
  people you would let push to your branch, and review changes before running them.
* The **network** and the **relay** are untrusted for confidentiality and integrity.

## What protects a session

| Threat | Mitigation |
|---|---|
| Eavesdropping / tampering on the network | TLS 1.3/1.2 everywhere. Plain `ws://` is rejected by clients; the relay refuses plain mode unless bound to loopback behind a proxy |
| Impersonating the host / MITM | Per-session self-signed certificate; guests pin its SHA-256 fingerprint from the invitation (`PinnedTrustManager`) |
| Reading traffic on the relay | End-to-end TLS tunnel inside the relay connection (`TlsTunnel`, JSSE). The relay forwards records it cannot decrypt |
| Strangers joining | 192-bit random invitation tokens, compared in constant time; a new token per copied invitation, with expiry (24 h by default) and optional single use; *Revoke all invitations*; the host's approval of each newcomer (default on; at most 5 waiting, refused after 2 min); handshake timeout; participant cap. A participant resuming within 30 min keeps its place without a new approval |
| Strangers using your relay | Relay host secret (constant-time check), room ids are 128-bit random, room and peer limits, per-peer rate limit |
| Privilege escalation | Roles and permissions live on the host. Every operation (`op`, `fileRequest`, `setRole`, `setPermissions`, `docSaved`, `runRequest`, `commandRequest`, `breakpointRequest`, `chatSend`) is authorised in `HostSession`, never in the UI only, and run requests are checked again when the host answers them. A resumed participant keeps the role and permissions the host assigned, whatever token it presents. Viewers can never be given more than the chat. Nobody can be made host |
| Path traversal / escaping the project | `SafePath` rejects absolute paths, drive letters, `..`, `.`, empty segments, backslashes, `:` (NTFS streams), control characters, Windows device names; `resolveInside` re-checks after normalisation and refuses symbolic links or real paths outside the root, on both host and guest |
| Leaking secrets | Default excludes (env files, keys, keystores, cloud credentials, Terraform state, local IDE files); `.git` can never be shared; excluded files are neither listed, read, written, nor broadcast; a rename into an excluded name becomes a delete for guests |
| Guests planting code the host executes unknowingly | Guests cannot modify `.git` (hooks), IDE run configurations (`.idea`, `.run`, `*.run.xml`), wrapper scripts and CI workflows; directories containing unshared files cannot be deleted or moved by guests |
| Guests running code on the host | Guests can only *ask*: to run one of the host's existing run configurations or a project build (the host's user approves each request, or one participant for the session), or to run a terminal command in the project folder (the exact command is shown and must be approved every time; there is no standing permission for commands). Viewers are refused by the host, requests are rate limited and expire after two minutes, and a request is never granted after it expired or its sender left. Commands run in a Run tab the host can stop. Command requests can be turned off |
| Guests controlling the host's tools | Guests see the debugger, breakpoints and Git status but cannot step, resume, evaluate, commit or change branches. Editors can add and remove line breakpoints in shared files only (a breakpoint can pause the host's program, nothing more) |
| Overwriting the host's unsaved work | A guest's file write/delete is refused while the host has unsaved changes in that file; shared documents are only changed through OT |
| Overwriting a guest's data | Joining requires an empty folder or an existing OpenCollab mirror; resync only deletes files that came from the host |
| Resource exhaustion | Frame size limits, file size limits (default 10 MB, configurable up to 2048 MB; large files are streamed from disk with an 8 MB acknowledgement window and written to a temporary file on the guest, never held in memory), 8 MB limit for guest uploads, message rate limit per peer (2 000/s), presence limit (120/s), chat limit (5 messages/s, 2 000 characters, no control characters; the host stamps the sender, and names and text are shown as plain text and escaped in notifications), bounded histories and logs |
| Credentials | The plugin never reads or sends IDE credentials, tokens or settings. The relay host secret is stored in the IDE password safe and only sent to the relay |

Cryptography is provided by the JDK (JSSE TLS, SecureRandom, SHA-256) and BouncyCastle (only to
generate the self-signed X.509 certificate). No custom cryptography or NAT traversal is
implemented.

## What guests see of the host's IDE

With *Show guests the debugger, breakpoints, Git branch and changes, and code style* and console
sharing on (both default), guests also receive:

* **Console output** of everything the host runs and builds, and test results with stack traces.
* **Debugger state** while paused: frame names, file and line, and the names, types and values
  of the selected frame's variables. **Values may contain secrets** (tokens, passwords, personal
  data in memory): turn this setting off before debugging such code in a session.
* **Git**: the branch name and the paths of changed and unversioned shared files (not their
  diffs). Excluded files are never listed.
* **Code style** settings.

Not shared, by design: the host's own Terminal tool window, a shell for guests, debugger
control, version control actions, servers and ports. A guest must never obtain a shell or
arbitrary filesystem access on the host; single commands run only after the host's approval.

## Known limitations

* **Invitations are bearer secrets.** Anyone who obtains one can ask to join until it expires, is
  used up or is revoked. With *Ask me before anyone joins* (default) the host still has to let
  each person in; without it, the invitation alone is enough. The prompt shows the name a person
  chose and the address they connect from: names are not verified.
* **Restrictions follow people only loosely.** A guest who leaves and rejoins keeps the role and
  permissions the host gave them, recognised by their IDE installation id or their name. Both can
  be changed by a determined person, so keep join approval on and remove or refuse them again.
* **Addresses on screen** are partly hidden by default (tool window, join requests,
  notifications) so streams do not reveal them, and the address fields on the settings page show
  dots except while you type in them; the IDE log file keeps them in full.
* **Editors are powerful.** See the trust assumptions above.
* **Relay metadata.** The relay sees IP addresses, room ids, and the timing and size of traffic.
* **Self-signed relays** require pinning the fingerprint; a relay started with `--self-signed`
  changes its fingerprint on every start.
* **Direct mode exposure.** The host listens on all interfaces by default (TLS + token required).
  Use `127.0.0.1`, a VPN address, or the relay to restrict exposure.
* **No end-to-end identity of participants.** Display names are self-declared.
* **Shared `.idea` project files** (read-only for guests) are sent to guests; remove them with an
  exclude rule if they contain anything sensitive.
