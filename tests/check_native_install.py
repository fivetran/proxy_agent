"""Exercise failed reinstalls against the Java manager JAR or native binary."""

import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread


def check_reinstall(manager, config, tags_status=200, missing_binary=False):
    class Endpoints(BaseHTTPRequestHandler):
        def do_POST(self):
            assert self.path == "/proxy-agent/configure"
            self.reply(200, config)

        def do_GET(self):
            if self.path == "/manager":
                self.reply(200, b"#!/bin/sh\ntouch started\n")
            else:
                assert self.path == "/tags"
                self.reply(tags_status, b'{"tags":["1.2.3-ubuntu-26.04"]}')

        def reply(self, status, body):
            self.send_response(status)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    with TemporaryDirectory() as directory, ThreadingHTTPServer(("127.0.0.1", 0), Endpoints) as server:
        root = Path(directory)
        docker = root / "docker"
        docker.write_text('#!/bin/sh\nif [ "$1" = --version ]; then echo "Docker version 27.0.0"; fi\n')
        docker.chmod(0o700)
        user_id = root / "id"
        user_id.write_text("#!/bin/sh\necho 1000\n")
        user_id.chmod(0o700)

        install_dir = root / "installed"
        (install_dir / "config").mkdir(parents=True)
        (install_dir / "logs").mkdir()
        existing = {
            "config/config.json": b'{"agent_id":"existing"}',
            "manager": b"existing binary",
            "proxy-agent-manager.sh": b"existing script",
            "settings.sh": b"MEMORY_ALLOCATION_MB=5120\n",
            "version": b"1.0.0\n",
        }
        for name, contents in existing.items():
            (install_dir / name).write_bytes(contents)

        base = f"http://127.0.0.1:{server.server_port}"
        env = {key: value for key, value in os.environ.items() if not key.lower().endswith("_proxy")}
        env.update(
            PATH=f"{root}{os.pathsep}{os.environ['PATH']}",
            RUNTIME="docker",
            TOKEN="fixture-token",
            MEMORY_ALLOCATION_MB="5120",
            FIVETRAN_API_URL=base,
            PROXY_AGENT_SCRIPT_URL=f"{base}/manager",
            PROXY_AGENT_REGISTRY_TAGS_URL=f"{base}/tags",
            PROXY_AGENT_INSTALLER_BINARY=str(root / "missing-manager") if missing_binary else "",
        )
        worker = Thread(target=server.serve_forever)
        worker.start()
        try:
            result = subprocess.run(
                [*manager, "install", "--install-dir", str(install_dir)],
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
        finally:
            server.shutdown()
            worker.join()

        assert result.returncode == 1, (result.stdout, result.stderr)
        assert env["TOKEN"] not in result.stdout + result.stderr
        for name, contents in existing.items():
            assert (install_dir / name).read_bytes() == contents, name
        assert not (install_dir / "started").exists()
        assert not list(install_dir.rglob("*.tmp"))
        return result.stderr


if __name__ == "__main__":
    executable = Path(sys.argv[1]).resolve()
    manager = ["java", "-jar", str(executable)] if executable.suffix == ".jar" else [str(executable)]
    malformed = check_reinstall(manager, b'{"agent_id":"new"} trailing')
    assert "invalid JSON response" in malformed, malformed
    for config in (b"{}", b'{"agent_id":4}', b'{"agent_id":""}'):
        invalid_id = check_reinstall(manager, config)
        assert "nonempty string agent_id" in invalid_id, invalid_id

    config = b'{"agent_id":"new"}'
    registry_failure = check_reinstall(manager, config, tags_status=503)
    assert "Unable to query image registry" in registry_failure, registry_failure
    copy_failure = check_reinstall(manager, config, missing_binary=True)
    assert "missing-manager" in copy_failure, copy_failure
    print("Failed reinstalls preserved the existing installation in all six cases.")
