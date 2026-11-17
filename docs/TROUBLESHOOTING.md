# Troubleshooting

The **Activity** panel of the OpenCollab tool window shows what the session is doing. More detail
is in the IDE log (*Help | Show Log in Explorer/Finder*), lines containing `OpenCollab`.

| Symptom | Cause and fix |
|---|---|
| *Could not start: Cannot listen on 0.0.0.0:47800* | The port is in use (another IDE hosting?). Change *Port* or use `0` for a free port |
| *Enable direct connections or a relay* | Both are disabled in *Settings \| Tools \| OpenCollab* |
| Guest: *Cannot connect to wss://…: timed out* | The host address is not reachable: firewall, different network, or the advertised address is wrong. Allow inbound TCP on the port in the host firewall, set *Advertised hosts* to a reachable address, or use the relay |
| Guest: *TLS certificate rejected … does not match the invitation* | The invitation belongs to an older session (each session has a new certificate), or something intercepts the connection. Ask the host for a fresh invitation |
| Guest: *Host refused the connection: Invalid or expired invitation* | The session was restarted or the token is wrong. Get a new invitation |
| Join dialog: *This project folder is not empty* | Join into an empty folder opened as a project (IDE files like `.idea` are fine), or into a folder used for a previous OpenCollab session |
| *The host started a new session; join again* | The host ended and restarted hosting. Joining the same folder again only downloads what changed |
| Host: *Relay refused registration: invalid relay secret* | The relay host secret in settings does not match the relay's `--host-secret-file` |
| Host: *Cannot connect to wss://relay…: TLS certificate rejected* | Self-signed relay: copy the fingerprint printed by the relay into *Relay certificate fingerprint*. CA relay: check the host name in the URL matches the certificate |
| Guest via relay: *connection closed (unknown room or host offline)* | The host is not connected to the relay (yet), or the room expired. Ask the host for a new invitation |
| Edits from a guest are reverted immediately | The guest is a viewer, or the file is read-only for guests (run configurations, wrapper scripts, CI files). The host can change the role (right-click > *Make Editor*) |
| *Host refused write …: The host has unsaved changes* | A guest tried to replace a file (outside the editor) that the host is editing. Open the file in the editor instead |
| A file is missing on the guest | It matches an exclude rule (secret, build output, local IDE file) or exceeds the size limit. The host can add an explicit include rule |
| Remote carets are not shown | The other participant has no shared file focused; presence updates are throttled to 75 ms by default |
| *Reconnecting in Ns* | The network dropped. The guest keeps editing locally and merges after reconnecting; if the host's history no longer covers the gap, the document is rebased on a snapshot |
| Gradle: `Could not resolve idea:idea:2026.2.3` | First build needs internet access to `cache-redirector.jetbrains.com` / `www.jetbrains.com` |
| Gradle on Windows: path too long errors | Clone/build in a short path (e.g. `C:\src\OpenCollab`) |
| *Port forwarding needs a fixed listen port* | Set *Port* in *Direct Connections* to the port you forwarded (e.g. 47800) instead of `0` |
| *Could not detect the public address* | The public IP service is unreachable. Enter your public IP or dynamic DNS name in *Port Forwarding \| Public address* |
| Guest: *The host public address … did not answer* | Port forwarding is not working: forward TCP (not only UDP) to the host's current LAN IP, allow the IDE in the host firewall, check the external port, and check for CGNAT (router WAN address in `100.64.0.0/10` or private): then use the relay or a VPN |
| LAN guests get the public address and fail | They try their own network first; if your router lacks NAT loopback the public endpoint simply fails and the LAN endpoint is used |
| Join dialog: current folder not available | It is not empty. Keep *A new folder* selected: it is created, opened in a new window and joined automatically |
