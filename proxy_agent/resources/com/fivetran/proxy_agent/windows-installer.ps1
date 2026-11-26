$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# Windows PowerShell 5.1 uses WebRequest.DefaultWebProxy; PowerShell 7 uses
# HttpClient.DefaultProxy. Apply proxy settings to the one used by each version.
# Without environment variables, keep the system proxy and its credentials.
# Docker has separate proxy settings; see the README.
function Set-WebProxyFromEnvironment {
    $proxyUrl = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { $null }
    if (-not $proxyUrl) {
        $systemProxy = if ($PSVersionTable.PSVersion.Major -ge 7) {
            [System.Net.Http.HttpClient]::DefaultProxy
        } else {
            [System.Net.WebRequest]::DefaultWebProxy
        }
        if ($systemProxy -and -not $systemProxy.Credentials) {
            $systemProxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
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
}
