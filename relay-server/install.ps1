# Installs or updates an OpenCollab relay with Docker Desktop on Windows, asking a few questions.
# In PowerShell:
#
#   irm https://raw.githubusercontent.com/chomusuke-png/OpenCollab/main/relay-server/install.ps1 | iex
#
# Without questions (defaults or the options given):
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/chomusuke-png/OpenCollab/main/relay-server/install.ps1))) -Yes -Port 8443
#
# Options: -Dir <folder> (default: %USERPROFILE%\opencollab-relay), -Domain <name> (free
# Let's Encrypt certificate through Caddy, ports 80 and 443; without one: self-signed),
# -Port <port> (self-signed, default 8443), -Version <tag> (latest, 3 or X.Y.Z), -Yes.
#
# Running it again in the same folder updates the relay and keeps its settings, secret and
# certificate. Works in Windows PowerShell 5.1 and PowerShell 7 (also on Linux and macOS).
[CmdletBinding()]
param(
    [string]$Dir = (Join-Path $HOME 'opencollab-relay'),
    [string]$Domain = '',
    [int]$Port = 8443,
    [string]$Version = 'latest',
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'

$Source = if ($env:OPENCOLLAB_SOURCE) { $env:OPENCOLLAB_SOURCE } else { 'https://raw.githubusercontent.com/chomusuke-png/OpenCollab/main/relay-server/docker' }
$domainGiven = $PSBoundParameters.ContainsKey('Domain')
$portGiven = $PSBoundParameters.ContainsKey('Port')

function Stop-Install([string]$Message) {
    Write-Host ''
    Write-Host "Error: $Message" -ForegroundColor Red
    # exit would close the window when the script runs through iex.
    throw 'The relay was not installed.'
}

function Test-Interactive { -not $Yes -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected }

function Read-Answer([string]$Question, [string]$Default) {
    if (-not (Test-Interactive)) { return $Default }
    $answer = Read-Host "$Question [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}

function Write-TextFile([string]$Path, [string]$Text) {
    # UTF-8 without a byte order mark and with LF: compose reads .env and the relay the secret.
    [IO.File]::WriteAllText($Path, ($Text -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
}

function Invoke-Docker {
    # Shown, not returned: only the result is the function's value.
    & docker @args | Out-Host
    if ($LASTEXITCODE -ne 0) { return $false }
    return $true
}

Write-Host 'OpenCollab relay installer'
Write-Host ''

# ---- Docker ---------------------------------------------------------------------------------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-Install "Docker is not installed. Install Docker Desktop: https://docs.docker.com/desktop/setup/install/windows-install/"
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Stop-Install "Docker is installed but not running. Open Docker Desktop, wait until it says it is running, and run this again."
}
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Stop-Install "The 'docker compose' command is missing. Update Docker Desktop." }

# ---- Settings -------------------------------------------------------------------------------
$Dir = [IO.Path]::GetFullPath($Dir)
$update = (Test-Path (Join-Path $Dir '.env')) -and (Test-Path (Join-Path $Dir 'relay-secret.txt'))
if ($update) {
    Write-Host "A relay is already installed in $Dir."
    $reply = Read-Answer 'Update it to the newest image, keeping its settings? (y/n)' 'y'
    if ($reply -notmatch '^[YySs]') { Write-Host 'Nothing changed.'; return }
} else {
    $Dir = [IO.Path]::GetFullPath((Read-Answer 'Install folder' $Dir))
    if (-not $domainGiven -and (Test-Interactive)) {
        Write-Host ''
        Write-Host 'With a domain whose DNS points to this computer, the relay gets a free certificate from'
        Write-Host "Let's Encrypt (through Caddy) and nobody needs to enter a fingerprint. That needs ports 80"
        Write-Host 'and 443. Without a domain, the relay makes its own certificate, and hosts enter its'
        Write-Host 'fingerprint once.'
        $Domain = Read-Answer 'Domain (empty for none)' ''
    }
    if ($Domain) {
        if ($Domain -notmatch '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$') { Stop-Install "Not a domain name: $Domain" }
    } else {
        if (-not $portGiven) {
            $answer = Read-Answer 'Port' "$Port"
            if ($answer -notmatch '^[0-9]{1,5}$') { Stop-Install "Not a port: $answer" }
            $Port = [int]$answer
        }
        if ($Port -lt 1 -or $Port -gt 65535) { Stop-Install "Not a port: $Port" }
    }
    if ($Version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { Stop-Install "Not a version: $Version" }
}

# ---- Files ----------------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
Write-Host ''
Write-Host "Downloading the compose files to $Dir..."
if ($Source -match '^https?://') {
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
}
foreach ($name in 'compose.yml', 'compose.caddy.yml', 'Caddyfile') {
    $target = Join-Path $Dir $name
    try {
        if ($Source -match '^https?://') { Invoke-WebRequest -UseBasicParsing -Uri "$Source/$name" -OutFile "$target.new" }
        else { Copy-Item (Join-Path $Source $name) "$target.new" -Force }
        Move-Item "$target.new" $target -Force
    } catch { Stop-Install "Could not download $name from $Source ($($_.Exception.Message))" }
}

if (-not $update) {
    $secretFile = Join-Path $Dir 'relay-secret.txt'
    if (-not (Test-Path $secretFile) -or (Get-Item $secretFile).Length -eq 0) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes); $rng.Dispose()
        Write-TextFile $secretFile ([Convert]::ToBase64String($bytes) + "`n")
    }
    $lines = @('# Written by install.ps1. Change a value, then: docker compose up -d')
    if ($Domain) { $lines += 'COMPOSE_FILE=compose.caddy.yml'; $lines += "RELAY_DOMAIN=$Domain" }
    else { $lines += 'COMPOSE_FILE=compose.yml'; $lines += "RELAY_PORT=$Port" }
    $lines += "OPENCOLLAB_VERSION=$Version"
    Write-TextFile (Join-Path $Dir '.env') (($lines -join "`n") + "`n")
}

# ---- Start ----------------------------------------------------------------------------------
Push-Location $Dir
try {
    Write-Host ''
    Write-Host 'Starting the relay...'
    $pull = if ($env:OPENCOLLAB_PULL) { $env:OPENCOLLAB_PULL } else { 'always' }
    if ($pull -eq 'always' -and -not (Invoke-Docker compose pull --quiet)) { Stop-Install 'Could not download the relay image.' }
    if (-not (Invoke-Docker compose up -d --remove-orphans)) { Stop-Install 'Could not start the relay (see the messages above; is the port already in use?).' }

    $envText = [IO.File]::ReadAllText((Join-Path $Dir '.env'))
    $Domain = if ($envText -match '(?m)^RELAY_DOMAIN=(.*)$') { $Matches[1].Trim() } else { '' }
    $Port = if ($envText -match '(?m)^RELAY_PORT=(\d+)') { [int]$Matches[1] } else { 8443 }
    $fingerprint = ''
    if (-not $Domain) {
        for ($i = 0; $i -lt 60 -and -not $fingerprint; $i++) {
            $log = (& docker compose logs relay 2>$null) -join "`n"
            $found = [regex]::Matches($log, 'fingerprint \(SHA-256\): *([0-9A-Fa-f:]+)')
            if ($found.Count -gt 0) { $fingerprint = $found[$found.Count - 1].Groups[1].Value } else { Start-Sleep -Seconds 1 }
        }
        if (-not $fingerprint) { Stop-Install "The relay did not start. Its log: cd `"$Dir`"; docker compose logs relay" }
    }
    $secret = ([IO.File]::ReadAllText((Join-Path $Dir 'relay-secret.txt'))).Trim()
} finally {
    Pop-Location
}

Write-Host ''
Write-Host 'The OpenCollab relay is running.' -ForegroundColor Green
Write-Host ''
Write-Host 'In the plugin (Settings | Tools | OpenCollab | Relay), each host enters:'
if ($Domain) {
    Write-Host "  Relay URL:                     wss://$Domain"
    Write-Host '  Relay certificate fingerprint: (leave empty)'
} else {
    Write-Host "  Relay URL:                     wss://<this computer's address>:$Port"
    Write-Host "  Relay certificate fingerprint: $fingerprint"
}
Write-Host "  Relay host secret:             $secret"
Write-Host ''
if ($Domain) {
    Write-Host "Caddy now asks Let's Encrypt for the certificate; that needs $Domain to point to this"
    Write-Host "computer and ports 80 and 443 open. Check it with: cd `"$Dir`"; docker compose logs caddy"
} else {
    Write-Host "Hosts and guests on the internet need TCP port $Port allowed in the Windows firewall and"
    Write-Host 'forwarded on the router.'
}
Write-Host ''
Write-Host "Give the secret only to people who host sessions. It is kept in $(Join-Path $Dir 'relay-secret.txt')."
Write-Host "Update: run this installer again, or: cd `"$Dir`"; docker compose pull; docker compose up -d"
Write-Host "Stop:   cd `"$Dir`"; docker compose down"
