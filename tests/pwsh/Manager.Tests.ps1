#Requires -Modules @{ ModuleName = 'Pester'; ModuleVersion = '5.0' }
<#
.SYNOPSIS
Unit tests for proxy-agent-manager.ps1.

.DESCRIPTION
The script is run as a black box with docker, Invoke-RestMethod and
Get-CimInstance replaced by functions, so no Docker daemon is needed. Functions
defined in BeforeAll shadow the real commands for the invoked script because it
runs in a child scope.

The stubs keep their state in $global:ProxyAgentStub. PowerShell scopes
dynamically, so inside a stub called from the manager script $script: refers to
the manager's scope, not this file's; only a global is visible to both.
#>

BeforeAll {
    $script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:Image = 'us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent'

    # Creates an installation directory with the management script, a config
    # file and the given version file content, and returns the manager's path.
    function New-AgentDir {
        param(
            [string]$Version = '1.2.9-ubuntu-26.04',
            [string]$Settings
        )
        $dir = Join-Path ([System.IO.Path]::GetTempPath()) ("proxy-agent-test-" + [guid]::NewGuid())
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $dir 'config')
        Copy-Item (Join-Path $script:RepoRoot 'proxy-agent-manager.ps1') $dir
        Set-Content -LiteralPath (Join-Path $dir 'config\config.json') -Value '{"agent_id": "abc123"}' -Encoding ascii
        Set-Content -LiteralPath (Join-Path $dir 'version') -Value $Version -Encoding ascii
        if ($Settings) {
            Set-Content -LiteralPath (Join-Path $dir 'settings.ps1') -Value $Settings -Encoding ascii
        }
        $script:AgentDir = $dir
        return Join-Path $dir 'proxy-agent-manager.ps1'
    }

    function Get-VersionFile {
        return (Get-Content -LiteralPath (Join-Path $script:AgentDir 'version') -Raw).Trim()
    }

    # Runs the manager, capturing every output stream as one string.
    function Invoke-Manager {
        param([string]$Manager, [string]$Command)
        return (& $Manager $Command *>&1 | Out-String)
    }

    function Get-DockerRunArgs {
        return ($global:ProxyAgentStub.DockerCalls | Where-Object { $_[0] -eq 'run' })[0]
    }

    # Stand-in for the docker CLI. Records every call and keeps one container's
    # worth of state: LastImage is $null while no container "runs" and
    # otherwise holds the image it was started from.
    function docker {
        $stub = $global:ProxyAgentStub
        $stub.DockerCalls += ,@($args)
        $global:LASTEXITCODE = 0
        switch ($args[0]) {
            'info' { return $stub.OsType }
            'run' {
                $stub.LastImage = $args | Where-Object { $_ -like "*proxy-agent:*" } | Select-Object -First 1
                return 'container-id'
            }
            'inspect' {
                if (-not $stub.LastImage) {
                    $global:LASTEXITCODE = 1
                    return
                }
                if ($args[1] -eq '-f') {
                    if ($stub.UnhealthyTag -and $stub.LastImage -like "*$($stub.UnhealthyTag)*") { return 'unhealthy' }
                    return 'healthy'
                }
            }
            { $_ -in 'stop', 'rm' } { $stub.LastImage = $null }
            'ps' { return 'NAMES IMAGE STATUS' }
        }
    }

    # Stand-in for the registry tag listing.
    function Invoke-RestMethod {
        return [pscustomobject]@{ tags = $global:ProxyAgentStub.Tags }
    }

    # Stand-in for the host OS lookup, reporting Windows Server 2022.
    function Get-CimInstance {
        return [pscustomobject]@{ ProductType = 3; BuildNumber = 20348; Caption = 'Windows Server 2022' }
    }
}

AfterAll {
    Remove-Variable -Name ProxyAgentStub -Scope Global -ErrorAction SilentlyContinue
}

