#Requires -Version 5.1
param(
    [Parameter(Position = 0)]
    [string]$Command
)

$ErrorActionPreference = 'Stop'

$BASE_DIR           = $PSScriptRoot
$IMAGE              = 'us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent'
$CONFIG_FILE        = Join-Path $BASE_DIR 'config\config.json'
$VERSION_FILE       = Join-Path $BASE_DIR 'version'
$LOGFILE            = Join-Path $BASE_DIR 'logs\proxy-agent-manager.log'
$CONTAINER_LOG_DIR  = '/app/logs'
$SETTINGS_FILE = Join-Path $BASE_DIR 'settings.ps1'

if (Test-Path -LiteralPath $SETTINGS_FILE) {
    . $SETTINGS_FILE
}
if (-not $MEMORY_ALLOCATION_MB) { $MEMORY_ALLOCATION_MB = 5120 }

# ── Startup validation ───────────────────────────────────────────────────────

$validCommands = @('start', 'stop', 'restart', 'upgrade', 'status', 'logs')
if (-not $Command -or $Command -notin $validCommands) {
    Write-Host "Usage: $($MyInvocation.MyCommand.Name) {start|stop|restart|upgrade|status|logs}"
    exit 1
}

if (-not (Test-Path $CONFIG_FILE)) {
    Write-Host "ERROR: Config file not found: $CONFIG_FILE" -ForegroundColor Red
    exit 1
}

try {
    $configJson = Get-Content $CONFIG_FILE -Raw | ConvertFrom-Json
} catch {
    Write-Host "ERROR: Failed to parse config file: $_" -ForegroundColor Red
    exit 1
}

$AGENT_ID = $configJson.agent_id
if (-not $AGENT_ID -or $AGENT_ID -eq 'null') {
    Write-Host "ERROR: 'agent_id' missing in $CONFIG_FILE" -ForegroundColor Red
    exit 1
}

$CONTAINER_NAME = "proxy-agent-$AGENT_ID"

if (-not (Test-Path $VERSION_FILE)) {
    Write-Host "ERROR: $VERSION_FILE not found." -ForegroundColor Red
    exit 1
}

$CURRENT_VERSION = (Get-Content $VERSION_FILE -Raw).Trim()
if (-not $CURRENT_VERSION) {
    Write-Host "ERROR: $VERSION_FILE is empty." -ForegroundColor Red
    exit 1
}

# ── Functions ────────────────────────────────────────────────────────────────

function Write-Log {
    param([string]$Message)
    Write-Host $Message
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $LOGFILE)
    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    Add-Content -Path $LOGFILE -Value "$timestamp UTC - $Message" -Encoding ascii
}

function Get-ContainerOsType {
    $osTypeOutput = docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to determine whether Docker is running Linux or Windows containers."
    }

    $osType = ([string]$osTypeOutput).Trim()
    if ($osType -notin @('linux', 'windows')) {
        throw "Docker reported an unsupported container operating system: '$osType'."
    }

    return $osType
}

function Get-WindowsLtscVersion {
    try {
        $operatingSystem = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
    } catch {
        throw "Unable to detect the Windows Server version: $_"
    }

    if ($operatingSystem.ProductType -notin @(2, 3)) {
        throw "Windows Server is required for Windows containers; detected '$($operatingSystem.Caption)'."
    }

    switch ([int]$operatingSystem.BuildNumber) {
        17763 { return 'ltsc2019' }
        20348 { return 'ltsc2022' }
        26100 { return 'ltsc2025' }
    }

    throw "Unsupported Windows Server build '$($operatingSystem.BuildNumber)' ('$($operatingSystem.Caption)')."
}

function Get-ImageTagType {
    param([Parameter(Mandatory)][string]$Tag)

    if ($Tag -match '^\d+\.\d+\.\d+-windows-(?:ltsc2019|ltsc2022|ltsc2025)$') {
        return 'windows'
    }
    if ($Tag -match '^\d+\.\d+\.\d+-ubuntu-26\.04$') {
        return 'linux'
    }
    if ($Tag -match '^\d+\.\d+\.\d+$') {
        return 'legacy-linux'
    }

    throw "Invalid proxy-agent image tag '$Tag'."
}

