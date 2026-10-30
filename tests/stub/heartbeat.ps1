# Stub proxy agent: writes the heartbeat file the management script's health
# check reads, then idles. It expects to be invoked like the real agent, with
# `-i <config path>` pointing at a readable file, and exits otherwise so the
# container never becomes healthy and the test fails.
if ($args.Count -lt 2 -or $args[0] -ne '-i') {
    Write-Error "stub proxy agent: expected '-i <config path>', got: $args"
    exit 1
}
if (-not (Test-Path -LiteralPath $args[1] -PathType Leaf)) {
    Write-Error "stub proxy agent: config file not readable: $($args[1])"
    exit 1
}

$path = $env:HEARTBEAT_PATH
if (-not $path) { throw 'HEARTBEAT_PATH must be set' }
$expiry = if ($env:HEARTBEAT_EXPIRY_SECONDS) { [int]$env:HEARTBEAT_EXPIRY_SECONDS } else { 30 }

Write-Host "stub proxy agent started with config $($args[1])"
$null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)
while ($true) {
    $expireAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + $expiry
    Set-Content -LiteralPath $path -Value "HEARTBEAT_EXPIRE_AT=$expireAt" -Encoding ascii
    Start-Sleep -Seconds 5
}
