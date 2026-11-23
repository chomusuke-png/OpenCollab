#!/bin/sh
# Installs or updates an OpenCollab relay with Docker, asking a few questions.
# Works on Linux, on Windows inside WSL, and on macOS.
#
#   curl -fsSL https://raw.githubusercontent.com/chomusuke-png/OpenCollab/main/relay-server/install.sh | sh
#
# Without questions (defaults or the options given):
#   curl -fsSL .../install.sh | sh -s -- --yes --domain relay.example.com
#
# Options:
#   --dir <folder>      Where to install (default: ~/opencollab-relay)
#   --domain <name>     A domain whose DNS points to this server: free Let's Encrypt certificate
#                       through Caddy, ports 80 and 443. Without one: a self-signed certificate.
#   --port <port>       Port for the self-signed relay (default 8443)
#   --version <tag>     Image version: latest (default), a major version such as 3, or X.Y.Z
#   --yes               Ask nothing; use the defaults and the options given
#
# Running it again in the same folder updates the relay and keeps its settings, secret and
# certificate.
set -eu

SOURCE="${OPENCOLLAB_SOURCE:-https://raw.githubusercontent.com/chomusuke-png/OpenCollab/main/relay-server/docker}"
DIR="${HOME}/opencollab-relay"
DOMAIN=""
PORT=8443
VERSION=latest
YES=0
DOMAIN_SET=0
PORT_SET=0

say() { printf '%s\n' "$*"; }
die() { printf '\nError: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --dir) [ $# -ge 2 ] || die "--dir needs a folder"; DIR=$2; shift 2 ;;
        --domain) [ $# -ge 2 ] || die "--domain needs a name"; DOMAIN=$2; DOMAIN_SET=1; shift 2 ;;
        --port) [ $# -ge 2 ] || die "--port needs a number"; PORT=$2; PORT_SET=1; shift 2 ;;
        --version) [ $# -ge 2 ] || die "--version needs a tag"; VERSION=$2; shift 2 ;;
        --yes|-y) YES=1; shift ;;
        -h|--help)
            say "Installs or updates an OpenCollab relay with Docker."
            say "Options: --dir <folder>  --domain <name>  --port <port>  --version <tag>  --yes"
            say "Details: https://github.com/chomusuke-png/OpenCollab/blob/main/docs/RELAY_SERVER.md"
            exit 0 ;;
        *) die "Unknown option: $1 (see --help)" ;;
    esac
done

# Questions are read from the terminal, also when this script itself comes from a pipe.
interactive() { [ "$YES" = 0 ] && (: </dev/tty) 2>/dev/null; }
ask() { # ask VARIABLE "question" default
    if ! interactive; then eval "$1=\$3"; return; fi
    printf '%s [%s]: ' "$2" "$3" >/dev/tty
    IFS= read -r answer </dev/tty || answer=""
    [ -n "$answer" ] || answer=$3
    eval "$1=\$answer"
}
yes_no() { # yes_no "question" default(y/n)
    ask reply "$1 (y/n)" "$2"
    case "$reply" in [Yy]*|[Ss]*) return 0 ;; *) return 1 ;; esac
}