function Get-ImageTagVariant {
    param([Parameter(Mandatory)][string]$Tag)

    if ($Tag -match '^\d+\.\d+\.\d+-windows-(?<variant>ltsc2019|ltsc2022|ltsc2025)$') {
        return $Matches.variant
    }

    throw "Invalid Windows proxy-agent image tag '$Tag'. Expected <version>-windows-ltsc2019, <version>-windows-ltsc2022, or <version>-windows-ltsc2025."
}

function Resolve-ImageTag {
    param(
        [Parameter(Mandatory)][string]$Tag,
        [Parameter(Mandatory)][ValidateSet('linux', 'windows')][string]$ContainerOsType
    )

    $tagType = Get-ImageTagType -Tag $Tag

    if ($Tag -match '^\d+\.\d+\.\d+$') {
        if ($ContainerOsType -eq 'windows') {
            return "$Tag-windows-$(Get-WindowsLtscVersion)"
        }
        return $Tag
    }

    if ($tagType -eq 'windows' -and $ContainerOsType -ne 'windows') {
        throw "Windows proxy-agent image tags require Docker to run Windows containers."
    }
    if ($tagType -eq 'linux' -and $ContainerOsType -ne 'linux') {
        throw "Linux proxy-agent image tags require Docker to run Linux containers."
    }

    return $Tag
}

function Get-ReleaseVersion {
    param([Parameter(Mandatory)][string]$Tag)

    if ($Tag -match '^(?<version>\d+\.\d+\.\d+)(?:(?:-ubuntu-26\.04)|(?:-windows-(?:ltsc2019|ltsc2022|ltsc2025)))?$') {
        return $Matches.version
    }

    throw "Invalid proxy-agent image tag '$Tag'."
}

function Get-LatestVersion {
    param(
        [Parameter(Mandatory)][ValidateSet('linux', 'windows')][string]$ContainerOsType,
        [string]$WindowsVersion
    )

    $registryHost   = $IMAGE.Split('/')[0]
    $repositoryPath = $IMAGE.Substring($registryHost.Length + 1)
    $registryUrl    = "https://$registryHost/v2/$repositoryPath/tags/list"

    try {
        $tagsJson = Invoke-RestMethod -Uri $registryUrl -Method Get
    } catch {
        Write-Host "ERROR: Unable to query image registry for latest version: $_" -ForegroundColor Red
        exit 1
    }

    if ($ContainerOsType -eq 'windows') {
        $tagPattern = '^\d+\.\d+\.\d+-windows-' + [regex]::Escape($WindowsVersion) + '$'
        $releasePattern = '-windows-' + [regex]::Escape($WindowsVersion) + '$'
    } else {
        $tagPattern = '^\d+\.\d+\.\d+-ubuntu-26\.04$'
        $releasePattern = '-ubuntu-26\.04$'
    }
    $versions = @($tagsJson.tags | Where-Object { $_ -match $tagPattern })
    if (-not $versions) {
        if ($ContainerOsType -eq 'windows') {
            Write-Host "ERROR: No published Windows image tags found for $WindowsVersion" -ForegroundColor Red
        } else {
            Write-Host "ERROR: No published Ubuntu 26.04 image tags found" -ForegroundColor Red
        }
        exit 1
    }

    $latest = $versions | Sort-Object { [version]($_ -replace $releasePattern, '') } | Select-Object -Last 1
    return $latest
}

function Compare-SemVer {
    param([string]$A, [string]$B)
    $aVer = [version](Get-ReleaseVersion -Tag $A)
    $bVer = [version](Get-ReleaseVersion -Tag $B)
    return $aVer.CompareTo($bVer)
}

function Stop-ProxyAgent {
    try { docker inspect $CONTAINER_NAME 2>&1 | Out-Null } catch {}
    if ($LASTEXITCODE -eq 0) {
        docker stop $CONTAINER_NAME | Out-Null
        docker rm $CONTAINER_NAME | Out-Null
        Write-Log "Stopped $CONTAINER_NAME."
    }
}

