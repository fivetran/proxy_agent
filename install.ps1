#Requires -Version 5.1
param(
    [Parameter(Position = 0)]
    [string]$ConfigPath,
    [string]$InstallDir,
    [ValidateSet('ltsc2019', 'ltsc2022', 'ltsc2025')]
    [string]$WindowsVersion
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Windows PowerShell 5.1 uses WebRequest.DefaultWebProxy; PowerShell 7 uses
# HttpClient.DefaultProxy. Apply proxy settings to the one used by each version.
# Without environment variables, keep the system proxy and its credentials.
# Docker has separate proxy settings; see the README.
function Set-WebProxyFromEnvironment {
    param([Parameter(Mandatory)][Uri]$ProbeUri)

    $proxyUrl = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { $null }
    if (-not $proxyUrl) {
        $systemProxy = if ($PSVersionTable.PSVersion.Major -ge 7) {
            [System.Net.Http.HttpClient]::DefaultProxy
        } else {
            [System.Net.WebRequest]::DefaultWebProxy
        }
        if ($systemProxy) {
            if (-not $systemProxy.Credentials) {
                $systemProxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
            }
            $effectiveProxy = $systemProxy.GetProxy($ProbeUri)
            # Direct connections come back as the probe URI on .NET Framework and as null on .NET Core.
            if ($effectiveProxy -and $effectiveProxy -ne $ProbeUri) {
                Write-Host "Using system proxy $($effectiveProxy.Scheme)://$($effectiveProxy.Authority)"
            }
        }
        return
    }

    try {
        $proxyUri = [Uri]$proxyUrl
    } catch {
        Write-Host "ERROR: Invalid proxy URL in HTTPS_PROXY/HTTP_PROXY" -ForegroundColor Red
        exit 1
    }

    # .NET bypass entries are regular expressions matched against the request URL. As with curl,
    # each entry matches the host and its subdomains, with or without a leading dot. CIDR ranges aren't supported.
    $bypassList = @()
    if ($env:NO_PROXY) {
        foreach ($entry in ($env:NO_PROXY -split ',')) {
            $bypassHost = $entry.Trim().TrimStart('.')
            if (-not $bypassHost) { continue }
            if ($bypassHost -eq '*') {
                $bypassList = @('.*')
                break
            }
            $bypassList += '^https?://([^/]+\.)?' + [regex]::Escape($bypassHost) + '(:\d+)?(/|$)'
        }
    }

    # Keep credentials out of the proxy address; pass them separately.
    $proxyAddress = [Uri]"$($proxyUri.Scheme)://$($proxyUri.Authority)"
    $webProxy = New-Object System.Net.WebProxy($proxyAddress, $false, [string[]]$bypassList)
    if ($proxyUri.UserInfo) {
        $userInfo = $proxyUri.UserInfo.Split(':', 2)
        $proxyUser     = [Uri]::UnescapeDataString($userInfo[0])
        $proxyPassword = if ($userInfo.Length -gt 1) { [Uri]::UnescapeDataString($userInfo[1]) } else { '' }
        $webProxy.Credentials = New-Object System.Net.NetworkCredential($proxyUser, $proxyPassword)
    } else {
        $webProxy.UseDefaultCredentials = $true
    }
    [System.Net.WebRequest]::DefaultWebProxy = $webProxy
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        [System.Net.Http.HttpClient]::DefaultProxy = $webProxy
    }
    Write-Host "Using proxy $proxyAddress from the environment"
}

$binary = $env:PROXY_AGENT_MANAGER_BIN
$scratch = $null
$checksum = $null
$previousBinary = $env:PROXY_AGENT_INSTALLER_BINARY
$previousPowerShell = $env:PROXY_AGENT_INSTALLER_POWERSHELL
try {
    if (-not $binary) {
        $release = 'https://github.com/fivetran/proxy_agent/releases/download/manager-v0.1.0'
        $url = $env:PROXY_AGENT_MANAGER_BINARY_URL
        $expectedHash = $env:PROXY_AGENT_MANAGER_BINARY_SHA256
        if (($url -or $expectedHash) -and (-not $url -or $expectedHash -notmatch '^[0-9a-f]{64}$')) {
            throw 'Native manager download requires a URL and its SHA-256 digest.'
        }
        if (-not $url) { $url = "$release/manager-windows-x64.exe" }

        $scratch = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
        $null = [IO.Directory]::CreateDirectory($scratch)
        $binary = Join-Path $scratch 'manager.exe'
        try {
            $uri = [Uri]$url
            Set-WebProxyFromEnvironment -ProbeUri $uri
            if (-not $expectedHash) {
                $checksum = Join-Path $scratch 'manager.sha256'
                Invoke-WebRequest -Uri "$url.sha256" -OutFile $checksum -UseBasicParsing -TimeoutSec 30
                $expectedHash = [IO.File]::ReadAllText($checksum).Trim()
            }
            Invoke-WebRequest -Uri $uri -OutFile $binary -UseBasicParsing -TimeoutSec 120
        } catch {
            throw 'Failed to download the native manager or its SHA-256 digest.'
        }
        if ($expectedHash -notmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $binary).Hash -ne $expectedHash) {
            throw 'Native manager SHA-256 does not match.'
        }
        Unblock-File -LiteralPath $binary
    }

    if (-not [IO.File]::Exists($binary)) {
        $binary = (Get-Command -Name $binary -CommandType Application -ErrorAction Stop).Path
    }
    $binary = (Resolve-Path -LiteralPath $binary).ProviderPath
    $env:PROXY_AGENT_INSTALLER_BINARY = $binary
    $program = if ($PSVersionTable.PSVersion.Major -ge 7) { 'pwsh.exe' } else { 'powershell.exe' }
    $env:PROXY_AGENT_INSTALLER_POWERSHELL = Join-Path $PSHOME $program
    $nativeArguments = @('install')
    if ($ConfigPath) { $nativeArguments += $ConfigPath }
    if ($InstallDir) { $nativeArguments += @('--install-dir', $InstallDir) }
    if ($WindowsVersion) { $nativeArguments += @('--windows-version', $WindowsVersion) }
    & $binary @nativeArguments
    exit $LASTEXITCODE
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    exit 1
} finally {
    $env:PROXY_AGENT_INSTALLER_BINARY = $previousBinary
    $env:PROXY_AGENT_INSTALLER_POWERSHELL = $previousPowerShell
    Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue
    if ($scratch) {
        Remove-Item -LiteralPath $binary -Force -ErrorAction SilentlyContinue
        if ($checksum) {
            Remove-Item -LiteralPath $checksum -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $scratch -Force -ErrorAction SilentlyContinue
    }
}
