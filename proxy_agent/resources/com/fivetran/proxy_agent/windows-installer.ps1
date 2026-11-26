$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

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
                [Console]::Error.WriteLine("Using system proxy $($effectiveProxy.Scheme)://$($effectiveProxy.Authority)")
            }
        }
        return
    }

    try {
        $proxyUri = [Uri]$proxyUrl
    } catch {
        throw 'Invalid proxy URL in HTTPS_PROXY/HTTP_PROXY'
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
    [Console]::Error.WriteLine("Using proxy $proxyAddress from the environment")
}

$operation = $env:PROXY_AGENT_INSTALL_OPERATION
$argument = $env:PROXY_AGENT_INSTALL_ARGUMENT
$value = $env:PROXY_AGENT_INSTALL_VALUE

try {
    if ($operation -in @('configure', 'download', 'tags')) {
        Set-WebProxyFromEnvironment -ProbeUri ([Uri]$argument)
    }

    switch ($operation) {
        'host' {
            $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
            $principal = [Security.Principal.WindowsPrincipal]::new($identity)
            [Console]::WriteLine($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
            try {
                [Console]::WriteLine([math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB))
            } catch {
                # The installer reports unavailable host memory while continuing the preflight checks.
            }
        }
        'os' {
            $os = Get-CimInstance Win32_OperatingSystem
            [Console]::WriteLine($os.ProductType)
            [Console]::WriteLine($os.BuildNumber)
            [Console]::WriteLine($os.Caption)
        }
        'disk' {
            $qualifier = Split-Path -Qualifier $argument
            $drive = Get-PSDrive ($qualifier.TrimEnd(':'))
            [Console]::WriteLine([math]::Round($drive.Free / 1MB))
        }
        'legacy-token' {
            $config = Get-Content -LiteralPath $argument -Raw | ConvertFrom-Json
            if (-not $config.agent_id -or -not $config.auth_token) { exit 1 }
            $credential = "$($config.agent_id):$($config.auth_token)"
            [Console]::Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential)))
        }
        'protect-config' {
            $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
            $acl = [Security.AccessControl.FileSecurity]::new()
            $acl.SetAccessRuleProtection($true, $false)
            $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($user, 'Read,Write', 'Allow')))
            $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule('NT AUTHORITY\SYSTEM', 'Read', 'Allow')))
            $admins = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
            $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($admins, 'Read', 'Allow')))
            Set-Acl -LiteralPath $argument -AclObject $acl
        }
        'download' {
            Invoke-WebRequest -Uri $argument -OutFile $value -TimeoutSec 30 -UseBasicParsing
            Unblock-File -LiteralPath $value
        }
        'tags' {
            $response = Invoke-RestMethod -Uri $argument -Method Get -TimeoutSec 30
            foreach ($tag in $response.tags) { [Console]::WriteLine($tag) }
        }
        'configure' {
            try {
                $response = Invoke-WebRequest -Uri $argument -Method Post -TimeoutSec 30 -UseBasicParsing `
                    -Headers @{ Authorization = "Basic $value"; Accept = 'application/json' }
            } catch {
                if (-not $_.Exception.Response) { exit 1 }
                [Console]::WriteLine([int]$_.Exception.Response.StatusCode)
                exit 0
            }
            try {
                $null = $response.Content | ConvertFrom-Json
            } catch {
                exit 2
            }
            [Console]::WriteLine($response.StatusCode)
            [Console]::Write($response.Content)
        }
        default { exit 1 }
    }
} catch {
    # HTTP exceptions can carry credential-bearing URLs or response bodies.
    exit 1
}
