"""Identity and custody for qualified, reusable native Python dependencies."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path

SCHEMA = "skywright-graalpy-environment@1"
RECORD = "skywright-environment.json"
WHEEL_INPUTS = (
    "graalpy-environment/pom.xml",
    "graalpy-environment/graalpy.lock",
    "graalpy-environment/build-constraints.txt",
    "scripts/prepare-graalpy-wheels",
    "scripts/retag-wheel",
)
INPUTS = (
    *WHEEL_INPUTS,
    ".github/actions/prepare-graalpy/action.yml",
    "scripts/graalpy-environment",
    "graalpy-environment/VerifyEnvironment.java",
    "scripts/quality_support/graalpy_environment.py",
    "backend-deployment/src/main/docker/Dockerfile",
)


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_document(path: Path) -> dict:
    if path.stat().st_size > 1024 * 1024:
        raise ValueError(f"Environment metadata exceeds its bound: {path.name}")
    result = json.loads(path.read_text())
    if not isinstance(result, dict):
        raise ValueError("Environment metadata must be an object")
    return result


def write_document(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_name(path.name + ".pending")
    pending.write_bytes(canonical(value) + b"\n")
    pending.replace(path)


def effective_versions(root: Path) -> dict[str, str]:
    """Let Maven resolve inheritance, profiles and command-line property overrides."""
    result = {}
    with tempfile.TemporaryDirectory(prefix="skywright-effective-versions-") as temporary:
        for name in ("graalpy", "skypilot"):
            output = Path(temporary) / name
            subprocess.run(
                ["mvn", "--batch-mode", "--no-transfer-progress", "-f",
                 str(root / "graalpy-environment/pom.xml"), "help:evaluate",
                 f"-Dexpression={name}.version", f"-Doutput={output}"],
                cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120,
            )
            value = output.read_text().strip()
            if not re.fullmatch(r"[0-9][A-Za-z0-9.+_-]*", value):
                raise ValueError("Maven did not resolve the runtime and SDK versions")
            result[name] = value
    return result


def command_version(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=10)
        return result.stdout.strip().splitlines()[0]
    except (OSError, subprocess.SubprocessError, IndexError):
        return "unavailable"


def build_platform() -> dict:
    try:
        release = platform.freedesktop_os_release()
    except OSError:
        release = {}
    return {"system": platform.system(), "machine": platform.machine(),
            "distribution": {key: release.get(key, "unknown") for key in ("ID", "VERSION_ID")},
            "libc": list(platform.libc_ver())}


def native_inputs() -> dict:
    return {
        "cc": command_version([*shlex.split(os.environ.get("CC", "cc")), "--version"]),
        "cxx": command_version([*shlex.split(os.environ.get("CXX", "c++")), "--version"]),
        "java": command_version(["java", "--version"]),
        "rustc": command_version(["rustc", "--version"]),
        "openssl": command_version(["openssl", "version"]),
        "flags": {key: os.environ.get(key, "") for key in
                  ("CC", "CXX", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "RUSTFLAGS", "CARGO_BUILD_TARGET")},
    }


def identity(root: Path, versions: dict[str, str], *, target: dict | None = None,
             native: dict | None = None) -> dict:
    return {"schema": SCHEMA, "versions": versions,
            "platform": build_platform() if target is None else target,
            "nativeBuild": native_inputs() if native is None else native,
            "toolchain": read_document(root / "quality/toolchain.json"),
            "inputs": {name: digest((root / name).read_bytes()) for name in INPUTS}}


def wheel_identity(environment: dict) -> dict:
    """Reuse installation inputs when only packaging or qualification changes.

    These wheels still require a locked install and fresh environment qualification.
    Keep exact compiler, runtime, recipe and platform boundaries, including flags.
    """
    return {"schema": "skywright-graalpy-wheels@1",
            **{name: environment[name] for name in
               ("versions", "platform", "nativeBuild", "toolchain")},
            "inputs": {name: environment["inputs"][name] for name in WHEEL_INPUTS}}


def package_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def locked_packages(root: Path) -> dict[str, str]:
    result = {}
    for line in (root / "graalpy-environment/graalpy.lock").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, separator, version = line.partition("==")
        if not separator or not version or package_name(name) in result:
            raise ValueError("Unsupported or duplicate locked package")
        result[package_name(name)] = version
    return result


def verify_observation(root: Path, versions: dict[str, str], observed: dict) -> None:
    if observed.get("implementation") != "graalpy" or observed.get("graalpy") != versions["graalpy"]:
        raise ValueError("Actual GraalPy runtime differs from the effective Maven version")
    if observed.get("skypilot") != versions["skypilot"]:
        raise ValueError("Actual SkyPilot SDK differs from the effective Maven version")
    packages = observed.get("packages")
    if not isinstance(packages, dict):
        raise ValueError("Actual installed package inventory is unavailable")
    for name, version in locked_packages(root).items():
        if packages.get(name) != version:
            raise ValueError(f"Installed package differs from the lock: {name}")
    if packages.get("skypilot") != versions["skypilot"]:
        raise ValueError("Locked SkyPilot SDK differs from the effective Maven version")
    lock = (root / "graalpy-environment/graalpy.lock").read_text()
    if f"# graalpy-version: {versions['graalpy']}\n" not in lock:
        raise ValueError("Locked GraalPy version differs from the effective Maven version")


def prune_bytecode(resources: Path) -> None:
    """Discard mutable interpreter output without following dependency symlinks."""
    library = resources / "venv/lib"
    if library.is_symlink() or not library.is_dir():
        raise ValueError("Packaged environment library is missing or linked externally")
    for path in list(library.rglob("__pycache__")):
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)
    for path in library.rglob("*.py[co]"):
        if path.is_symlink() or path.is_file():
            path.unlink()


def payload_digest(resources: Path) -> str:
    """Hash installed dependencies, not relocatable launchers."""
    library = resources / "venv/lib"
    installed = resources / "venv/installed.txt"
    contents = resources / "venv/contents"
    if library.is_symlink() or not library.is_dir() or not installed.is_file() or not contents.is_file():
        raise ValueError("Packaged environment is incomplete")
    checksum = hashlib.sha256()
    for path in sorted([installed, contents, *library.rglob("*")]):
        if path.is_symlink():
            if not path.resolve().is_relative_to(library.resolve()) or path.is_dir():
                raise ValueError("Dependency payload contains an external or directory symlink")
            data = b"link\0" + os.readlink(path).encode()
        elif path.is_file():
            body = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    body.update(chunk)
            data = b"file\0" + body.digest()
        else:
            continue
        checksum.update(path.relative_to(resources).as_posix().encode() + b"\0" + data + b"\0")
    return checksum.hexdigest()


def verify_contents(resources: Path, versions: dict[str, str]) -> None:
    contents = dict(line.split("=", 1) for line in
                    (resources / "venv/contents").read_text().splitlines() if "=" in line)
    if contents.get("version") != versions["graalpy"]:
        raise ValueError("Packaged GraalPy runtime metadata differs from the effective version")
    if f"skypilot=={versions['skypilot']}" not in contents.get("input_packages", "").split(","):
        raise ValueError("Packaged SkyPilot requirement differs from the effective version")


def verify_record(root: Path, resources: Path, versions: dict[str, str], expected: dict | None = None) -> dict:
    verify_contents(resources, versions)
    record = read_document(resources / RECORD)
    recorded = record.get("identity")
    if not isinstance(recorded, dict) or recorded.get("schema") != SCHEMA:
        raise ValueError("Packaged environment identity is missing or unsupported")
    if not isinstance(recorded.get("platform"), dict) or not isinstance(recorded.get("nativeBuild"), dict):
        raise ValueError("Packaged environment platform and native inputs are missing")
    # A packaging host may differ from the qualified native build host. Keep that
    # provenance, while checking current source inputs and freshly resolved runtime.
    current = identity(root, versions, target=recorded.get("platform"), native=recorded.get("nativeBuild"))
    if recorded != current or expected is not None and recorded != expected:
        raise ValueError("Packaged environment identity does not match effective build inputs")
    if record.get("identitySha256") != digest(canonical(recorded)):
        raise ValueError("Packaged environment identity digest is invalid")
    verify_observation(root, versions, record.get("observed", {}))
    prune_bytecode(resources)
    if record.get("payloadSha256") != payload_digest(resources):
        raise ValueError("Packaged environment payload differs from its qualified record")
    return record


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("identity", "prune-bytecode", "seal", "verify", "stamp", "provenance"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--resources", type=Path)
    parser.add_argument("--graalpy-version")
    parser.add_argument("--skypilot-version")
    parser.add_argument("--observation", type=Path)
    parser.add_argument("--expected", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--run-attempt")
    parser.add_argument("--source")
    args = parser.parse_args(arguments)
    root = args.root.resolve()
    resources = args.resources or root / ".graalpy/resources"
    if args.command == "prune-bytecode":
        prune_bytecode(resources)
        return 0
    if bool(args.graalpy_version) != bool(args.skypilot_version):
        raise ValueError("Both effective versions must be supplied together")
    versions = {"graalpy": args.graalpy_version, "skypilot": args.skypilot_version} if args.graalpy_version else effective_versions(root)
    if args.command == "identity":
        value = identity(root, versions)
        if args.output:
            write_document(args.output, value)
        key = digest(canonical(value))
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write(f"identity={key}\n")
                output.write(f"wheel-identity={digest(canonical(wheel_identity(value)))}\n")
        print(key)
        return 0
    expected = read_document(args.expected) if args.expected else None
    if args.command == "seal":
        if args.observation is None:
            raise ValueError("Sealing requires actual runtime observations")
        verify_contents(resources, versions)
        observed = read_document(args.observation)
        verify_observation(root, versions, observed)
        value = identity(root, versions)
        if expected is not None and value != expected:
            raise ValueError("Build inputs changed during environment preparation")
        prune_bytecode(resources)
        record = {"identity": value, "identitySha256": digest(canonical(value)),
                  "observed": observed, "payloadSha256": payload_digest(resources)}
        write_document(resources / RECORD, record)
    else:
        record = verify_record(root, resources, versions, expected)
        if args.observation:
            verify_observation(root, versions, read_document(args.observation))
    if args.command in ("stamp", "provenance"):
        if not args.run_id or not args.source or not args.output or not args.run_attempt or not re.fullmatch(r"[1-9][0-9]*", args.run_attempt):
            raise ValueError("Artifact provenance requires run, positive producer attempt, source and output path")
        current_source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        if current_source != args.source:
            raise ValueError("Artifact source differs from the checked-out commit")
        provenance = {"schema": "skywright-graalpy-artifact@1", "runId": args.run_id,
                      "runAttempt": args.run_attempt, "sourceCommit": args.source, "identitySha256": record["identitySha256"],
                      "payloadSha256": record["payloadSha256"]}
        if args.command == "stamp":
            write_document(args.output, provenance)
        else:
            actual = read_document(args.output)
            if any(actual.get(key) != value for key, value in provenance.items()):
                raise ValueError("Packaged environment artifact is not from this run and source")
    print(f"{args.command}: {record['identitySha256']}")
    return 0
