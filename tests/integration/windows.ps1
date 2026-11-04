#Requires -Version 5.1
<#
.SYNOPSIS
Integration test for install.ps1 and proxy-agent-manager.ps1 with Windows
containers on a real Docker daemon.

.DESCRIPTION
Uses a stub agent image built here (tests\stub) for the host's LTSC version and
a local stand-in for the registry and the script download, so it needs no
Fivetran credentials and never contacts the real registry; the versions below
are fixtures. The only network access is pulling the stub's servercore base
image.
#>
param(
    [int]$Port = 8181
)

$ErrorActionPreference = 'Stop'

$RepoRoot    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$StubImage   = 'proxy-agent-stub'
$OldVersion  = '1.2.3'
$NewVersion  = '1.2.4'
$AgentId     = 'test-agent'   # matches the fixtures
$Container   = "proxy-agent-$AgentId"
$TempRoot    = Join-Path ([System.IO.Path]::GetTempPath()) "proxy-agent-test-$PID"
$InstallDir  = Join-Path $TempRoot 'fivetran-proxy-agent'
$Fixtures    = Join-Path $RepoRoot 'tests\integration\fixtures'
# Credential the stub configure endpoint accepts: agent_id:auth_token from
# fixtures\config-websocket.json, base64 encoded as the dashboard does.
$TokenValue  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('test-agent:secret'))
$Server      = $null

function Fail {
    param([string]$Message)
    throw "FAIL: $Message"
}

function Get-LtscVersion {
    $build = [int](Get-CimInstance -ClassName Win32_OperatingSystem).BuildNumber
    switch ($build) {
        17763 { return 'ltsc2019' }
        20348 { return 'ltsc2022' }
        26100 { return 'ltsc2025' }
    }
    Fail "Unsupported Windows build $build; the stub image needs a matching servercore tag"
}

function Get-CurrentImage {
    return (docker inspect -f '{{.Config.Image}}' $Container).Trim()
}

function Assert-Image {
    param([string]$Expected)
    $actual = Get-CurrentImage
    if ($actual -ne $Expected) { Fail "expected image $Expected, got $actual" }
}

function Assert-Healthy {
    $status = (docker inspect -f '{{.State.Health.Status}}' $Container).Trim()
    if ($status -ne 'healthy') { Fail "expected healthy container, got $status" }
}

function Assert-VersionFile {
    param([string]$Prefix)
    $content = (Get-Content -LiteralPath (Join-Path $InstallDir 'version') -Raw).Trim()
    if (-not $content.StartsWith($Prefix)) { Fail "expected version file to start with $Prefix, got '$content'" }
}

function Invoke-Manager {
    param([string]$Command, [string]$Dir = $InstallDir)
    $output = & (Join-Path $Dir 'proxy-agent-manager.ps1') $Command *>&1 | Out-String
    Write-Host $output
    if ($LASTEXITCODE -ne 0) { Fail "manager $Command exited with $LASTEXITCODE" }
    return $output
}

function Assert-ConfigMatches {
    param([string]$Dir, [string]$Fixture)
    $expected = Get-Content -LiteralPath (Join-Path $Fixtures $Fixture) -Raw
    $actual = Get-Content -LiteralPath (Join-Path $Dir 'config\config.json') -Raw
    if ($actual -ne $expected) { Fail "config.json in $Dir does not match $Fixture" }
}

