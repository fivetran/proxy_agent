#Requires -Version 5.1
#
#  Installer for Fivetran Proxy Agent on Windows using Docker
#
#  For more information:
#     https://github.com/fivetran/proxy_agent
#
param(
    [Parameter(Position = 0)]
    [string]$ConfigPath,
    [string]$InstallDir
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# ── Constants ────────────────────────────────────────────────────────────────

$DEFAULT_INSTALL_DIR           = Join-Path $env:USERPROFILE 'fivetran-proxy-agent'
$MIN_DOCKER_VERSION            = '20.10.17'
$MIN_RECOMMENDED_CPU_COUNT     = 4
$MIN_RECOMMENDED_RAM_MB        = 5120
$MIN_RECOMMENDED_DISK_SPACE_MB = 2048
$AGENT_SCRIPT                  = 'proxy-agent-manager.ps1'
$AGENT_SCRIPT_URL              = 'https://raw.githubusercontent.com/fivetran/proxy_agent/main/proxy-agent-manager.ps1'
$REGISTRY_TAGS_URL             = 'https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list'
$DEFAULT_FIVETRAN_API_URL      = 'https://api.fivetran.com'

$script:Warnings = [System.Collections.Generic.List[string]]::new()
$script:Errors   = [System.Collections.Generic.List[string]]::new()

# ── Helpers ──────────────────────────────────────────────────────────────────

function Show-Usage {
    Write-Host @'
Usage:
  $env:RUNTIME='docker'; .\install.ps1 [<config.json>] [-InstallDir <dir>]
  $env:TOKEN='<token>'; $env:RUNTIME='docker'; .\install.ps1 [-InstallDir <dir>]

Options:
  -InstallDir <dir>   Installation directory (default: %USERPROFILE%\fivetran-proxy-agent)
'@
    exit 1
}

# ── Validation ───────────────────────────────────────────────────────────────

function Test-DockerVersion {
    if (-not (Get-Command 'docker' -ErrorAction SilentlyContinue)) {
        $script:Errors.Add("docker is not installed")
        return
    }

    $versionOutput = docker --version 2>&1
    if ($LASTEXITCODE -ne 0) {
        $script:Errors.Add("Failed to execute 'docker --version'. Docker may not be functioning properly")
        return
    }

    if ($versionOutput -match 'Docker version (\d+\.\d+\.\d+)') {
        $version = $Matches[1]
        if ([version]$version -lt [version]$MIN_DOCKER_VERSION) {
            $script:Errors.Add("Docker version $version does not meet the minimum requirement of $MIN_DOCKER_VERSION")
        }
    } else {
        $script:Warnings.Add("Unable to determine Docker version")
    }

    try { docker info 2>&1 | Out-Null } catch {}
    if ($LASTEXITCODE -ne 0) {
        $script:Errors.Add("Docker is installed but the Docker daemon is not accessible. Ensure that Docker Desktop is running and that your user has permission to access it.")
    }
}

function Test-Resources {
    $cpuCount = [Environment]::ProcessorCount
    if ($cpuCount -gt 0 -and $cpuCount -lt $MIN_RECOMMENDED_CPU_COUNT) {
        $script:Warnings.Add("CPU count ($cpuCount) is below the recommended minimum of $MIN_RECOMMENDED_CPU_COUNT")
    }

    try {
        $cs         = Get-CimInstance Win32_ComputerSystem
        $totalRamMB = [math]::Round($cs.TotalPhysicalMemory / 1MB)
        if ($totalRamMB -lt $MIN_RECOMMENDED_RAM_MB) {
            $script:Warnings.Add("RAM (${totalRamMB}MB) is below the recommended minimum of ${MIN_RECOMMENDED_RAM_MB}MB")
        }
    } catch {
        $script:Warnings.Add("Unable to determine available memory")
    }
}

function Test-DiskSpace {
    param([string]$Path)
    $parentDir = Split-Path $Path -Parent
    if (-not (Test-Path $parentDir)) { return }

    try {
        $qualifier   = Split-Path -Qualifier $parentDir
        $drive       = Get-PSDrive ($qualifier.TrimEnd(':'))
        $freeSpaceMB = [math]::Round($drive.Free / 1MB)
        if ($freeSpaceMB -lt $MIN_RECOMMENDED_DISK_SPACE_MB) {
            $script:Warnings.Add("Available disk space (${freeSpaceMB}MB) is below the recommended minimum of ${MIN_RECOMMENDED_DISK_SPACE_MB}MB")
        }
    } catch {
        $script:Warnings.Add("Unable to determine available disk space for $parentDir")
    }
}

function Show-WarningsAndErrors {
    if ($script:Warnings.Count -gt 0) {
        Write-Host "`nWARNINGS:"
        foreach ($w in $script:Warnings) { Write-Host "  - $w" }
    }
    if ($script:Errors.Count -gt 0) {
        Write-Host "`nERRORS:"
        foreach ($e in $script:Errors) { Write-Host "  - $e" }
        Write-Host ""
        Write-Host "ERROR: Please resolve the above errors before proceeding." -ForegroundColor Red
        exit 1
    }
    Write-Host ""
}

# ── Version resolution ───────────────────────────────────────────────────────

function Get-LatestVersion {
    try {
        $tagsJson = Invoke-RestMethod -Uri $REGISTRY_TAGS_URL -Method Get
    } catch {
        Write-Host "ERROR: Unable to query image registry for latest version: $_" -ForegroundColor Red
        exit 1
    }

    $versions = $tagsJson.tags | Where-Object { $_ -match '^\d+\.\d+\.\d+$' }
    if (-not $versions) {
        Write-Host "ERROR: No valid version tags found in registry" -ForegroundColor Red
        exit 1
    }

    return ($versions | Sort-Object { [version]$_ } | Select-Object -Last 1)
}

# ── Entry point ──────────────────────────────────────────────────────────────

if ($env:RUNTIME -ne 'docker') {
    Write-Host "ERROR: RUNTIME must be set to 'docker' (got: '$($env:RUNTIME)')." -ForegroundColor Red
    exit 1
}

$currentPrincipal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if ($currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Warning "Running as Administrator is not recommended. Consider running as a regular user."
}

if (-not $InstallDir) { $InstallDir = $DEFAULT_INSTALL_DIR }

if (-not $ConfigPath -and -not $env:TOKEN) { Show-Usage }

if ($ConfigPath -and -not (Test-Path $ConfigPath -PathType Leaf)) {
    Write-Host "ERROR: Config file not found or not readable: $ConfigPath" -ForegroundColor Red
    exit 1
}

# Detect WebSocket agent config and migrate to TOKEN flow
if ($ConfigPath -and -not $env:TOKEN) {
    $rawConfig = Get-Content $ConfigPath -Raw
    if ($rawConfig -match '"proxy_server_uri"') {
        $parsed    = $rawConfig | ConvertFrom-Json
        $agentId   = $parsed.agent_id
        $authToken = $parsed.auth_token
        if (-not $agentId -or -not $authToken) {
            Write-Host "ERROR: Could not extract credentials from WebSocket config" -ForegroundColor Red
            exit 1
        }
        $env:TOKEN  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${agentId}:${authToken}"))
        $ConfigPath = ''
        Write-Host "Detected WebSocket agent configuration. Fetching updated config from Fivetran..."
    }
}

Write-Host "Installing Fivetran Proxy Agent...`n"

# Pre-flight checks
Write-Host -NoNewline "Checking prerequisites... "
Test-DockerVersion
Test-Resources
Test-DiskSpace $InstallDir

if ($script:Warnings.Count -eq 0 -and $script:Errors.Count -eq 0) {
    Write-Host "OK`n"
} else {
    Write-Host ""
    Show-WarningsAndErrors
}

# Directory setup
if (Test-Path $InstallDir) {
    Write-Host "$InstallDir already exists, will re-use it."
} else {
    $null = New-Item -ItemType Directory -Force -Path $InstallDir
}

$testFile = Join-Path $InstallDir ".write-test-$PID"
try {
    $null = New-Item -ItemType File -Path $testFile -Force
    Remove-Item $testFile -Force
} catch {
    Write-Host "ERROR: Insufficient permissions to write to $InstallDir" -ForegroundColor Red
    exit 1
}

$null = New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir 'config')
$null = New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir 'logs')

