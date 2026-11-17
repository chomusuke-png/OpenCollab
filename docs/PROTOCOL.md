# Protocol and synchronisation

All session logic lives in `core` and is independent of IntelliJ and of the transport.

## Transport contract

`dev.opencollab.core.transport.Connection` is an ordered, reliable, bidirectional channel of
UTF-8 text messages. Implementations:

| Transport | Module | Used for |
|---|---|---|
| `InMemoryTransport` | core | Tests; the host IDE's own bridge to its session |
| `WsConnection` / `DirectHostTransport` / `WebSocketConnector` | transport | Direct `wss://` (TLS 1.3/1.2, Java-WebSocket) |
| `RelayHostTransport` / `RelayConnector` + `TlsTunnel` | transport | Relay with end-to-end TLS |

A future peer-to-peer transport (e.g. WebRTC data channels via an established library) only
needs to implement `Connection`/`HostTransport`/`Connector`.

## Messages

JSON objects with a `"t"` discriminator (`core/.../protocol/Messages.kt`). Every inbound message
is size-limited (16 Mi chars) and validated by `MessageCodec` before the session sees it: paths
must pass `SafePath`, identifiers are bounded, operations are well-formed, selections ordered,
etc. Invalid input from an authenticated peer yields an `error` reply; before authentication it
closes the connection.

| Group | Messages |
|---|---|
| Handshake | `hello` (protocol version, display name, invitation token, optional resume key) → `welcome` (participant id, role, resume key, participants, sharing rules, file event sequence, session age) or `rejected` |
| Membership | `participantJoined`, `participantLeft`, `roleChanged`, `setRole` (host only), `sessionEnded` |
| Files | `requestManifest` → `manifest`; `requestFiles` → `fileChunk`* + `filesDone`; `fileRequest` → `result`; `fileEvent` (host broadcast, sequenced); `resumeFiles` → replayed `fileEvent`s + `result`, or `filesResyncRequired` |
| Documents | `openDoc` → `docState`; `op` → `ack` (+ `remoteOp` to other subscribers) or `opRejected`; `resumeDoc` → `docCatchUp` or `docState`; `docUnavailable`; `docSaved`; `closeDoc` |
| Presence | `presence` (sender) → `presenceBroadcast` (host stamps the sender id) |
| Chat | `chatSend` (text) → `chat` to everyone, sender included (host assigns id, sender and time); `chatHistory` (last 200) after `welcome` |
| Consoles | host → guests: `consoleStarted`, `consoleOutput` (stdout/stderr/system, ≤ 64 Ki chars per message, project folder replaced by `$PROJECT_DIR$`), `consoleEnded`; the last 5 consoles (256 Ki chars each) are replayed after `welcome`. `runTargets` lists what may be requested; `runRequest` (editors only, 1/s) → the host's user answers → `runResponse` to the requester (refusal codes: `refused`, `viewer`, `unknown-target`, `too-many`, `no-answer`, `host`) |
| Liveness | `ping`/`pong` (either direction; WebSocket pings are also used); `latencies` (host broadcast every 2 s: each participant's round-trip time to the host in ms, measured with host pings) |

## Document synchronisation (host-authoritative OT)

* Operations are retain / insert / delete sequences over UTF-16 code units
  (`TextOperation`, ot.js wire format: positive int = retain, negative int = delete, string =
  insert), with `apply`, `compose`, `transform`, and caret `transformIndex`.
* **Host (`ServerDocument`)**: one authoritative text and revision counter per document. An
  operation carries `baseRevision` and an `opId` unique per author. The host transforms it against
  every history entry after `baseRevision`, applies it, increments the revision, acknowledges the
  author and broadcasts the transformed operation to the other subscribers.
* **Duplicate detection**: `(author, opId)` pairs are remembered (bounded, 10 000); a re-sent
  operation is acknowledged again with its original revision and not re-applied.
* **History and checkpoints**: the last 2 000 operations per document are kept; older ones are
  folded into a checkpoint (`checkpointText` at `checkpointRevision`). Operations based on a
  revision older than the checkpoint are rejected as `stale`.
* **Client (`ClientDocument`)**: the ot.js client state machine — one operation in flight,
  further local edits composed into a buffer; incoming operations are transformed against the
  in-flight and buffered operations before being applied to the editor. The client keeps a
  shadow of the last known server text.
* **Recovery (`resync`)**: when the host sends a full `docState` (stale operation, protocol
  error, unregistered document edited locally, reconnect after history was pruned), the client
  computes the server's change as a diff from its shadow, transforms its unacknowledged local
  changes over it, applies the server change locally and re-sends its rebased changes. The host
  tells the client whether its in-flight operation was already applied, so it is never applied
  twice. If the host refused an edit (`forbidden`), local changes are discarded instead.
* **Unsaved vs on-disk**: documents carry the live editor text (including unsaved changes);
  file synchronisation carries bytes on disk. On a guest, disk writes for a file that is open as
  a shared document are skipped (the document is authoritative); a host save sends `docSaved`
  and guests save their copy.

Convergence is verified by randomized simulation (`SyncEngineTest`: 200 runs, 2-4 clients,
random interleavings and duplicate deliveries), multi-threaded session tests, and the two-IDE
end-to-end test.

## File synchronisation

* **Join**: `requestManifest` returns path, size and SHA-256 of every shared file. The guest
  compares with its mirror, downloads only differing files (streamed from disk in 256 KiB chunks,
  at most 8 MiB unacknowledged per request via `chunkAck`, assembled in `.opencollab/tmp`, SHA-256 verified,
  written atomically), and deletes files that it previously received but that no longer exist on
  the host. Local-only files of the guest are never deleted. The mirror state is stored in
  `.opencollab/mirror.json`.
* **Incremental**: host VFS changes (create, write, rename/move, delete) become sequenced
  `fileEvent`s. Guest changes are `fileRequest`s that the host validates (role, sharing rules,
  guest read-only rules, path safety, size, directory contents) and applies through the VFS before
  broadcasting them. A rename that moves a file out of the shared set is seen by guests as a delete.
* **Reconnect**: `resumeFiles(lastSeq)` replays the events the guest missed (last 5 000 kept);
  otherwise the guest re-runs the manifest comparison.

## Presence

Carets and selections are sent as soon as they change by default (interval 0 ms; with a larger interval the first update goes out immediately,
latest value on the trailing edge — `PresenceThrottler`). The host rate-limits presence per
participant (120/s; beyond that only the latest position is kept and relayed as soon as the limit allows, so the final caret is never lost), stamps the sender id (no spoofing), relays it to everybody else, sends current
presence to newcomers and broadcasts `participantLeft` on disconnect, which clears the carets.

## Reconnect

Guests reconnect with exponential backoff (1 s … 30 s). The plugin gives up after 8 attempts (about two minutes) and leaves the session automatically, as it does when the host ends the session or removes the guest. `hello` carries the resume key; within
30 minutes the host restores the same participant id, role (never escalated by the token) and
document subscriptions. The guest then sends `resumeDoc(path, revision, outstandingOpId)` for each
document; the host replies with the missing history (`docCatchUp`), in which the guest recognises
its own in-flight operation as an acknowledgement, or with a `docState` snapshot.