function Start-ProxyAgent {
    param(
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][ValidateSet('linux', 'windows')][string]$ContainerOsType
    )
    Write-Log "Starting $CONTAINER_NAME (version: $Version)..."

    Stop-ProxyAgent

    $null = New-Item -ItemType Directory -Force -Path (Join-Path $BASE_DIR 'logs')

    # Docker Desktop for Windows requires forward-slash paths in volume mounts.
    $configFileMount = (Join-Path $BASE_DIR 'config\config.json') -replace '\\', '/'
    $configDirectoryMount = (Join-Path $BASE_DIR 'config') -replace '\\', '/'
    $logsMount   = (Join-Path $BASE_DIR 'logs') -replace '\\', '/'

    # Build argument list to avoid PowerShell variable expansion inside the health check.
    $dockerArgs = @(
        'run', '-d',
        '--name', $CONTAINER_NAME,
        '--restart', 'unless-stopped',
        "--memory=${MEMORY_ALLOCATION_MB}m",
        '--label', 'fivetran=proxy-agent',
        '--label', "proxy_agent_id=$AGENT_ID",
        '--env', 'IS_DOCKER=true',
        '--env', 'HEARTBEAT_EXPIRY_SECONDS=30',
        '--health-interval', '10s',
        '--health-timeout', '3s',
        '--health-retries', '3',
        '--health-start-period', '30s'
    )

    if ($ContainerOsType -eq 'windows') {
        $containerConfigPath = 'C:/config/config.json'
        $containerLogDir = 'C:/app/logs'
        $containerHeartbeatPath = 'C:/app/logs/proxy-agent-heartbeat.txt'
        $windowsHealthCheckScript = @(
            '$path = $env:HEARTBEAT_PATH'
            'if (-not (Test-Path -LiteralPath $path)) { exit 0 }'
            '$line = Get-Content -LiteralPath $path -ErrorAction Stop | Select-Object -First 1'
            'if ($line -match ''^HEARTBEAT_EXPIRE_AT=(\d+)$'' -and [int64]$Matches[1] -gt [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) { exit 0 } else { exit 1 }'
        ) -join '; '
        $windowsHealthCheckEncoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($windowsHealthCheckScript))
        $windowsHealthCheck = "powershell.exe -NoProfile -EncodedCommand $windowsHealthCheckEncoded"
        $dockerArgs += @(
            '--env', "LOG_FOLDER_PATH=$containerLogDir",
            '--env', "HEARTBEAT_PATH=$containerHeartbeatPath",
            '--health-cmd', $windowsHealthCheck,
            '-v', "${configDirectoryMount}:C:/config:ro",
            '-v', "${logsMount}:$containerLogDir",
            "${IMAGE}:${Version}",
            '-i', $containerConfigPath
        )
    } else {
        $containerConfigPath = '/config/config.json'
        $containerLogDir = $CONTAINER_LOG_DIR
        $dockerArgs += @(
            '--env', "LOG_FOLDER_PATH=$containerLogDir",
            '--env', 'HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt',
            '--health-cmd', '[ ! -f $HEARTBEAT_PATH ] || { . $HEARTBEAT_PATH && [ $(date +%s) -lt $HEARTBEAT_EXPIRE_AT ]; }',
            '-v', "${configFileMount}:/config/config.json:ro",
            '-v', "${logsMount}:$containerLogDir",
            "${IMAGE}:${Version}",
            '-i', $containerConfigPath
        )
    }

    & docker @dockerArgs | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Log "Error: Failed to start container $CONTAINER_NAME"
        return $false
    }

    $timeout = 60
    Write-Log "Waiting for $CONTAINER_NAME to become healthy (timeout: ${timeout}s)..."

    $elapsed = 0
    while ($true) {
        try {
            $health = (docker inspect -f '{{.State.Health.Status}}' $CONTAINER_NAME 2>&1).Trim()
        } catch {
            Write-Log "Error: Failed to inspect $CONTAINER_NAME"
            return $false
        }
        if ($health -ne 'starting') { break }
        if ($elapsed -ge $timeout) {
            Write-Log "Error: $CONTAINER_NAME did not become healthy within ${timeout}s"
            docker logs $CONTAINER_NAME | Out-Host
            Stop-ProxyAgent
            return $false
        }
        Start-Sleep -Seconds 1
        $elapsed++
    }

    try {
        $finalStatus = (docker inspect -f '{{.State.Health.Status}}' $CONTAINER_NAME 2>&1).Trim()
    } catch {
        $finalStatus = 'unknown'
    }

    if ($finalStatus -eq 'healthy') {
        docker ps -a --filter "name=$CONTAINER_NAME" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | Out-Host
        Write-Log "Success: $CONTAINER_NAME is healthy."
        return $true
    } else {
        Write-Log "Error: $CONTAINER_NAME entered status: $finalStatus"
        docker logs $CONTAINER_NAME | Out-Host
        return $false
    }
}