# Download management script from public repo (temp file → move for atomicity)
Write-Host "Downloading management script..."
$tmpScript = Join-Path $InstallDir "$AGENT_SCRIPT.tmp"
try {
    Invoke-WebRequest -Uri $AGENT_SCRIPT_URL -OutFile $tmpScript -UseBasicParsing
} catch {
    Remove-Item -Path $tmpScript -Force -ErrorAction SilentlyContinue
    Write-Host "ERROR: Failed to download management script from ${AGENT_SCRIPT_URL}: $_" -ForegroundColor Red
    exit 1
}
Move-Item -Path $tmpScript -Destination (Join-Path $InstallDir $AGENT_SCRIPT) -Force

# Bootstrap or copy config
$configDest  = Join-Path $InstallDir 'config\config.json'
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name

# Create with restricted ACL before writing credentials (no world-readable window)
$null = New-Item -ItemType File -Path $configDest -Force
$configAcl = Get-Acl $configDest
$configAcl.SetAccessRuleProtection($true, $false)
$configAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    $currentUser, 'ReadAndExecute,Write', 'Allow')))
$configAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    'NT AUTHORITY\SYSTEM', 'Read', 'Allow')))
Set-Acl -Path $configDest -AclObject $configAcl

if ($ConfigPath) {
    [System.IO.File]::WriteAllText($configDest, (Get-Content $ConfigPath -Raw), [System.Text.UTF8Encoding]::new($false))
    Write-Host "Config copied to $configDest"
} else {
    $apiUrl = if ($env:FIVETRAN_API_URL) { $env:FIVETRAN_API_URL } else { $DEFAULT_FIVETRAN_API_URL }
    Write-Host "Fetching agent config from $apiUrl..."
    try {
        $configData = Invoke-RestMethod -Uri "$apiUrl/proxy-agent/configure" `
            -Method POST `
            -Headers @{ Authorization = "Basic $($env:TOKEN)"; Accept = 'application/json' }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode) {
            Write-Host "ERROR: Configure endpoint returned HTTP $statusCode" -ForegroundColor Red
        } else {
            Write-Host "ERROR: Failed to connect to configure endpoint: $_" -ForegroundColor Red
        }
        exit 1
    }
    [System.IO.File]::WriteAllText($configDest, ($configData | ConvertTo-Json -Depth 10 -Compress), [System.Text.UTF8Encoding]::new($false))
}

# Resolve and pin version
Write-Host "Resolving latest proxy agent version..."
$version = Get-LatestVersion
Set-Content -Path (Join-Path $InstallDir 'version') -Value $version -Encoding ascii
Write-Host "Using version $version"

# Clear credential from environment before spawning child process
Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue

# Start agent
& (Join-Path $InstallDir $AGENT_SCRIPT) start
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installation complete, but agent failed to start."
    Write-Host "To try to start the agent again, run: & '$(Join-Path $InstallDir $AGENT_SCRIPT)' start"
    exit 1
}

Write-Host "`nInstallation complete."
Write-Host "Install directory: $InstallDir"
