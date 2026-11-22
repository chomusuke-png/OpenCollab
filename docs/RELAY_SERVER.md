# OpenCollab relay server

The relay lets participants on different networks meet without opening ports on the host. The
host keeps one outbound connection to the relay; each guest connects to the relay; the relay
forwards frames between them.

```
guest IDE ──wss──▶ relay ◀──wss── host IDE
     └──────── TLS 1.3 tunnel (pinned host certificate) ────────┘
```

Two layers of TLS protect traffic:

1. **Outer TLS** between each IDE and the relay (relay certificate: CA-issued, or self-signed and
   pinned by fingerprint).
2. **Inner, end-to-end TLS** between guest and host, using the host's per-session certificate
   whose fingerprint is in the invitation. The relay forwards these TLS records without being
   able to read or modify session content.

The relay requires a **host secret** before it opens a room, so strangers cannot use your relay.
Guests do not need the secret: they need the room id (in the invitation) and are then
authenticated by the host itself with the invitation token.

## Build

```bash
./gradlew :relay-server:shadowJar
# -> relay-server/build/libs/opencollab-relay-X.Y.Z-all.jar
```

Requires Java 21+ to run. `X.Y.Z` is the version being built (the same as the plugin's; see
the README); replace it in the commands below.

## Run

Create a long random secret (at least 16 characters) and keep it out of shell history:

```bash
openssl rand -base64 32 > relay-secret.txt
```

### Option A — certificate from a CA (recommended for internet use)

Convert your certificate (e.g. from Let's Encrypt) to PKCS#12:

```bash
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out relay.p12 -name opencollab
```

```bash
export OPENCOLLAB_RELAY_KEYSTORE_PASSWORD='the export password'
java -jar opencollab-relay-X.Y.Z-all.jar --keystore relay.p12 --host-secret-file relay-secret.txt --port 8443
```

IDE settings: *Relay URL* `wss://relay.example.com:8443`, *Relay certificate fingerprint*
empty (verified against the JVM's CA list and host name).

### Option B — self-signed (small teams)

```bash
java -jar opencollab-relay-X.Y.Z-all.jar --self-signed --host-secret-file relay-secret.txt --port 8443
```

The relay prints `Self-signed relay certificate fingerprint (SHA-256): AB:CD:…`. Enter it in
*Relay certificate fingerprint* on the host. The fingerprint is copied into invitations, so guests
pin it automatically. A new fingerprint is generated at every start unless you add
`--keep-certificate relay.p12`: the certificate is then saved in that file and reused, so the
fingerprint stays the same (a new one is made when it is about to expire, after about two years).

### Option C — behind a TLS-terminating reverse proxy

```bash
java -jar opencollab-relay-X.Y.Z-all.jar --plain-behind-proxy --bind 127.0.0.1 --port 8080 --host-secret-file relay-secret.txt
```

Plain mode is refused unless bound to the loopback interface. Example Caddy configuration:

```
relay.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

(nginx: `proxy_pass http://127.0.0.1:8080; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";`)

### Options

| Option | Default | |
|---|---|---|
| `--bind` | `0.0.0.0` | Listen address |
| `--port` | `8443` | Listen port |
| `--keystore <p12>` | | Certificate + key; password in `OPENCOLLAB_RELAY_KEYSTORE_PASSWORD` |
| `--self-signed` | | Generate a certificate at startup and print its fingerprint |
| `--keep-certificate <file>` | | With `--self-signed`: save the certificate there and reuse it |
| `--plain-behind-proxy` | | No TLS; loopback only |
| `--host-secret-file <file>` | | Secret required from hosts (or env `OPENCOLLAB_RELAY_SECRET`) |
| `--allow-anonymous-hosts` | off | Allow rooms without a secret (not for the internet) |
| `--max-rooms` | 200 | Concurrent rooms |
| `--max-peers` | 16 | Guests per room |

Endpoints: `/host` (hosts), `/join/<room>` (guests), `/health` (closes immediately with code 1000).

### systemd example

```ini
[Unit]
Description=OpenCollab relay
After=network-online.target

[Service]
User=opencollab
Environment=OPENCOLLAB_RELAY_KEYSTORE_PASSWORD=change-me
ExecStart=/usr/bin/java -jar /opt/opencollab/opencollab-relay-X.Y.Z-all.jar --keystore /etc/opencollab/relay.p12 --host-secret-file /etc/opencollab/relay-secret.txt
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

## Docker

The image builds the relay from source and runs it as an unprivileged user. Everything is in
`relay-server/`: the `Dockerfile` and, in `relay-server/docker/`, two compose files. Run the
commands below from that folder on the server, after cloning the repository.

First create the host secret (hosts enter it in *Settings | Tools | OpenCollab*):

```bash
cd relay-server/docker
openssl rand -base64 32 > relay-secret.txt
```

The container reads it as a user with id 10001, so keep the file readable (the default
permissions are fine; do not `chmod 600` it unless you also `chown 10001` it). It is in
`.gitignore`.

### With a domain: automatic HTTPS (recommended)

`compose.caddy.yml` puts [Caddy](https://caddyserver.com) in front of the relay. Caddy gets a
free Let's Encrypt certificate for your domain and renews it. Point the domain's DNS to the
server, open ports 80 and 443, then:

```bash
RELAY_DOMAIN=relay.example.com docker compose -f compose.caddy.yml up -d --build
```

In the plugin: *Relay URL* `wss://relay.example.com`, *Relay certificate fingerprint* empty.

The relay only listens on the container's loopback interface; Caddy shares the container's
network and is the only thing reachable from outside. The relay's logs then show Caddy's local
address instead of each person's address.

### Without a domain: self-signed

`compose.yml` runs the relay alone with a self-signed certificate kept in a volume, so the
fingerprint does not change when the container restarts or is rebuilt:

```bash
docker compose up -d --build
docker compose logs relay   # Self-signed relay certificate fingerprint (SHA-256): AB:CD:...
```

In the plugin: *Relay URL* `wss://<server address>:8443`, *Relay certificate fingerprint* the
printed value. Set `RELAY_PORT` to publish another port. Deleting the `relay-data` volume makes a
new certificate, and hosts must then enter the new fingerprint.

### Updating and other settings

```bash
git pull && docker compose up -d --build     # add -f compose.caddy.yml if you use it
```

Set `OPENCOLLAB_VERSION` to label the image with a version (it defaults to `dev`). To pass other
options (`--max-rooms`, `--max-peers`, your own `--keystore`), override `command:` in the compose
file; the image's default command is
`--self-signed --keep-certificate /data/relay.p12 --host-secret-file /run/secrets/relay_secret`.

Without compose:

```bash
docker build -f relay-server/Dockerfile -t opencollab-relay .   # from the repository root
docker run -d --name opencollab-relay --restart unless-stopped -p 8443:8443 -v relay-data:/data   -v "$PWD/relay-server/docker/relay-secret.txt:/run/secrets/relay_secret:ro"   opencollab-relay
```

(Mount the secret as a file rather than passing `OPENCOLLAB_RELAY_SECRET` with `-e`, which
`docker inspect` shows to anyone who can use Docker on the server.)

## Behaviour and limits

* Rooms are random 128-bit ids. If the host's connection drops, the room is kept for 5 minutes and
  the host re-registers it with a room key, so invitations stay valid; guests reconnect.
* Per-guest rate limit (2000 frames/s), frame size limit, room and peer limits.
* Logs contain room id prefixes, peer ids and remote addresses — never session content.
* The relay is a single process with in-memory state; restart it and hosts must start a new
  session (or wait for their automatic reconnect, which creates a new room if the old one is gone).
