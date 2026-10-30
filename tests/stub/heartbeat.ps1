# Stub proxy agent: writes the heartbeat file the management script's health
# check reads, then idles. Any arguments (the real agent's -i <config>) are
# ignored.
$path = $env:HEARTBEAT_PATH
if (-not $path) { throw 'HEARTBEAT_PATH must be set' }
$expiry = if ($env:HEARTBEAT_EXPIRY_SECONDS) { [int]$env:HEARTBEAT_EXPIRY_SECONDS } else { 30 }

Write-Host "stub proxy agent started with args: $args"
$null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)
while ($true) {
    $expireAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + $expiry
    Set-Content -LiteralPath $path -Value "HEARTBEAT_EXPIRE_AT=$expireAt" -Encoding ascii
    Start-Sleep -Seconds 5
}
