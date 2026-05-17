# Ensures every host path referenced by docker-compose.yml exists as the
# correct type (file vs directory) BEFORE `docker compose up`. If a file
# is missing Docker silently creates a directory in its place, which then
# confuses the server. Run this once on a fresh checkout.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Ensure-File($path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType File -Path $path -Force | Out-Null
        Write-Host "  [+] created file:  $path"
    }
}
function Ensure-Dir($path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
        Write-Host "  [+] created dir:   $path"
    }
}

Write-Host "Pre-flight: verifying host-side paths..."

# ---- save_server ----
Ensure-File "$root\save_server\server_config.json"
Ensure-File "$root\save_server\licenses.json"
Ensure-File "$root\save_server\server_private_key.pem"
Ensure-File "$root\save_server\saves.db"
Ensure-File "$root\save_server\server.log"
Ensure-Dir  "$root\save_server\saves"

# ---- patch_server ----
Ensure-File "$root\patch_server\patch_config.json"
Ensure-File "$root\patch_server\manifest.json"
Ensure-File "$root\patch_server\patch_private_key.pem"
Ensure-Dir  "$root\patch_server\patches"

# ---- multiplayer_server ----
Ensure-File "$root\multiplayer_server\mp_config.json"
Ensure-File "$root\multiplayer_server\licenses.json"
Ensure-File "$root\multiplayer_server\server_private_key.pem"
Ensure-Dir  "$root\multiplayer_server\maps"

Write-Host "Pre-flight OK."
