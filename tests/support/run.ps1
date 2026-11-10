#Requires -Version 5.1
<#
.SYNOPSIS
Runs a script with stubbed external commands and exports observable Windows state.
.DESCRIPTION
Assertions live in Python. This adapter only supplies commands that cannot be
replaced through PATH (PowerShell cmdlets) and observes proxy settings and ACLs.
#>
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidGlobalVars', '', Justification = 'Command stubs must expose native exit codes to the script under test.')]
param([string]$RequestPath)
$ErrorActionPreference = 'Stop'
$request = Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json
$options = Get-Content -LiteralPath (Join-Path $env:AGENT_TEST_ROOT 'options.json') -Raw | ConvertFrom-Json
$global:LASTEXITCODE = 0

function docker {
    & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB docker @args
    $global:LASTEXITCODE = $LASTEXITCODE
}

function Invoke-RestMethod {
    param([string]$Uri, [string]$Method, [int]$TimeoutSec)
    & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB request $Uri $Method $TimeoutSec | Out-Null
    return [pscustomobject]@{ tags = $options.tags }
}

function Invoke-WebRequest {
    # Unused web-request options remain unbound in $args, as in the old stub.
    param([string]$Uri, [string]$OutFile, [string]$Method, [int]$TimeoutSec)
    & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB request $Uri $Method $TimeoutSec | Out-Null
    if ($OutFile) {
        Set-Content -LiteralPath $OutFile -Encoding ascii -Value '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB manager @args'
    } else {
        return [pscustomobject]@{ StatusCode = 200; Content = '{"agent_id":"abc123"}' }
    }
}

function Get-CimInstance {
    [CmdletBinding()]
    param([Parameter(Position = 0)][string]$ClassName)
    if ($ClassName -eq 'Win32_OperatingSystem') {
        return [pscustomobject]@{ ProductType = $options.product_type; BuildNumber = $options.build; Caption = 'Stub Windows' }
    }
    return [pscustomobject]@{ TotalPhysicalMemory = 64GB; NumberOfLogicalProcessors = 8 }
}

function Get-PSDrive {
    param([Parameter(Position = 0)][string]$Name)
    & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB drive $Name | Out-Null
    return [pscustomobject]@{ Name = $Name; Free = $options.free_mb * 1MB }
}

$systemProxy = New-Object System.Net.WebProxy
if ($options.system_proxy) { $systemProxy = New-Object System.Net.WebProxy($options.system_proxy) }
if ($options.system_credentials) {
    $systemProxy.Credentials = New-Object System.Net.NetworkCredential('proxy-user', 'proxy-password')
}
$originalCredentials = $systemProxy.Credentials
[System.Net.WebRequest]::DefaultWebProxy = $systemProxy
if ($PSVersionTable.PSVersion.Major -ge 7) { [System.Net.Http.HttpClient]::DefaultProxy = $systemProxy }

$code = 0
try {
    if ($request.kind -eq 'install') {
        $parameters = @{}
        foreach ($property in $request.parameters.PSObject.Properties) { $parameters[$property.Name] = $property.Value }
        & $request.script @parameters
    } else {
        $arguments = @($request.arguments)
        $commonParameters = @{}
        if ($arguments -contains '-Verbose') {
            $commonParameters.Verbose = $true
            $arguments = @($arguments | Where-Object { $_ -ne '-Verbose' })
        }
        & $request.script @arguments @commonParameters
    }
    $code = $LASTEXITCODE
} catch {
    Write-Output $_
    $code = 1
} finally {
    $proxy = [System.Net.WebRequest]::DefaultWebProxy
    $bypass = @{}
    foreach ($uri in @('https://us-docker.pkg.dev/v2/tags/list', 'http://localhost:8080/', 'https://corp.example.com/', 'https://db.corp.example.com:5432/', 'https://notcorp.example.com/')) {
        $bypass[$uri] = $proxy.IsBypassed([Uri]$uri)
    }
    $snapshot = @{
        proxy_address = [string]$proxy.Address
        proxy_user = $proxy.Credentials.UserName
        proxy_password = $proxy.Credentials.Password
        default_credentials = $proxy.UseDefaultCredentials
        same_system_proxy = [object]::ReferenceEquals($systemProxy, $proxy)
        same_credentials = [object]::ReferenceEquals($originalCredentials, $proxy.Credentials)
        bypass = $bypass
        administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    }
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $snapshot.http_client_proxy = [object]::ReferenceEquals($proxy, [System.Net.Http.HttpClient]::DefaultProxy)
    }
    $config = Join-Path $env:AGENT_TEST_ROOT 'installed\config\config.json'
    if (Test-Path -LiteralPath $config) {
        $acl = Get-Acl -LiteralPath $config
        $snapshot.acl_protected = $acl.AreAccessRulesProtected
        $snapshot.acl = @($acl.Access | ForEach-Object {
            @{ sid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
               rights = [string]$_.FileSystemRights; type = [string]$_.AccessControlType }
        })
        $snapshot.user_sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    }
    $snapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $env:AGENT_TEST_ROOT 'snapshot.json') -Encoding UTF8
}
exit $code
