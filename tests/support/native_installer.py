"""Run the Java installer with the same dependency stubs as the manager tests."""

import json
import os
import shlex
import shutil
import subprocess
from contextlib import contextmanager
from functools import cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

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
    subprocess.run([bazel, 'build', '//proxy_agent:test_manager_deploy.jar'], check=True, cwd=repo)
    return repo / 'bazel-bin/proxy_agent/test_manager_deploy.jar'


def installer_environment(repo, root, env, *, windows):
    """Set up the installer subprocess without changing the tests' assertions."""
    launch_dir = root / 'native-installer'
    launcher = launch_dir / ('manager.cmd' if windows else 'manager')
    if not launcher.exists():
        launch_dir.mkdir()
        jar = installer_jar(repo)
        java_home = os.environ.get('JAVA_HOME_17_X64') or os.environ.get('JAVA_HOME_17_ARM64')
        if windows:
            framework = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319'
            source = launch_dir / 'DockerStub.cs'
            source.write_text(DOCKER_LAUNCHER)
            subprocess.run(
                [str(framework / 'csc.exe'), '/nologo', '/out:' + str(launch_dir / 'docker.exe'), str(source)],
                check=True,
            )
            java = str(Path(java_home) / 'bin/java.exe') if java_home else 'java.exe'
            launcher.write_text(f'@echo off\n"{java}" -jar "{jar}" %*\n')
        else:
            java = str(Path(java_home) / 'bin/java') if java_home else 'java'
            launcher.write_text(f'#!/bin/sh\nexec {shlex.quote(java)} -jar {shlex.quote(str(jar))} "$@"\n')
            launcher.chmod(0o700)
    return {
        **env,
        'PROXY_AGENT_MANAGER_BIN': str(launcher),
        'PATH': str(launch_dir) + os.pathsep + env['PATH'],
    }


@contextmanager
def installer_endpoints(options, *, windows):
    """Serve the installer's fixture responses over HTTP for Java's web client."""
    script = (
        '$OutputEncoding = [Text.UTF8Encoding]::new($false)\n'
        'ConvertTo-Json -InputObject @($args) -Compress | '
        '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB manager-json\n'
        'exit $LASTEXITCODE\n'
        if windows else
        '#!/usr/bin/env bash\nexec "$AGENT_TEST_PYTHON" "$AGENT_TEST_STUB" manager "$@"\n'
    )

    class Endpoints(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/manager':
                if options.get('download_failure') or options.get('download_exit'):
                    self.reply(503, 'partial download')
                else:
                    self.reply(200, options.get('script_content', script))
            elif self.path == '/tags':
                self.reply(503 if options.get('registry_failure') else 200, json.dumps({'tags': options['tags']}))
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path != '/proxy-agent/configure':
                self.send_error(404)
            elif not options.get('configure_failure'):
                self.reply(options.get('configure_status', 200), options.get('configure_body', '{"agent_id":"abc123"}'))

        def reply(self, status, data):
            body = data.encode('utf-8-sig' if windows and self.path == '/manager' else 'utf-8')
            self.send_response(status)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_arguments):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Endpoints) as server:
        thread = Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            yield dict(PROXY_AGENT_SCRIPT_URL=f'{base}/manager', PROXY_AGENT_REGISTRY_TAGS_URL=f'{base}/tags',
                       FIVETRAN_API_URL=base)
        finally:
            server.shutdown()
            thread.join()
