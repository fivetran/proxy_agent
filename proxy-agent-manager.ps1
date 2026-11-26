#Requires -Version 5.1

$ErrorActionPreference = 'Stop'
$BASE_DIR = $PSScriptRoot

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

function Install-NativeManager {
    param([switch]$RequireUpdater)
    $url = $env:PROXY_AGENT_MANAGER_BINARY_URL
    $expected = $env:PROXY_AGENT_MANAGER_BINARY_SHA256
    $candidate = Join-Path $BASE_DIR ('.native-manager.' + [guid]::NewGuid() + '.exe')
    $checksum = "$candidate.sha256"
    try {
        if ($url -or $expected) {
            if (-not $url -or $expected -notmatch '^[0-9a-f]{64}$') {
                throw 'A manager URL and SHA-256 digest are required together.'
            }
        } else {
            $url = 'https://github.com/fivetran/proxy_agent/releases/latest/download/manager-windows-x64.exe'
        }
        Set-WebProxyFromEnvironment -ProbeUri ([Uri]$url)
        if (-not $expected) {
            Invoke-WebRequest -Uri "$url.sha256" -OutFile $checksum -UseBasicParsing -TimeoutSec 30
            $expected = [IO.File]::ReadAllText($checksum).Trim()
        }
        if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid manager SHA-256 digest.' }
        Invoke-WebRequest -Uri $url -OutFile $candidate -UseBasicParsing -TimeoutSec 120
        if ((Get-FileHash -LiteralPath $candidate).Hash -ne $expected) {
            throw 'Native manager SHA-256 does not match.'
        }
        Unblock-File -LiteralPath $candidate
        if ($RequireUpdater) {
            & $candidate update-manager --help *> $null
            if ($LASTEXITCODE -ne 0) { throw 'The release does not support update-manager.' }
        }
        $destination = Join-Path $BASE_DIR 'manager.exe'
        if ([IO.File]::Exists($destination)) {
            [IO.File]::Replace($candidate, $destination, [NullString]::Value)
        } else {
            [IO.File]::Move($candidate, $destination)
        }
        Write-Host 'Native manager installed.'
        return $true
    } catch {
        Write-Warning 'Could not install a verified native manager.'
        return $false
    } finally {
        Remove-Item -LiteralPath $candidate, $checksum -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-NativeManager {
    $manager = Join-Path $BASE_DIR 'manager.exe'
    if (-not (Test-Path -LiteralPath $manager -PathType Leaf) -and -not (Install-NativeManager)) {
        exit 1
    }
    # An early native installer may predate the update-manager command.
    if ($args.Count -and $args[0] -eq 'update-manager') {
        & $manager update-manager --help *> $null
        if ($LASTEXITCODE -ne 0 -and -not (Install-NativeManager -RequireUpdater)) {
            exit 1
        }
    }
    & $manager @args
    exit $LASTEXITCODE
}

Invoke-NativeManager @args
