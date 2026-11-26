import hashlib
import importlib.util
import json
from pathlib import Path
from unittest.mock import Mock, call

import pytest

spec = importlib.util.spec_from_file_location(
    "publish_manager", Path(__file__).resolve().parents[1] / ".github/scripts/publish_manager.py"
)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

COMMIT = "a" * 40
FIRST = "manager-v0.1.0"
TAG = f"manager-v0.1.0-{COMMIT}"


@pytest.fixture
def release(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("GITHUB_SHA", COMMIT)
    monkeypatch.setenv("GITHUB_REPOSITORY", "fivetran/proxy_agent")
    for name in ("manager-linux-x64", "manager-linux-aarch64", "manager-windows-x64.exe"):
        binary = Path("dist") / name
        binary.parent.mkdir(exist_ok=True)
        binary.write_bytes(name.encode())
        binary.with_name(name + ".sha256").write_text(hashlib.sha256(binary.read_bytes()).hexdigest())
    for name in ("proxy-agent-manager.sh", "proxy-agent-manager.ps1"):
        Path(name).write_text("manager wrapper")
    gh = Mock()
    monkeypatch.setattr(publisher, "gh", gh)
    monkeypatch.setattr(publisher, "is_ancestor", Mock(return_value=True))
    return gh


def releases(gh, *, entries=(), latest=None):
    gh.side_effect = [json.dumps([list(entries)]), latest or "", "", "", ""]


def test_first_release_uploads_complete_asset_set_before_publishing(release):
    releases(release)

    publisher.publish()

    create, upload, publish = release.call_args_list[2:]
    assert create == call("release", "create", FIRST, "--draft", "--target", COMMIT, "--title", FIRST)
    assert upload.args[:4] == ("release", "upload", FIRST, "--clobber")
    assert {Path(asset).name for asset in upload.args[4:]} == {
        "manager-linux-x64", "manager-linux-x64.sha256",
        "manager-linux-aarch64", "manager-linux-aarch64.sha256",
        "manager-windows-x64.exe", "manager-windows-x64.exe.sha256",
        "proxy-agent-manager.sh", "proxy-agent-manager.ps1",
    }
    assert publish == call("release", "edit", FIRST, "--draft=false", "--latest=true")


def test_retry_finishes_a_draft_without_promoting_an_older_commit(release):
    draft = {"tag_name": TAG, "draft": True, "target_commitish": COMMIT}
    initial = {"tag_name": FIRST, "draft": False, "target_commitish": "b" * 40}
    releases(release, entries=[draft, initial], latest="manager-v0.1.0-newer")
    publisher.is_ancestor.return_value = False

    publisher.publish()

    publisher.is_ancestor.assert_called_once_with("manager-v0.1.0-newer", COMMIT)
    assert release.call_args_list[2].args[:4] == ("release", "upload", TAG, "--clobber")
    assert release.call_args == call("release", "edit", TAG, "--draft=false", "--latest=false")


def test_retry_leaves_a_published_release_alone(release):
    initial = {"tag_name": FIRST, "draft": False, "target_commitish": COMMIT}
    releases(release, entries=[initial], latest=FIRST)

    publisher.publish()

    assert release.call_count == 1


def test_rejects_a_corrupt_binary_before_contacting_github(release):
    Path("dist/manager-windows-x64.exe").write_bytes(b"corrupt")

    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        publisher.publish()
    release.assert_not_called()
