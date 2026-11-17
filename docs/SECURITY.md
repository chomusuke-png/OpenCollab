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
| Strangers joining | 192-bit random invitation tokens, compared in constant time; separate editor and viewer tokens; new tokens per session; handshake timeout; participant cap |
| Strangers using your relay | Relay host secret (constant-time check), room ids are 128-bit random, room and peer limits, per-peer rate limit |
| Privilege escalation | Roles live on the host. Every operation (`op`, `fileRequest`, `setRole`, `docSaved`) is authorised in `HostSession`, never in the UI only. A resumed participant keeps the role the host assigned, whatever token it presents. Nobody can be made host |
| Path traversal / escaping the project | `SafePath` rejects absolute paths, drive letters, `..`, `.`, empty segments, backslashes, `:` (NTFS streams), control characters, Windows device names; `resolveInside` re-checks after normalisation and refuses symbolic links or real paths outside the root, on both host and guest |
| Leaking secrets | Default excludes (env files, keys, keystores, cloud credentials, Terraform state, local IDE files); `.git` can never be shared; excluded files are neither listed, read, written, nor broadcast; a rename into an excluded name becomes a delete for guests |
| Guests planting code the host executes unknowingly | Guests cannot modify `.git` (hooks), IDE run configurations (`.idea`, `.run`, `*.run.xml`), wrapper scripts and CI workflows; directories containing unshared files cannot be deleted or moved by guests |
| Overwriting the host's unsaved work | A guest's file write/delete is refused while the host has unsaved changes in that file; shared documents are only changed through OT |
| Overwriting a guest's data | Joining requires an empty folder or an existing OpenCollab mirror; resync only deletes files that came from the host |
| Resource exhaustion | Frame size limits, file size limits (default 10 MB, configurable up to 2048 MB; large files are streamed from disk with an 8 MB acknowledgement window and written to a temporary file on the guest, never held in memory), 8 MB limit for guest uploads, message rate limit per peer (2 000/s), presence limit (120/s), chat limit (5 messages/s, 2 000 characters, no control characters; the host stamps the sender, and names and text are shown as plain text and escaped in notifications), bounded histories and logs |
| Credentials | The plugin never reads or sends IDE credentials, tokens or settings. The relay host secret is stored in the IDE password safe and only sent to the relay |

Cryptography is provided by the JDK (JSSE TLS, SecureRandom, SHA-256) and BouncyCastle (only to
generate the self-signed X.509 certificate). No custom cryptography or NAT traversal is
implemented.

## Not shared — by design

There is no terminal, debugger, run configuration, process or port sharing. These require an
authorisation model and sandboxing that do not exist yet; a guest must never obtain a shell or
arbitrary filesystem access on the host.

## Known limitations

* **Invitations are bearer secrets.** Anyone who obtains one can join while the session runs.
  Send them privately; end the session to revoke them. Per-person invitations and an approval
  prompt are not implemented yet.
* **Editors are powerful.** See the trust assumptions above.
* **Relay metadata.** The relay sees IP addresses, room ids, and the timing and size of traffic.
* **Self-signed relays** require pinning the fingerprint; a relay started with `--self-signed`
  changes its fingerprint on every start.
* **Direct mode exposure.** The host listens on all interfaces by default (TLS + token required).
  Use `127.0.0.1`, a VPN address, or the relay to restrict exposure.
* **No end-to-end identity of participants.** Display names are self-declared.
* **Shared `.idea` project files** (read-only for guests) are sent to guests; remove them with an
  exclude rule if they contain anything sensitive.
