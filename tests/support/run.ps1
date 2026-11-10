#Requires -Version 5.1
<#
.SYNOPSIS
Bridges Python fixtures to PowerShell cmdlets and Windows objects.
.DESCRIPTION
Python owns the fixtures, arguments and assertions. This disposable child
process shadows external commands, invokes the real script, and exports only
the Windows state requested by the test. Docker keeps native exit semantics;
failures in Python mocks are test infrastructure errors.
#>
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidGlobalVars', '', Justification = 'Command stubs must expose native exit codes to the script under test.')]
param([Parameter(Mandatory)][string]$RequestPath)
$ErrorActionPreference = 'Stop'
$request = Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json
$global:LASTEXITCODE = 0
$stubState = @{ Error = $null }
# PowerShell 5.1 defaults to ASCII when piping text to a native process.
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Invoke-TestStub {
    param([string]$Name, [object[]]$Arguments)
    try {
        $payload = ConvertTo-Json -InputObject (@($Name) + @($Arguments)) -Depth 10 -Compress
        $response = $payload | & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB powershell-json
        if ($LASTEXITCODE -ne 0) { throw "Test stub $Name failed (exit $LASTEXITCODE): $response" }
        if (-not $response) { throw "Test stub $Name returned no JSON." }
        return ($response | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        # The script may catch command failures; a broken test fixture must still fail the test.
        $stubState.Error = "$_"
        throw
    }
}

function docker {
    $payload = ConvertTo-Json -InputObject @($args) -Compress
    $payload | & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB docker-json
    $global:LASTEXITCODE = $LASTEXITCODE
}

if ($request.stub_http) {
    function Invoke-RestMethod { Invoke-TestStub -Name 'Invoke-RestMethod' -Arguments $args }
    function Invoke-WebRequest { Invoke-TestStub -Name 'Invoke-WebRequest' -Arguments $args }
}
function Get-CimInstance { Invoke-TestStub -Name 'Get-CimInstance' -Arguments $args }
function Get-PSDrive { Invoke-TestStub -Name 'Get-PSDrive' -Arguments $args }

$systemProxy = New-Object System.Net.WebProxy
if ($request.system_proxy) { $systemProxy = New-Object System.Net.WebProxy($request.system_proxy) }
if ($request.system_credentials) {
    $systemProxy.Credentials = New-Object System.Net.NetworkCredential('proxy-user', 'proxy-password')
}
$originalCredentials = $systemProxy.Credentials
[System.Net.WebRequest]::DefaultWebProxy = $systemProxy
if ($PSVersionTable.PSVersion.Major -ge 7) { [System.Net.Http.HttpClient]::DefaultProxy = $systemProxy }

$code = 0
try {
    $parameters = @{}
    foreach ($property in $request.parameters.PSObject.Properties) { $parameters[$property.Name] = $property.Value }
    $arguments = @($request.arguments)
    & $request.script @arguments @parameters
    $code = $LASTEXITCODE
} catch {
    Write-Output "$_"
    $code = 1
}
if ($stubState.Error) { Write-Output $stubState.Error; $code = 1 }

$snapshot = @{}
if ($request.observe_proxy) {
    $proxy = [System.Net.WebRequest]::DefaultWebProxy
    $bypass = @{}
    foreach ($uri in $request.proxy_probes) {
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
    }
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $snapshot.http_client_proxy = [object]::ReferenceEquals($proxy, [System.Net.Http.HttpClient]::DefaultProxy)
    }
}
if ($request.config_path -and (Test-Path -LiteralPath $request.config_path)) {
    $acl = Get-Acl -LiteralPath $request.config_path
    $snapshot.administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $snapshot.acl_protected = $acl.AreAccessRulesProtected
    $snapshot.acl = @($acl.Access | ForEach-Object {
        @{ sid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
           rights = [string]$_.FileSystemRights; type = [string]$_.AccessControlType }
    })
    $snapshot.user_sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
}
$snapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $request.snapshot_path -Encoding UTF8
exit $code
