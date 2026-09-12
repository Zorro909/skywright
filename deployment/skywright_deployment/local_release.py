"""Verify and retain a versioned local release before any installation mutation."""

from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import sys
import tarfile
import tempfile
from pathlib import Path

from .local_host import download
from .local_operations import command

ARCHIVE_TOOLS = {
    "gh": ("https://github.com/cli/cli/releases/download/v2.94.0/gh_2.94.0_linux_amd64.tar.gz",
           "a757f1ba6db18f4de8cbadb244843a5f89bc75b5e7c6fc127d2bd77fbd12ed62",
           "gh_2.94.0_linux_amd64/bin/gh"),
    "oras": ("https://github.com/oras-project/oras/releases/download/v1.3.3/oras_1.3.3_linux_amd64.tar.gz",
             "9ce999f8d2de03fc03968b29d743077a58783e545e5eaa53917ca177352d0e59", "oras"),
}


def version_key(version: str) -> tuple:
    core, separator, prerelease = version.removeprefix("v").split("+", 1)[0].partition("-")
    identifiers = tuple((0, int(part)) if part.isdigit() else (1, part)
                        for part in prerelease.split(".")) if separator else ()
    return (*map(int, core.split(".")), not bool(separator), identifiers)


def release_tools(directory: Path) -> dict[str, Path]:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    result = {}
    for name, (url, digest, member_name) in ARCHIVE_TOOLS.items():
        path = directory / (name + "-" + digest[:12])
        if not path.exists():
            archive = download(url, digest, 100 * 1024 * 1024)
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
                member = package.getmember(member_name)
                if not member.isfile() or member.size > 100 * 1024 * 1024:
                    raise SystemExit("Invalid pinned release tool archive")
                temporary = path.with_suffix(".partial")
                temporary.write_bytes(package.extractfile(member).read())
                temporary.chmod(0o700)
                temporary.replace(path)
        result[name] = path
    return result


def fetch(settings: dict, directory: Path, registry: dict) -> tuple[Path, dict]:
    tools = release_tools(directory / "tools")
    source = Path(__file__).resolve().parents[2]
    release = settings["release"]
    releases = directory / "releases"
    releases.mkdir(mode=0o700, exist_ok=True)
    destination = releases / release.split(":")[-1]
    with tempfile.TemporaryDirectory(prefix="release-", dir=directory) as temporary:
        staging = Path(temporary)
        credentials = staging / "credentials"
        credentials.mkdir(mode=0o700)
        encoded = base64.b64encode((registry["username"] + ":" + registry["token"]).encode()).decode()
        auth = credentials / "config.json"
        auth.write_text(json.dumps({"auths": {"ghcr.io": {"auth": encoded}}}))
        auth.chmod(0o600)
        environment = os.environ | {"GH_TOKEN": registry["token"], "DOCKER_CONFIG": str(credentials)}
        def attest(image: str):
            command([str(tools["gh"]), "attestation", "verify", "oci://" + image,
                     "--repo", "Zorro909/skywright", "--signer-workflow",
                     "Zorro909/skywright/.github/workflows/deployment-release.yml",
                     "--deny-self-hosted-runners"], environment=environment, timeout=120)
        attest(release)
        payload = staging / "payload"
        payload.mkdir()
        command([str(tools["oras"]), "pull", "--registry-config", str(auth),
                 release, "--output", str(payload)], timeout=120)
        metadata = json.loads(command([sys.executable, str(source / "deployment/scripts/release-support"),
                                       "verify-bundle", "--directory", str(payload),
                                       "--allow-prerelease"]).stdout)
        if metadata["schemaVersion"] != 4 or metadata["localStateSchema"] != 1:
            raise SystemExit("This release does not support the local installation state format")
        for field in ("backendImage", "skypilotImage", "writerImage"):
            attest(metadata[field])
        # The verified archive validator rejects symlinks, devices, duplicate names and traversal.
        code = staging / "code"
        code.mkdir()
        with tarfile.open(payload / "local-package.tar.gz", "r:gz") as archive:
            archive.extractall(code, filter="data")
        if destination.exists():
            for name in ("release-metadata.json", "SHA256SUMS"):
                if (destination / "payload" / name).read_bytes() != (payload / name).read_bytes():
                    raise SystemExit("Retained release metadata differs from its verified digest")
            # Refresh executable code from the verified archive instead of trusting a stale extraction.
            import shutil
            shutil.rmtree(destination / "code")
            code.rename(destination / "code")
        else:
            destination.mkdir(mode=0o700)
            payload.rename(destination / "payload")
            code.rename(destination / "code")
    return destination, metadata
