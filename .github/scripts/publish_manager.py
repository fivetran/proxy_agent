"""Publish the native manager assets produced by one successful CI run."""

import hashlib
import json
import os
from pathlib import Path
import subprocess

FIRST_TAG = "manager-v0.1.0"
BINARIES = ("manager-linux-x64", "manager-linux-aarch64", "manager-windows-x64.exe")
WRAPPERS = ("proxy-agent-manager.sh", "proxy-agent-manager.ps1")


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True).strip()


def is_ancestor(tag, commit):
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", f"refs/tags/{tag}", commit],
        check=False,
    )
    if result.returncode > 1:
        result.check_returncode()
    return result.returncode == 0


def release_assets():
    for name in BINARIES:
        binary = Path("dist") / name
        checksum = binary.with_name(name + ".sha256")
        with binary.open("rb") as contents:
            digest = hashlib.file_digest(contents, "sha256").hexdigest()
        if not binary.stat().st_size or digest != checksum.read_text(encoding="ascii").strip():
            raise ValueError(f"SHA-256 mismatch for {binary}")
        yield str(binary)
        yield str(checksum)

    compat = Path("proxy_agent/resources/com/fivetran/proxy_agent/compat")
    for name in WRAPPERS:
        wrapper = (compat if compat.is_dir() else Path(".")) / name
        if not wrapper.stat().st_size:
            raise ValueError(f"Empty manager wrapper: {wrapper}")
        yield str(wrapper)


def publish():
    commit = os.environ["GITHUB_SHA"]
    repository = os.environ["GITHUB_REPOSITORY"]
    owner, name = repository.split("/")
    assets = list(release_assets())
    tag = f"{FIRST_TAG}-{commit}"
    pages = json.loads(gh("api", f"repos/{repository}/releases", "--paginate", "--slurp"))
    releases = {
        release["tag_name"]: release
        for page in pages
        for release in page
        if release["tag_name"] in (FIRST_TAG, tag)
    }
    first = releases.get(FIRST_TAG)
    if first is None or first["target_commitish"] == commit:
        tag = FIRST_TAG
    existing = releases.get(tag)
    if existing and existing["target_commitish"] != commit:
        raise ValueError(f"{tag} targets a different commit")
    if existing and not existing["draft"]:
        print(f"{tag} is already published.")
        return

    latest = gh(
        "api", "graphql",
        "-f", "query=query($owner:String!, $name:String!) { repository(owner:$owner, name:$name) { latestRelease { tagName } } }",
        "-f", f"owner={owner}", "-f", f"name={name}",
        "--jq", ".data.repository.latestRelease.tagName // empty",
    )
    make_latest = "true" if not latest or is_ancestor(latest, commit) else "false"

    if not existing:
        gh("release", "create", tag, "--draft", "--target", commit, "--title", tag)
    gh("release", "upload", tag, "--clobber", *assets)
    print(gh("release", "edit", tag, "--draft=false", f"--latest={make_latest}"))


if __name__ == "__main__":
    publish()