function Start-CurrentProxyAgent {
    $containerOsType = Get-ContainerOsType
    $resolvedVersion = Resolve-ImageTag -Tag $CURRENT_VERSION -ContainerOsType $containerOsType

    if (-not (Start-ProxyAgent -Version $resolvedVersion -ContainerOsType $containerOsType)) {
        return $false
    }

    if ($resolvedVersion -ne $CURRENT_VERSION) {
        Set-Content -Path $VERSION_FILE -Value $resolvedVersion -Encoding ascii
    }

    return $true
}

function Invoke-Upgrade {
    Write-Host "Checking for latest version..."
    $containerOsType = Get-ContainerOsType
    $currentVersion = Resolve-ImageTag -Tag $CURRENT_VERSION -ContainerOsType $containerOsType
    if ($containerOsType -eq 'windows') {
        $windowsVersion = Get-ImageTagVariant -Tag $currentVersion
        $latestVersion = Get-LatestVersion -ContainerOsType windows -WindowsVersion $windowsVersion
    } else {
        $tagType = Get-ImageTagType -Tag $currentVersion
        if ($tagType -eq 'windows') {
            throw "Windows proxy-agent image tags require Docker to run Windows containers."
        }
        $latestVersion = Get-LatestVersion -ContainerOsType linux
    }

    if ((Compare-SemVer $latestVersion $currentVersion) -eq 0) {
        if ($currentVersion -ne $CURRENT_VERSION) {
            Write-Log "Switching image tag from $CURRENT_VERSION to $currentVersion..."
            if (-not (Start-ProxyAgent -Version $currentVersion -ContainerOsType $containerOsType)) {
                exit 1
            }
            Set-Content -Path $VERSION_FILE -Value $currentVersion -Encoding ascii
        }

        Write-Host "Already running the latest version ($currentVersion)."
        exit 0
    }

    Write-Log "Upgrading from $currentVersion to $latestVersion..."
    Set-Content -Path $VERSION_FILE -Value $latestVersion -Encoding ascii

    if (-not (Start-ProxyAgent -Version $latestVersion -ContainerOsType $containerOsType)) {
        Write-Log "Upgrade failed, rolling back to $currentVersion..."
        Set-Content -Path $VERSION_FILE -Value $currentVersion -Encoding ascii
        Start-ProxyAgent -Version $currentVersion -ContainerOsType $containerOsType | Out-Null
    }
}

# ── Commands ─────────────────────────────────────────────────────────────────

switch ($Command) {
    'start' {
        if (-not (Start-CurrentProxyAgent)) { exit 1 }
    }
    'stop' {
        Write-Log "Stopping $CONTAINER_NAME..."
        Stop-ProxyAgent
    }
    'restart' {
        Write-Log "Restarting $CONTAINER_NAME..."
        if (-not (Start-CurrentProxyAgent)) { exit 1 }
    }
    'upgrade' {
        Invoke-Upgrade
    }
    'status' {
        docker ps -a --filter "name=$CONTAINER_NAME" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    }
    'logs' {
        docker logs -f $CONTAINER_NAME
    }
}