Describe 'proxy-agent-manager.ps1' {
    BeforeEach {
        $global:ProxyAgentStub = @{
            OsType       = 'linux'
            LastImage    = $null
            UnhealthyTag = $null
            DockerCalls  = @()
            Tags         = @('1.2.9-ubuntu-26.04', '1.2.10-ubuntu-26.04', '1.2.11', '1.2.10-windows-ltsc2022', '1.2.10-windows-ltsc2025')
        }
    }

    AfterEach {
        if ($script:AgentDir -and (Test-Path -LiteralPath $script:AgentDir)) {
            Remove-Item -LiteralPath $script:AgentDir -Recurse -Force
        }
    }

    Context 'with Linux containers' {
        It 'starts the pinned Ubuntu image with the default memory limit' {
            $manager = New-AgentDir
            $null = Invoke-Manager $manager start
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.9-ubuntu-26.04"
            Get-DockerRunArgs | Should -Contain '--memory=5120m'
        }

        It 'honors MEMORY_ALLOCATION_MB from settings.ps1' {
            $manager = New-AgentDir -Settings '$MEMORY_ALLOCATION_MB = 2048'
            $null = Invoke-Manager $manager start
            Get-DockerRunArgs | Should -Contain '--memory=2048m'
        }

        It 'labels the container with the agent id' {
            $manager = New-AgentDir
            $null = Invoke-Manager $manager start
            $runArgs = Get-DockerRunArgs
            $runArgs | Should -Contain 'proxy-agent-abc123'
            $runArgs | Should -Contain 'proxy_agent_id=abc123'
        }

        It 'fails when the config file is missing' {
            $manager = New-AgentDir
            Remove-Item -LiteralPath (Join-Path $script:AgentDir 'config\config.json')
            $output = Invoke-Manager $manager start
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match 'Config file not found'
        }

        It 'fails when the version file is empty' {
            $manager = New-AgentDir -Version ''
            $output = Invoke-Manager $manager start
            $LASTEXITCODE | Should -Be 1
            $output | Should -Match 'is empty'
        }

        It 'refuses a Windows image tag' {
            $manager = New-AgentDir -Version '1.2.9-windows-ltsc2022'
            { & $manager start } | Should -Throw '*Windows containers*'
        }

        It 'upgrades to the newest Ubuntu tag and ignores bare and Windows tags' {
            $manager = New-AgentDir
            $null = Invoke-Manager $manager upgrade
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.10-ubuntu-26.04"
            Get-VersionFile | Should -BeLike '1.2.10*'
        }

        It 'does nothing on upgrade when already on the latest version' {
            $global:ProxyAgentStub.Tags = @('1.2.9-ubuntu-26.04', '1.2.11')
            $manager = New-AgentDir
            $output = Invoke-Manager $manager upgrade
            $LASTEXITCODE | Should -Be 0
            $output | Should -Match 'Already running the latest version'
            Get-DockerRunArgs | Should -BeNullOrEmpty
        }

        It 'rolls back when the upgraded container is unhealthy' {
            $global:ProxyAgentStub.UnhealthyTag = '1.2.10'
            $manager = New-AgentDir
            $output = Invoke-Manager $manager upgrade
            $output | Should -Match 'rolling back'
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.9-ubuntu-26.04"
            Get-VersionFile | Should -BeLike '1.2.9*'
        }

        It 'rejects an invalid version file entry on upgrade' {
            $manager = New-AgentDir -Version '2.0'
            { & $manager upgrade } | Should -Throw '*Invalid*'
        }

        It 'removes the running container on stop' {
            $manager = New-AgentDir
            $null = Invoke-Manager $manager start
            $null = Invoke-Manager $manager stop
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.LastImage | Should -BeNullOrEmpty
            ($global:ProxyAgentStub.DockerCalls | Where-Object { $_[0] -eq 'rm' })[0] | Should -Contain 'proxy-agent-abc123'
        }
    }

    Context 'with Windows containers' {
        BeforeEach {
            $global:ProxyAgentStub.OsType = 'windows'
        }

        It 'starts the image for the detected LTSC version from a plain version' {
            $manager = New-AgentDir -Version '1.2.9'
            $null = Invoke-Manager $manager start
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.9-windows-ltsc2022"
        }

        It 'starts a pinned Windows image tag as is' {
            $manager = New-AgentDir -Version '1.2.9-windows-ltsc2025'
            $null = Invoke-Manager $manager start
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.9-windows-ltsc2025"
        }

        It 'refuses an Ubuntu image tag' {
            $manager = New-AgentDir -Version '1.2.9-ubuntu-26.04'
            { & $manager start } | Should -Throw '*Linux containers*'
        }

        It 'upgrades to the newest tag for the same LTSC version only' {
            $manager = New-AgentDir -Version '1.2.9-windows-ltsc2022'
            $null = Invoke-Manager $manager upgrade
            $LASTEXITCODE | Should -Be 0
            $global:ProxyAgentStub.LastImage | Should -Be "$($script:Image):1.2.10-windows-ltsc2022"
            Get-VersionFile | Should -BeLike '1.2.10*'
        }

        It 'mounts the config directory and uses the Windows health check' {
            $manager = New-AgentDir -Version '1.2.9'
            $null = Invoke-Manager $manager start
            $runArgs = Get-DockerRunArgs
            ($runArgs | Where-Object { $_ -like '*:C:/config:ro' }) | Should -Not -BeNullOrEmpty
            ($runArgs | Where-Object { $_ -like 'powershell.exe -NoProfile -EncodedCommand *' }) | Should -Not -BeNullOrEmpty
        }
    }
}
