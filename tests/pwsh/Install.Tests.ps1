#Requires -Modules @{ ModuleName = 'Pester'; ModuleVersion = '5.0' }
<#
.SYNOPSIS
Unit tests for install.ps1.

.DESCRIPTION
The installer is run as a black box with docker, Invoke-RestMethod,
Invoke-WebRequest, Get-CimInstance and Get-PSDrive replaced by functions, so no
Docker daemon or network access is needed. See Manager.Tests.ps1 for why the
stubs shadow the real commands and keep their state in $global:ProxyAgentStub.

The management script the installer downloads is replaced by one that only
records that it was started.
#>
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidGlobalVars', '', Justification = 'Stub state must be global; see Manager.Tests.ps1.')]
param()

BeforeAll {
    $script:Installer = (Resolve-Path (Join-Path $PSScriptRoot '..\..\install.ps1')).Path
    $script:WindowsServer2022 = [pscustomobject]@{ ProductType = 3; BuildNumber = 20348; Caption = 'Windows Server 2022' }

    # Creates a config file and an install directory path under TestDrive, and
    # returns the arguments to run the installer with.
    function New-InstallArgs {
        $dir = Join-Path $TestDrive ([guid]::NewGuid())
        $null = New-Item -ItemType Directory -Force -Path $dir
        $config = Join-Path $dir 'config.json'
        Set-Content -LiteralPath $config -Value '{"agent_id": "abc123"}' -Encoding ascii
        $script:InstallDir = Join-Path $dir 'fivetran-proxy-agent'
        return @{ ConfigPath = $config; InstallDir = $script:InstallDir }
    }

    # Runs the installer, capturing every output stream as one string. The width
    # keeps Windows PowerShell 5.1 from wrapping long lines, such as paths, at
    # the console width.
    function Invoke-Installer {
        param([hashtable]$Arguments)
        return (& $script:Installer @Arguments *>&1 | Out-String -Width 4096)
    }

    function Get-VersionFile {
        return (Get-Content -LiteralPath (Join-Path $script:InstallDir 'version') -Raw).Trim()
    }

    function Test-VersionFile {
        return Test-Path -LiteralPath (Join-Path $script:InstallDir 'version')
    }

    # Stand-in for the docker CLI, covering the calls the installer makes.
    function docker {
        $stub = $global:ProxyAgentStub
        $global:LASTEXITCODE = 0
        if ($args[0] -eq '--version') { return 'Docker version 27.0.0, build 0000000' }
        if ($args[0] -eq 'info' -and $args[1] -eq '--format') {
            switch ($args[2]) {
                '{{.OSType}}' { return $stub.OsType }
                '{{.DockerRootDir}}' {
                    if (-not $stub.DockerRootDir) { $global:LASTEXITCODE = 1 }
                    return $stub.DockerRootDir
                }
            }
        }
    }

    # Stand-in for the registry tag listing.
    function Invoke-RestMethod {
        return [pscustomobject]@{ tags = $global:ProxyAgentStub.Tags }
    }

    # Stand-in for the management script download. The downloaded script only
    # records that the installer started it. The arguments other than OutFile
    # are left unbound, in $args.
    function Invoke-WebRequest {
        param([string]$OutFile)
        Set-Content -LiteralPath $OutFile -Value '$global:ProxyAgentStub.ManagerArgs = $args' -Encoding ascii
    }

    # Stand-in for the host lookups: the OS from the stub state, and a host
    # with enough memory not to warn.
    function Get-CimInstance {
        [CmdletBinding()]
        param([Parameter(Position = 0)][string]$ClassName)
        if ($ClassName -eq 'Win32_OperatingSystem') { return $global:ProxyAgentStub.OperatingSystem }
        return [pscustomobject]@{ TotalPhysicalMemory = 64GB }
    }

    # Stand-in for the free space lookup. Records the drives queried and reports
    # FreeMB for a drive, or plenty of space for any other.
    function Get-PSDrive {
        param([Parameter(Position = 0)][string]$Name)
        $stub = $global:ProxyAgentStub
        $stub.DrivesQueried += $Name
        $freeMB = if ($stub.FreeMB.ContainsKey($Name)) { $stub.FreeMB[$Name] } else { 100000 }
        return [pscustomobject]@{ Name = $Name; Free = $freeMB * 1MB }
    }
}