try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot
    $ltsc = Get-LtscVersion
    $oldTag = "$OldVersion-windows-$ltsc"
    $newTag = "$NewVersion-windows-$ltsc"

    Write-Host "== Building stub image for $ltsc"
    docker build -q --build-arg "BASE_TAG=$ltsc" -f (Join-Path $RepoRoot 'tests\stub\Dockerfile.windows') -t "${StubImage}:$oldTag" (Join-Path $RepoRoot 'tests\stub') | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'stub image build failed' }
    docker tag "${StubImage}:$oldTag" "${StubImage}:$newTag"

    Write-Host "== Starting stub registry on port $Port"
    $tags = "$oldTag,$newTag,$NewVersion-ubuntu-26.04,$NewVersion,$NewVersion-windows-ltsc2016"
    # Start-Process does not quote arguments, so paths are quoted here.
    $Server = Start-Process -FilePath python -PassThru -NoNewWindow -ArgumentList @(
        "`"$(Join-Path $RepoRoot 'tests\integration\registry_server.py')`"",
        '--port', $Port, '--root', "`"$RepoRoot`"", '--tags', $tags,
        '--token', $TokenValue, '--config', "`"$(Join-Path $Fixtures 'config.json')`""
    )
    $ready = $false
    foreach ($attempt in 1..40) {
        # A server that died on bind (port in use) must not let the readiness
        # check succeed against some other listener.
        if ($Server.HasExited) { Fail "stub registry exited; is port $Port in use?" }
        try {
            $null = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/proxy-agent-manager.ps1" -UseBasicParsing -TimeoutSec 2
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) { Fail 'stub registry did not start' }

    $env:RUNTIME = 'docker'
    $env:MEMORY_ALLOCATION_MB = '512'
    $env:PROXY_AGENT_IMAGE = $StubImage
    $env:PROXY_AGENT_REGISTRY_TAGS_URL = "http://127.0.0.1:$Port/v2/stub/tags/list"
    $env:PROXY_AGENT_SCRIPT_URL = "http://127.0.0.1:$Port/proxy-agent-manager.ps1"
    $env:FIVETRAN_API_URL = "http://127.0.0.1:$Port"

    Write-Host '== Installing from a config file'
    & (Join-Path $RepoRoot 'install.ps1') (Join-Path $Fixtures 'config.json') -InstallDir $InstallDir
    if ($LASTEXITCODE -ne 0) { Fail "install.ps1 exited with $LASTEXITCODE" }
    Assert-Image "${StubImage}:$newTag"
    Assert-Healthy
    Assert-VersionFile $NewVersion
    $settings = Get-Content -LiteralPath (Join-Path $InstallDir 'settings.ps1') -Raw
    if ($settings -notmatch '\$MEMORY_ALLOCATION_MB = 512') { Fail 'settings.ps1 does not record the memory allocation' }
    $memory = [int64](docker inspect -f '{{.HostConfig.Memory}}' $Container).Trim()
    if ($memory -ne 512MB) { Fail "container memory limit not applied (got $memory)" }
    Assert-ConfigMatches $InstallDir 'config.json'

    Write-Host '== status'
    $status = Invoke-Manager status
    if ($status -notmatch [regex]::Escape($Container)) { Fail 'status does not list the container' }

    Write-Host "== Rolling back to $OldVersion via the version file"
    Set-Content -LiteralPath (Join-Path $InstallDir 'version') -Value $OldVersion -Encoding ascii
    $null = Invoke-Manager start
    Assert-Image "${StubImage}:$oldTag"
    Assert-Healthy

    Write-Host '== Upgrading'
    $null = Invoke-Manager upgrade
    Assert-Image "${StubImage}:$newTag"
    Assert-Healthy
    Assert-VersionFile $NewVersion

    Write-Host '== Upgrading again is a no-op'
    $output = Invoke-Manager upgrade
    if ($output -notmatch 'Already running the latest version') { Fail 'second upgrade did not report already latest' }
    Assert-Image "${StubImage}:$newTag"

    Write-Host '== restart'
    $null = Invoke-Manager restart
    Assert-Healthy

    Write-Host '== stop'
    $null = Invoke-Manager stop
    $remaining = docker ps -a --filter "name=$Container" --format '{{.Names}}'
    if ($remaining) { Fail "container still exists after stop: $remaining" }

    Write-Host '== Installing with TOKEN fetches the config from the configure endpoint'
    $tokenInstallDir = Join-Path $TempRoot 'fivetran-proxy-agent-token'
    $env:TOKEN = $TokenValue
    & (Join-Path $RepoRoot 'install.ps1') -InstallDir $tokenInstallDir
    if ($LASTEXITCODE -ne 0) { Fail "install.ps1 with TOKEN exited with $LASTEXITCODE" }
    Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue
    Assert-ConfigMatches $tokenInstallDir 'config.json'
    Assert-Image "${StubImage}:$newTag"
    Assert-Healthy
    $null = Invoke-Manager stop -Dir $tokenInstallDir

    Write-Host '== Installing with a wrong TOKEN fails'
    $badTokenInstallDir = Join-Path $TempRoot 'fivetran-proxy-agent-bad-token'
    $env:TOKEN = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('test-agent:wrong'))
    $badTokenOutput = & (Join-Path $RepoRoot 'install.ps1') -InstallDir $badTokenInstallDir *>&1 | Out-String
    $badTokenExit = $LASTEXITCODE
    Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue
    if ($badTokenExit -eq 0) { Fail 'install.ps1 succeeded with a wrong TOKEN' }
    if ($badTokenOutput -notmatch 'HTTP 401') { Fail "expected an HTTP 401 error, got: $badTokenOutput" }
    $badConfig = Join-Path $badTokenInstallDir 'config\config.json'
    if ((Test-Path -LiteralPath $badConfig) -and (Get-Item -LiteralPath $badConfig).Length -gt 0) {
        Fail 'config.json was written despite the failed configure call'
    }

    Write-Host '== Installing from a WebSocket config migrates it through the configure endpoint'
    $wsInstallDir = Join-Path $TempRoot 'fivetran-proxy-agent-websocket'
    & (Join-Path $RepoRoot 'install.ps1') (Join-Path $Fixtures 'config-websocket.json') -InstallDir $wsInstallDir
    if ($LASTEXITCODE -ne 0) { Fail "install.ps1 with a WebSocket config exited with $LASTEXITCODE" }
    Assert-ConfigMatches $wsInstallDir 'config.json'
    Assert-Image "${StubImage}:$newTag"
    Assert-Healthy
    $null = Invoke-Manager stop -Dir $wsInstallDir

    Write-Host 'PASS'
} finally {
    # Best-effort cleanup; a failure here must not hide the test's own error.
    $ErrorActionPreference = 'Continue'
    if ($Server -and -not $Server.HasExited) { Stop-Process -Id $Server.Id -Force }
    docker rm -f $Container 2>&1 | Out-Null
    if (Test-Path -LiteralPath $TempRoot) { Remove-Item -LiteralPath $TempRoot -Recurse -Force }
}
