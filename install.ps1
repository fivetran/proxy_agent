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
$MIN_RECOMMENDED_RAM_MB        = 6827  # so that 75% (proxy-agent-manager's container memory allocation) clears 5GB
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
    $parentDir = Split-Path -LiteralPath $Path
    if (-not (Test-Path -LiteralPath $parentDir)) {
        $script:Warnings.Add("Unable to determine available disk space: parent directory $parentDir does not exist")
        return
    }

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
        $tagsJson = Invoke-RestMethod -Uri $REGISTRY_TAGS_URL -Method Get -TimeoutSec 30
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

# Read config file once; also validates existence and readability
$configFileContent = $null
if ($ConfigPath) {
    try {
        $configFileContent = Get-Content -LiteralPath $ConfigPath -Raw
    } catch {
        Write-Host "ERROR: Config file not found or not readable: $ConfigPath" -ForegroundColor Red
        exit 1
    }
}

try {
    # Detect WebSocket agent config and migrate to TOKEN flow.
    # Store derived credential in a local variable; $env:TOKEN is set only just before the
    # configure API call so that pre-flight docker commands do not inherit it.
    $derivedToken = $null
    if ($configFileContent -and -not $env:TOKEN) {
        if ($configFileContent -match '"proxy_server_uri"') {
            try {
                $parsed = $configFileContent | ConvertFrom-Json
            } catch {
                Write-Host "ERROR: Could not parse config file as JSON: $ConfigPath" -ForegroundColor Red
                exit 1
            }
            $agentId   = $parsed.agent_id
            $authToken = $parsed.auth_token
            if (-not $agentId -or -not $authToken) {
                Write-Host "ERROR: Could not extract credentials from WebSocket config" -ForegroundColor Red
                exit 1
            }
            $derivedToken = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${agentId}:${authToken}"))
            $ConfigPath   = ''
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
    if (Test-Path -LiteralPath $InstallDir -PathType Container) {
        Write-Host "$InstallDir already exists, will re-use it."
    } else {
        $null = New-Item -ItemType Directory -Force -Path $InstallDir
    }

    $testFile = Join-Path $InstallDir ".write-test-$PID"
    try {
        $null = New-Item -ItemType File -Path $testFile -Force
        Remove-Item -LiteralPath $testFile -Force
    } catch {
        Write-Host "ERROR: Insufficient permissions to write to $InstallDir" -ForegroundColor Red
        exit 1
    }

    $null = New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir 'config')
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir 'logs')

    # Download management script from public repo (temp file → move for atomicity)
    Write-Host "Downloading management script..."
    $agentScript = Join-Path $InstallDir $AGENT_SCRIPT
    $tmpScript   = Join-Path $InstallDir "$AGENT_SCRIPT.$PID.tmp"
    try {
        Invoke-WebRequest -Uri $AGENT_SCRIPT_URL -OutFile $tmpScript -UseBasicParsing -TimeoutSec 30
    } catch {
        Remove-Item -LiteralPath $tmpScript -Force -ErrorAction SilentlyContinue
        Write-Host "ERROR: Failed to download management script from ${AGENT_SCRIPT_URL}: $_" -ForegroundColor Red
        exit 1
    }
    Move-Item -LiteralPath $tmpScript -Destination $agentScript -Force
    Unblock-File -LiteralPath $agentScript

    # Bootstrap or copy config
    $configDest  = Join-Path $InstallDir 'config\config.json'
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name

    # Create empty file first, then restrict ACL, then write credentials.
    # The file is empty (no credentials) during the brief window between creation and ACL restriction.
    try {
        $null = New-Item -ItemType File -Path $configDest -Force
    } catch [System.UnauthorizedAccessException] {
        Write-Host "ERROR: Cannot create $configDest - if reinstalling, run as the original installing user or delete the existing file manually." -ForegroundColor Red
        exit 1
    }
    $configAcl = [System.Security.AccessControl.FileSecurity]::new()
    $configAcl.SetAccessRuleProtection($true, $false)
    $configAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
        $currentUser, 'Read,Write', 'Allow')))
    $configAcl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
        'NT AUTHORITY\SYSTEM', 'Read', 'Allow')))
    [System.IO.File]::SetAccessControl($configDest, $configAcl)

    if ($ConfigPath) {
        [System.IO.File]::WriteAllText($configDest, $configFileContent, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Config copied to $configDest"
    } else {
        $apiUrl = if ($env:FIVETRAN_API_URL) { $env:FIVETRAN_API_URL } else { $DEFAULT_FIVETRAN_API_URL }
        Write-Host "Fetching agent config from $apiUrl..."
        if ($derivedToken) { $env:TOKEN = $derivedToken }
        try {
            $response = Invoke-WebRequest -Uri "$apiUrl/proxy-agent/configure" `
                -Method POST `
                -Headers @{ Authorization = "Basic $($env:TOKEN)"; Accept = 'application/json' } `
                -TimeoutSec 30 `
                -UseBasicParsing
        } catch {
            $statusCode = $_.Exception.Response.StatusCode.value__
            if ($statusCode -eq 401) {
                Write-Host "ERROR: Authentication failed (HTTP 401). Ensure your TOKEN is valid." -ForegroundColor Red
            } elseif ($statusCode) {
                Write-Host "ERROR: Configure endpoint returned HTTP $statusCode" -ForegroundColor Red
            } else {
                Write-Host "ERROR: Failed to connect to configure endpoint: $_" -ForegroundColor Red
            }
            exit 1
        }
        if ($response.StatusCode -ne 200) {
            Write-Host "ERROR: Configure endpoint returned HTTP $($response.StatusCode)" -ForegroundColor Red
            exit 1
        }
        try {
            $null = $response.Content | ConvertFrom-Json
        } catch {
            Write-Host "ERROR: Configure endpoint returned an invalid JSON response" -ForegroundColor Red
            exit 1
        }
        [System.IO.File]::WriteAllText($configDest, $response.Content, [System.Text.UTF8Encoding]::new($false))
    }

    # Resolve and pin version
    Write-Host "Resolving latest proxy agent version..."
    $version = Get-LatestVersion
    Set-Content -LiteralPath (Join-Path $InstallDir 'version') -Value $version -Encoding ascii
    Write-Host "Using version $version"

    # Clear credential from environment before spawning child process
    Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue

    # Start agent
    & $agentScript start
    if (-not $?) {
        Write-Host "Installation complete, but agent failed to start."
        Write-Host "To try to start the agent again, run: & '$agentScript' start"
        exit 1
    }

    Write-Host "`nInstallation complete."
    Write-Host "Install directory: $InstallDir"
} finally {
    Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue
}
