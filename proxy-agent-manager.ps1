#Requires -Version 5.1
param(
    [Parameter(Position = 0)]
    [string]$Command
)

$ErrorActionPreference = 'Stop'

$BASE_DIR       = $PSScriptRoot
$IMAGE          = 'us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent'
$CONFIG_FILE    = Join-Path $BASE_DIR 'config\config.json'
$VERSION_FILE   = Join-Path $BASE_DIR 'version'
$LOGFILE        = Join-Path $BASE_DIR 'logs\proxy-agent-manager.log'
$CONTAINER_LOG_DIR = '/app/logs'

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
    Add-Content -Path $LOGFILE -Value "$timestamp UTC - $Message"
}

function Get-LatestVersion {
    $registryHost   = $IMAGE.Split('/')[0]
    $repositoryPath = $IMAGE.Substring($registryHost.Length + 1)
    $registryUrl    = "https://$registryHost/v2/$repositoryPath/tags/list"

    try {
        $tagsJson = Invoke-RestMethod -Uri $registryUrl -Method Get
    } catch {
        Write-Host "ERROR: Unable to query image registry for latest version: $_" -ForegroundColor Red
        exit 1
    }

    $versions = $tagsJson.tags | Where-Object { $_ -match '^\d+\.\d+\.\d+$' }
    if (-not $versions) {
        Write-Host "ERROR: No valid version tags found in registry" -ForegroundColor Red
        exit 1
    }

    $latest = $versions | Sort-Object { [version]$_ } | Select-Object -Last 1
    return $latest
}

function Compare-SemVer {
    param([string]$A, [string]$B)
    $aVer = [version]$A
    $bVer = [version]$B
    return $aVer.CompareTo($bVer)
}

function Stop-ProxyAgent {
    docker inspect $CONTAINER_NAME 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        docker stop $CONTAINER_NAME | Out-Null
        docker rm $CONTAINER_NAME | Out-Null
        Write-Log "Stopped $CONTAINER_NAME."
    }
}

function Start-ProxyAgent {
    param([string]$Version)
    Write-Log "Starting $CONTAINER_NAME (version: $Version)..."

    Stop-ProxyAgent

    $null = New-Item -ItemType Directory -Force -Path (Join-Path $BASE_DIR 'logs')

    # Docker Desktop for Windows requires forward-slash paths in volume mounts
    $configMount = (Join-Path $BASE_DIR 'config\config.json') -replace '\\', '/'
    $logsMount   = (Join-Path $BASE_DIR 'logs') -replace '\\', '/'

    # Build argument list to avoid PowerShell variable expansion inside the health-cmd bash expression
    $dockerArgs = @(
        'run', '-d',
        '--name', $CONTAINER_NAME,
        '--restart', 'unless-stopped',
        '--memory=1g',
        '--label', 'fivetran=proxy-agent',
        '--label', "proxy_agent_id=$AGENT_ID",
        '--env', 'IS_DOCKER=true',
        '--env', "LOG_FOLDER_PATH=$CONTAINER_LOG_DIR",
        '--env', 'HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt',
        '--env', 'HEARTBEAT_EXPIRY_SECONDS=30',
        '--health-cmd', '[ ! -f $HEARTBEAT_PATH ] || { . $HEARTBEAT_PATH && [ $(date +%s) -lt $HEARTBEAT_EXPIRE_AT ]; }',
        '--health-interval', '10s',
        '--health-timeout', '3s',
        '--health-retries', '3',
        '--health-start-period', '30s',
        '-v', "${configMount}:/config/config.json:ro",
        '-v', "${logsMount}:${CONTAINER_LOG_DIR}",
        "${IMAGE}:${Version}",
        '-i', '/config/config.json'
    )

    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Log "Error: Failed to start container $CONTAINER_NAME"
        return $false
    }

    $timeout = 60
    Write-Log "Waiting for $CONTAINER_NAME to become healthy (timeout: ${timeout}s)..."

    $elapsed = 0
    while ($true) {
        $health = (docker inspect -f '{{.State.Health.Status}}' $CONTAINER_NAME 2>&1).Trim()
        if ($health -ne 'starting') { break }
        if ($elapsed -ge $timeout) {
            Write-Log "Error: $CONTAINER_NAME did not become healthy within ${timeout}s"
            docker logs $CONTAINER_NAME
            Stop-ProxyAgent
            return $false
        }
        Start-Sleep -Seconds 1
        $elapsed++
    }

    $finalStatus = (docker inspect -f '{{.State.Health.Status}}' $CONTAINER_NAME 2>&1).Trim()

    if ($finalStatus -eq 'healthy') {
        docker ps -a --filter "name=$CONTAINER_NAME" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
        Write-Log "Success: $CONTAINER_NAME is healthy."
        return $true
    } else {
        Write-Log "Error: $CONTAINER_NAME entered status: $finalStatus"
        docker logs $CONTAINER_NAME
        return $false
    }
}

function Invoke-Upgrade {
    Write-Host "Checking for latest version..."
    $latestVersion = Get-LatestVersion

    if ((Compare-SemVer $latestVersion $CURRENT_VERSION) -eq 0) {
        Write-Host "Already running the latest version ($CURRENT_VERSION)."
        exit 0
    }

    Write-Log "Upgrading from $CURRENT_VERSION to $latestVersion..."
    Set-Content -Path $VERSION_FILE -Value $latestVersion

    if (-not (Start-ProxyAgent $latestVersion)) {
        Write-Log "Upgrade failed, rolling back to $CURRENT_VERSION..."
        Set-Content -Path $VERSION_FILE -Value $CURRENT_VERSION
        Start-ProxyAgent $CURRENT_VERSION | Out-Null
    }
}

# ── Commands ─────────────────────────────────────────────────────────────────

switch ($Command) {
    'start' {
        if (-not (Start-ProxyAgent $CURRENT_VERSION)) { exit 1 }
    }
    'stop' {
        Write-Log "Stopping $CONTAINER_NAME..."
        Stop-ProxyAgent
    }
    'restart' {
        Write-Log "Restarting $CONTAINER_NAME..."
        Stop-ProxyAgent
        if (-not (Start-ProxyAgent $CURRENT_VERSION)) { exit 1 }
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
