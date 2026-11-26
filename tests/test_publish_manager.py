import hashlib
import os
from pathlib import Path
import subprocess
import sys

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="The publisher runs on Ubuntu")

PUBLISHER = Path(__file__).resolve().parents[1] / ".github/scripts/publish_manager.sh"
COMMIT = "a" * 40
FIRST = "manager-v0.1.0"
TAG = f"{FIRST}-{COMMIT}"
BINARIES = ("manager-linux-x64", "manager-linux-aarch64", "manager-windows-x64.exe")
WRAPPERS = ("proxy-agent-manager.sh", "proxy-agent-manager.ps1")


@pytest.fixture
def release(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("GITHUB_SHA", COMMIT)
    monkeypatch.setenv("GITHUB_REPOSITORY", "fivetran/proxy_agent")
    Path("dist").mkdir()
    for name in BINARIES:
        binary = Path("dist") / name
        binary.write_bytes(name.encode())
        binary.with_name(name + ".sha256").write_text(hashlib.sha256(binary.read_bytes()).hexdigest())
    for name in WRAPPERS:
        Path(name).write_text("manager wrapper")

    tools = tmp_path / "tools"
    tools.mkdir()
    monkeypatch.setenv("PATH", str(tools) + os.pathsep + os.environ["PATH"])
    shim = tools / "gh"
    shim.write_text(r"""#!/usr/bin/env bash
set -euo pipefail
command=${0##*/}
{ printf '%s\t' "$command" "$@"; printf '\n'; } >> calls
if [[ "$command" == git ]]; then
    exit "$ANCESTRY_STATUS"
fi
case "$1 $2" in
    "api repos/fivetran/proxy_agent/releases") printf '%s\n' "$RELEASES" ;;
    "api graphql") printf '%s\n' "$LATEST" ;;
esac
""")
    shim.chmod(0o755)
    (tools / "git").symlink_to(shim)

    def publish(*, entries=(), latest="", ancestry_status=0):
        monkeypatch.setenv("RELEASES", "\n".join(
            f"{tag}\t{target}\t{str(draft).lower()}" for tag, target, draft in entries
        ))
        monkeypatch.setenv("LATEST", latest)
        monkeypatch.setenv("ANCESTRY_STATUS", str(ancestry_status))
        result = subprocess.run(["bash", PUBLISHER], capture_output=True, text=True, check=False)
        log = Path("calls")
        calls = [line.rstrip("\t").split("\t") for line in log.read_text().splitlines()] if log.exists() else []
        return result, calls

    return publish


def test_first_release_uploads_complete_asset_set_before_publishing(release):
    result, calls = release()
    assert result.returncode == 0, result.stderr
    create, upload, publish = calls[2:]
    assert create == ["gh", "release", "create", FIRST, "--draft", "--target", COMMIT, "--title", FIRST]
    assert upload[:5] == ["gh", "release", "upload", FIRST, "--clobber"]
    assert {Path(asset).name for asset in upload[5:]} == {
        *BINARIES, *(f"{name}.sha256" for name in BINARIES), *WRAPPERS,
    }
    assert publish == ["gh", "release", "edit", FIRST, "--draft=false", "--latest=true"]


def test_retry_finishes_a_draft_without_promoting_an_older_commit(release):
    entries = [(TAG, COMMIT, True), (FIRST, "b" * 40, False)]
    latest = "manager-v0.1.0-newer"
    result, calls = release(entries=entries, latest=latest, ancestry_status=1)

    assert result.returncode == 0, result.stderr
    ancestry, upload, publish = calls[2:]
    assert ancestry == ["git", "merge-base", "--is-ancestor", f"refs/tags/{latest}", COMMIT]
    assert upload[:5] == ["gh", "release", "upload", TAG, "--clobber"]
    assert publish == ["gh", "release", "edit", TAG, "--draft=false", "--latest=false"]


def test_retry_leaves_a_published_release_alone(release):
    result, calls = release(entries=[(FIRST, COMMIT, False)], latest=FIRST)

    assert result.returncode == 0, result.stderr
    assert len(calls) == 1


def test_rejects_a_corrupt_binary_before_contacting_github(release):
    Path("dist/manager-windows-x64.exe").write_bytes(b"corrupt")
    result, calls = release()

    assert result.returncode != 0
    assert "SHA-256 mismatch" in result.stderr
    assert not calls


def test_ancestry_error_stops_before_creating_a_release(release):
    result, calls = release(latest=FIRST, ancestry_status=128)

    assert result.returncode == 128
    assert len(calls) == 3
    assert calls[-1][:3] == ["git", "merge-base", "--is-ancestor"]