AfterAll {
    Remove-Variable -Name ProxyAgentStub -Scope Global -ErrorAction SilentlyContinue
}

Describe 'install.ps1' {
    BeforeEach {
        $env:RUNTIME = 'docker'
        $env:MEMORY_ALLOCATION_MB = '5120'
        Remove-Item Env:\TOKEN -ErrorAction SilentlyContinue
        $global:ProxyAgentStub = @{
            OsType          = 'windows'
            DockerRootDir   = $null
            OperatingSystem = $script:WindowsServer2022
            ManagerArgs     = $null
            DrivesQueried   = @()
            FreeMB          = @{}
            Tags            = @(
                '1.2.9-windows-ltsc2022', '1.2.10-windows-ltsc2022', '1.2.11-windows-ltsc2025',
                '1.2.12-ubuntu-26.04', '1.2.13'
            )
        }
    }

    AfterEach {
        Remove-Item Env:\RUNTIME, Env:\MEMORY_ALLOCATION_MB -ErrorAction SilentlyContinue
    }

    Context 'with Windows containers' {
        It 'pins the newest tag for the LTSC version detected from the host and starts the agent' {
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 0
            $output | Should -Match 'Using Windows image variant ltsc2022'
            Get-VersionFile | Should -Be '1.2.10'
            $global:ProxyAgentStub.ManagerArgs | Should -Be @('start')
        }

        It 'pins the ltsc2019 tag on Windows Server 2019' {
            $global:ProxyAgentStub.OperatingSystem = [pscustomobject]@{ ProductType = 3; BuildNumber = 17763; Caption = 'Windows Server 2019' }
            $global:ProxyAgentStub.Tags += '1.2.8-windows-ltsc2019'
            $null = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 0
            Get-VersionFile | Should -Be '1.2.8'
        }

        It 'normalizes -WindowsVersion and uses it instead of detecting the host, even on an unsupported build' {
            $global:ProxyAgentStub.OperatingSystem = [pscustomobject]@{ ProductType = 3; BuildNumber = 99999; Caption = 'Windows Server vNext' }
            $arguments = New-InstallArgs
            $arguments.WindowsVersion = 'LTSC2025'
            $output = Invoke-Installer $arguments
            $LASTEXITCODE | Should -Be 0
            Get-VersionFile | Should -Be '1.2.11'
            $output | Should -Match 'Using Windows image variant ltsc2025'
            Get-Content -LiteralPath (Join-Path $script:InstallDir 'settings.ps1') -Raw |
                Should -Match ([regex]::Escape("`$WINDOWS_VERSION = 'ltsc2025'"))
        }

        It 'fails on an unsupported Windows Server build' {
            $global:ProxyAgentStub.OperatingSystem = [pscustomobject]@{ ProductType = 3; BuildNumber = 99999; Caption = 'Windows Server vNext' }
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match "Unsupported Windows Server build '99999'"
            Test-VersionFile | Should -BeFalse
        }

        It 'fails on a Windows client host' {
            $global:ProxyAgentStub.OperatingSystem = [pscustomobject]@{ ProductType = 1; BuildNumber = 26100; Caption = 'Windows 11 Pro' }
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match 'Windows Server is required'
            Test-VersionFile | Should -BeFalse
        }

        It 'fails when no tags are published for the LTSC version' {
            $global:ProxyAgentStub.Tags = @('1.2.11-windows-ltsc2025', '1.2.12-ubuntu-26.04')
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match 'No published Windows image tags found for ltsc2022'
            $global:ProxyAgentStub.ManagerArgs | Should -BeNullOrEmpty
        }

        It "checks Docker's data root drive against the Windows disk space minimum" {
            $null = New-Item -ItemType Directory -Force -Path 'TestDrive:\docker'
            $global:ProxyAgentStub.DockerRootDir = 'TestDrive:\docker'
            $global:ProxyAgentStub.FreeMB['TestDrive'] = 10000
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.DrivesQueried | Should -Be @('TestDrive')
            $output | Should -Match 'drive TestDrive: \(10000MB\) is below the recommended minimum of 12288MB'
        }

        It "checks a data root that is a drive root" {
            $global:ProxyAgentStub.DockerRootDir = 'TestDrive:\'
            $global:ProxyAgentStub.FreeMB['TestDrive'] = 10000
            $output = Invoke-Installer (New-InstallArgs)
            $output | Should -Match 'drive TestDrive: \(10000MB\) is below the recommended minimum of 12288MB'
            $output | Should -Not -Match 'does not exist'
        }

        It "does not warn when Docker's data root has enough space" {
            $global:ProxyAgentStub.DockerRootDir = 'TestDrive:\'
            $output = Invoke-Installer (New-InstallArgs)
            $output | Should -Not -Match 'disk space'
        }

        It "falls back to the install directory's drive when Docker's data root is unknown" {
            $arguments = New-InstallArgs
            $installDrive = (Split-Path -Qualifier $arguments.InstallDir).TrimEnd(':')
            $global:ProxyAgentStub.FreeMB[$installDrive] = 10000
            $output = Invoke-Installer $arguments
            $LASTEXITCODE | Should -Be 0
            $output | Should -Match "Unable to determine Docker's data root"
            $output | Should -Match "drive ${installDrive}: \(10000MB\) is below the recommended minimum of 12288MB"
        }
    }

    It 'restricts config.json to the installing user, SYSTEM and Administrators' {
        $null = Invoke-Installer (New-InstallArgs)
        $LASTEXITCODE | Should -Be 0
        $acl = Get-Acl -LiteralPath (Join-Path $script:InstallDir 'config\config.json')
        $acl.AreAccessRulesProtected | Should -BeTrue
        $identities = $acl.Access | ForEach-Object { $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value }
        $expected = @(
            [Security.Principal.WindowsIdentity]::GetCurrent().User.Value,
            'S-1-5-18',      # SYSTEM
            'S-1-5-32-544'   # Administrators
        )
        ($identities | Sort-Object) | Should -Be ($expected | Sort-Object)
    }

    Context 'with Linux containers' {
        BeforeEach {
            $global:ProxyAgentStub.OsType = 'linux'
        }

        It 'pins the newest Ubuntu tag and ignores Windows and bare tags' {
            $output = Invoke-Installer (New-InstallArgs)
            $LASTEXITCODE | Should -Be 0
            $output | Should -Match 'Using Linux container image'
            Get-VersionFile | Should -Be '1.2.12'
            $global:ProxyAgentStub.ManagerArgs | Should -Be @('start')
        }

        It 'rejects -WindowsVersion' {
            $arguments = New-InstallArgs
            $arguments.WindowsVersion = 'ltsc2022'
            $output = Invoke-Installer $arguments
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match '-WindowsVersion can only be used when Docker is running Windows containers'
            Test-VersionFile | Should -BeFalse
        }

        It "checks the install directory's drive against the Linux disk space minimum" {
            $arguments = New-InstallArgs
            $installDrive = (Split-Path -Qualifier $arguments.InstallDir).TrimEnd(':')
            $global:ProxyAgentStub.FreeMB[$installDrive] = 1000
            $output = Invoke-Installer $arguments
            $LASTEXITCODE | Should -Be 0
            $output | Should -Match "drive ${installDrive}: \(1000MB\) is below the recommended minimum of 2048MB"
            $output | Should -Not -Match "Docker's data root"
        }
    }
}
