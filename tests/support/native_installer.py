"""Run the Java installer with the same dependency stubs as the manager tests."""

import os
import shlex
import shutil
import subprocess
from functools import cache
from pathlib import Path
from zipfile import ZipFile

HELPER = 'com/fivetran/proxy_agent/windows-installer.ps1'

WINDOWS_STUBS = r"""
$OutputEncoding = [Text.UTF8Encoding]::new($false)
function Invoke-TestStub {
    param([string]$Name, [object[]]$Arguments)
    ConvertTo-Json -InputObject (@($Name) + @($Arguments)) -Depth 10 -Compress |
        & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB native | Out-String | Invoke-Expression
}
function Get-CimInstance { Invoke-TestStub -Name Get-CimInstance -Arguments $args }
function Get-PSDrive { Invoke-TestStub -Name Get-PSDrive -Arguments $args }
"""

WINDOWS_HTTP_STUBS = r"""
function Invoke-RestMethod { Invoke-TestStub -Name Invoke-RestMethod -Arguments $args }
function Invoke-WebRequest {
    $response = Invoke-TestStub -Name Invoke-WebRequest -Arguments $args
    if ($response.download_error) { throw $response.download_error }
    return $response
}
"""

# A real executable keeps the Windows argument quoting and process semantics of docker.exe.
DOCKER_LAUNCHER = r"""
using System;
using System.Diagnostics;
using System.Text.RegularExpressions;

class DockerStub {
    static string Quote(string value) {
        string escaped = Regex.Replace(value, "(\\\\*)(\\\"|$)", m =>
            m.Groups[1].Value + m.Groups[1].Value + (m.Groups[2].Length == 0 ? "" : "\\\""));
        return "\"" + escaped + "\"";
    }

    static int Main(string[] args) {
        string arguments = Quote(Environment.GetEnvironmentVariable("AGENT_TEST_STUB")) + " docker";
        foreach (string arg in args) {
            arguments += " " + Quote(arg);
        }
        using (var process = Process.Start(new ProcessStartInfo {
            FileName = Environment.GetEnvironmentVariable("AGENT_TEST_PYTHON"),
            Arguments = arguments,
            UseShellExecute = false
        })) {
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
"""


@cache
def installer_jar(repo):
    """Build once for unit tests. Integration tests exercise the native executable."""
    bazel = shutil.which('bazelisk') or shutil.which('bazel')
    if not bazel:
        raise RuntimeError('Bazelisk is required to run the installer tests')
    subprocess.run([bazel, 'build', '//proxy_agent:manager_deploy.jar'], check=True, cwd=repo)
    return repo / 'bazel-bin/proxy_agent/manager_deploy.jar'


def installer_environment(repo, root, env, *, windows, stub_http):
    """Set up the installer subprocess without changing the tests' assertions."""
    launch_dir = root / 'native-installer'
    launcher = launch_dir / ('manager.cmd' if windows else 'manager')
    if not launcher.exists():
        launch_dir.mkdir()
        jar = installer_jar(repo)
        java_home = os.environ.get('JAVA_HOME_17_X64') or os.environ.get('JAVA_HOME_17_ARM64')
        if windows:
            test_jar = launch_dir / 'manager.jar'
            with ZipFile(jar) as source, ZipFile(test_jar, 'w') as target:
                stubs = WINDOWS_STUBS + (WINDOWS_HTTP_STUBS if stub_http else '')
                for file in source.infolist():
                    data = source.read(file)
                    if file.filename == HELPER:
                        data = stubs.encode() + data
                    target.writestr(file, data)
            framework = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319'
            source = launch_dir / 'DockerStub.cs'
            source.write_text(DOCKER_LAUNCHER)
            subprocess.run(
                [str(framework / 'csc.exe'), '/nologo', '/out:' + str(launch_dir / 'docker.exe'), str(source)],
                check=True,
            )
            java = str(Path(java_home) / 'bin/java.exe') if java_home else 'java.exe'
            launcher.write_text(f'@echo off\n"{java}" -jar "{test_jar}" %*\n')
        else:
            java = str(Path(java_home) / 'bin/java') if java_home else 'java'
            launcher.write_text(f'#!/bin/sh\nexec {shlex.quote(java)} -jar {shlex.quote(str(jar))} "$@"\n')
            launcher.chmod(0o700)
    return {
        **env,
        'PROXY_AGENT_MANAGER_BIN': str(launcher),
        'PATH': str(launch_dir) + os.pathsep + env['PATH'],
    }