valid_domain() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$'; }
valid_port() { printf '%s' "$1" | grep -Eq '^[0-9]{1,5}$' && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
valid_version() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*$'; }

say "OpenCollab relay installer"
say ""

# ---- Docker ---------------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "Docker is not installed.
  Linux: https://docs.docker.com/engine/install/
  Windows: install Docker Desktop and turn on its WSL integration, or install Docker inside WSL.
  macOS: install Docker Desktop."
if ! docker info >/dev/null 2>&1; then
    die "Docker is installed but not running, or this user may not use it.
  Docker Desktop (Windows, macOS): open it and wait until it says it is running.
  Linux: sudo systemctl start docker, and add yourself to the docker group
  (sudo usermod -aG docker \$USER, then log in again) or run this script with sudo."
fi
docker compose version >/dev/null 2>&1 || die "The 'docker compose' command is missing (Docker Compose v2).
  Linux: install the docker-compose-plugin package. Docker Desktop includes it."

# ---- Settings -------------------------------------------------------------------------------
case "$DIR" in /*) ;; *) DIR="$(pwd)/$DIR" ;; esac
UPDATE=0
if [ -f "$DIR/.env" ] && [ -f "$DIR/relay-secret.txt" ]; then
    say "A relay is already installed in $DIR."
    yes_no "Update it to the newest image, keeping its settings?" y || { say "Nothing changed."; exit 0; }
    UPDATE=1
fi

if [ "$UPDATE" = 0 ]; then
    ask DIR "Install folder" "$DIR"
    case "$DIR" in /*) ;; *) DIR="$(pwd)/$DIR" ;; esac
    if [ "$DOMAIN_SET" = 0 ] && interactive; then
        say ""
        say "With a domain whose DNS points to this server, the relay gets a free certificate from"
        say "Let's Encrypt (through Caddy) and nobody needs to enter a fingerprint. That needs ports 80"
        say "and 443. Without a domain, the relay makes its own certificate, and hosts enter its"
        say "fingerprint once."
        ask DOMAIN "Domain (empty for none)" ""
    fi
    if [ -n "$DOMAIN" ]; then
        valid_domain "$DOMAIN" || die "Not a domain name: $DOMAIN"
    else
        [ "$PORT_SET" = 1 ] || ask PORT "Port" "$PORT"
        valid_port "$PORT" || die "Not a port: $PORT"
    fi
    valid_version "$VERSION" || die "Not a version: $VERSION"
fi

# ---- Files ----------------------------------------------------------------------------------
fetch() { # fetch NAME: from the repository (or OPENCOLLAB_SOURCE, a folder or URL) into $DIR
    case "$SOURCE" in
        http://*|https://*)
            if command -v curl >/dev/null 2>&1; then curl -fsSL "$SOURCE/$1" -o "$DIR/$1.new"
            elif command -v wget >/dev/null 2>&1; then wget -q "$SOURCE/$1" -O "$DIR/$1.new"
            else die "Neither curl nor wget is installed"; fi ;;
        *) cp "$SOURCE/$1" "$DIR/$1.new" ;;
    esac || die "Could not download $1 from $SOURCE"
    mv "$DIR/$1.new" "$DIR/$1"
}

umask 077
mkdir -p "$DIR" || die "Cannot create $DIR"
say ""
say "Downloading the compose files to $DIR..."
fetch compose.yml
fetch compose.caddy.yml
fetch Caddyfile
chmod 644 "$DIR/compose.yml" "$DIR/compose.caddy.yml" "$DIR/Caddyfile"

if [ "$UPDATE" = 0 ]; then
    if [ ! -s "$DIR/relay-secret.txt" ]; then
        if command -v openssl >/dev/null 2>&1; then secret=$(openssl rand -base64 32)
        else secret=$(head -c 32 /dev/urandom | base64 | tr -d '\n'); fi
        printf '%s\n' "$secret" > "$DIR/relay-secret.txt"
    fi
    # The relay runs as user 10001 in the container and must read it; the folder stays private.
    chmod 644 "$DIR/relay-secret.txt"
    {
        say "# Written by install.sh. Change a value, then: docker compose up -d"
        if [ -n "$DOMAIN" ]; then say "COMPOSE_FILE=compose.caddy.yml"; say "RELAY_DOMAIN=$DOMAIN"
        else say "COMPOSE_FILE=compose.yml"; say "RELAY_PORT=$PORT"; fi
        say "OPENCOLLAB_VERSION=$VERSION"
    } > "$DIR/.env"
fi

# ---- Start ----------------------------------------------------------------------------------
cd "$DIR"
say ""
say "Starting the relay..."
if [ "${OPENCOLLAB_PULL:-always}" = always ]; then docker compose pull --quiet || die "Could not download the relay image"; fi
docker compose up -d --remove-orphans || die "Could not start the relay (see the messages above; is the port already in use?)"

DOMAIN=$(sed -n 's/^RELAY_DOMAIN=//p' .env)
PORT=$(sed -n 's/^RELAY_PORT=//p' .env)
fingerprint=""
if [ -z "$DOMAIN" ]; then
    tries=0
    while [ $tries -lt 60 ]; do
        fingerprint=$(docker compose logs relay 2>/dev/null | sed -n 's/.*fingerprint (SHA-256): *//p' | tail -1 | tr -d '\r')
        [ -n "$fingerprint" ] && break
        tries=$((tries + 1)); sleep 1
    done
    [ -n "$fingerprint" ] || die "The relay did not start. Its log: docker compose -f $DIR/compose.yml logs relay"
fi

say ""
say "The OpenCollab relay is running."
say ""
say "In the plugin (Settings | Tools | OpenCollab | Relay), each host enters:"
if [ -n "$DOMAIN" ]; then
    say "  Relay URL:                   wss://$DOMAIN"
    say "  Relay certificate fingerprint: (leave empty)"
else
    say "  Relay URL:                   wss://<this server's address>:$PORT"
    say "  Relay certificate fingerprint: $fingerprint"
fi
say "  Relay host secret:           $(cat relay-secret.txt)"
say ""
if [ -n "$DOMAIN" ]; then
    say "Caddy now asks Let's Encrypt for the certificate; that needs $DOMAIN to point to this"
    say "server and ports 80 and 443 open. Check it with: cd $DIR && docker compose logs caddy"
else
    addresses=$(hostname -I 2>/dev/null || true)
    [ -n "$addresses" ] && say "This computer's addresses: $addresses"
    say "Guests and hosts on the internet need TCP port $PORT open in the firewall and, at home,"
    say "forwarded on the router. With Docker Desktop (also from WSL) the port opens on Windows:"
    say "allow it in the Windows firewall."
fi
say ""
say "Give the secret only to people who host sessions. It is kept in $DIR/relay-secret.txt."
say "Update: run this installer again, or: cd $DIR && docker compose pull && docker compose up -d"
say "Stop:   cd $DIR && docker compose down"
