"""Exercise the public bootstrap scripts with controlled native downloads."""

import hashlib
import os
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

import pytest
from support.harness import EXT, REPO, WINDOWS, invoke


@pytest.fixture(scope='module')
def manager_executable(tmp_path_factory):
    directory = tmp_path_factory.mktemp('bootstrap-manager')
    executable = directory / ('fixture-manager.exe' if WINDOWS else 'fixture-manager')
    if WINDOWS:
        source = directory / 'Fixture.cs'
        source.write_text("""
using System;
using System.IO;
class Fixture {
    static int Main(string[] args) {
        var result = Environment.GetEnvironmentVariable("BOOTSTRAP_TEST_RESULT");
        File.WriteAllLines(result, args);
        File.AppendAllText(result, Environment.GetEnvironmentVariable("PROXY_AGENT_INSTALLER_BINARY"));
        return 7;
    }
}
""")
        framework = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319'
        subprocess.run([str(framework / 'csc.exe'), '/nologo', '/out:' + str(executable), str(source)], check=True)
    else:
        executable.write_text(
            '#!/bin/sh\nprintf "%s\\n" "$@" "$PROXY_AGENT_INSTALLER_BINARY" > "$BOOTSTRAP_TEST_RESULT"\nexit 7\n'
        )
        executable.chmod(0o700)
    return executable


@pytest.mark.parametrize('mode', ['verified', 'wrong-digest', 'failed-download', 'path-override'])
def test_bootstrap_checks_download_and_forwards_arguments(tmp_path, manager_executable, mode):
    result_file = tmp_path / 'invocation'
    scratch = tmp_path / 'scratch'
    scratch.mkdir()
    payload = manager_executable.read_bytes()
    served = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            served.append(self.path)
            self.send_response(503 if mode == 'failed-download' else 200)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_arguments):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
        env = {key: value for key, value in os.environ.items()
               if not key.upper().startswith('PROXY_AGENT_') and not key.upper().endswith('_PROXY')}
        env.update(TMPDIR=str(scratch), TEMP=str(scratch), TMP=str(scratch), BOOTSTRAP_TEST_RESULT=str(result_file))
        if mode == 'path-override':
            env['PROXY_AGENT_MANAGER_BIN'] = manager_executable.name
            env['PATH'] = str(manager_executable.parent) + os.pathsep + env['PATH']
        else:
            env['PROXY_AGENT_MANAGER_BINARY_URL'] = f'http://127.0.0.1:{server.server_port}/manager'
            env['PROXY_AGENT_MANAGER_BINARY_SHA256'] = (
                '0' * 64 if mode == 'wrong-digest' else hashlib.sha256(payload).hexdigest()
            )
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        config = tmp_path / 'config with spaces.json'
        install_dir = tmp_path / 'install with spaces'
        try:
            result = invoke(REPO / f'install.{EXT}', [config], parameters={'InstallDir': install_dir}, env=env)
        finally:
            server.shutdown()
            thread.join()

    assert not list(scratch.iterdir())
    assert served == ([] if mode == 'path-override' else ['/manager'])
    if mode in ('wrong-digest', 'failed-download'):
        expected = 'SHA-256 does not match' if mode == 'wrong-digest' else 'Failed to download'
        assert result.returncode == 1, result.stdout
        assert expected in result.stdout
        assert not result_file.exists()
    else:
        assert result.returncode == 7, result.stdout
        *arguments, binary = result_file.read_text(encoding='utf-8').splitlines()
        assert arguments == ['install', str(config), '--install-dir', str(install_dir)]
        assert Path(binary).is_absolute()
        if mode == 'path-override':
            assert Path(binary) == manager_executable.resolve()
        else:
            assert Path(binary).parent.parent == scratch.resolve()
