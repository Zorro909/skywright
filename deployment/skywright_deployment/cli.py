"""Public local and production deployment workflows."""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn


REPOSITORY = Path(
    os.environ.get("SKYWRIGHT_REPOSITORY", Path(__file__).resolve().parents[2])
).resolve()
NAMESPACE = "skywright"
LOCAL_LABEL = "skywright.io/local-retained-data=true"
RESET_CONFIRMATION = "reset skywright local control-plane state"
SKAFFOLD_VERSION = "v2.24.0"
ORAS_VERSION = "1.3.3"
BUNDLE_PATTERN = re.compile(
    r"^ghcr\.io/zorro909/skywright-deployment@sha256:[0-9a-f]{64}$"
)
PRODUCTION_SECRET_KEYS = {
    "migrationUrl",
    "migrationUsername",
    "migrationPassword",
    "runtimeUrl",
    "runtimeUsername",
    "runtimePassword",
}
PRODUCTION_SKYPILOT_SECRET_KEYS = {"connectionUri"}


def abort(message: str) -> NoReturn:
    raise SystemExit(message)


def tool(name: str) -> str:
    return os.environ.get(f"SKYWRIGHT_{name.upper()}", name)


def run(
    command: list[str],
    *,
    environment: dict[str, str] | None = None,
    input_text: str | None = None,
    capture: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=REPOSITORY,
        env=environment,
        input=input_text,
        check=check,
        capture_output=capture,
        text=True,
    )


@dataclass(frozen=True)
class LocalEnvironment:
    context: str
    cluster: str
    environment: dict[str, str]


def local_environment(context: str) -> LocalEnvironment:
    if not context.startswith("kind-") or len(context) == len("kind-"):
        abort(f"local deployment requires a kind context, got {context!r}")
    cluster = context.removeprefix("kind-")
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime:
        abort("XDG_RUNTIME_DIR is required for rootless Podman")
    socket = Path(runtime) / "podman" / "podman.sock"
    if not socket.exists():
        abort(f"rootless Podman socket is unavailable: {socket}")
    environment = os.environ | {
        "DOCKER_HOST": f"unix://{socket}",
        "KIND_EXPERIMENTAL_PROVIDER": "podman",
    }
    return LocalEnvironment(context=context, cluster=cluster, environment=environment)


def require_local_preflight(local: LocalEnvironment) -> None:
    version = run(
        [tool("skaffold"), "version"],
        environment=local.environment,
        capture=True,
    ).stdout.strip()
    if version != SKAFFOLD_VERSION:
        abort(f"Skaffold {SKAFFOLD_VERSION} is required, got {version!r}")

    active_context = run(
        [tool("kubectl"), "config", "current-context"],
        environment=local.environment,
        capture=True,
    ).stdout.strip()
    if active_context != local.context:
        abort(
            f"selected context {local.context!r} is not the active context {active_context!r}"
        )

    clusters = run(
        [tool("kind"), "get", "clusters"],
        environment=local.environment,
        capture=True,
    ).stdout.splitlines()
    if local.cluster not in clusters:
        abort(f"kind cluster {local.cluster!r} does not exist")

    podman_info = json.loads(
        run(
            [
                tool("podman"),
                "--url",
                local.environment["DOCKER_HOST"],
                "info",
                "--format",
                "json",
            ],
            environment=local.environment,
            capture=True,
        ).stdout
    )
    host = podman_info.get("host", podman_info.get("Host", {}))
    security = host.get("security", host.get("Security", {}))
    rootless = security.get("rootless", security.get("Rootless"))
    arch = host.get("arch", host.get("Arch"))
    if rootless is not True:
        abort("the selected Podman service is not rootless")
    if arch not in {"amd64", "x86_64"}:
        abort(f"unsupported Podman host architecture: {arch!r}")

    node = json.loads(
        run(
            [
                tool("podman"),
                "--url",
                local.environment["DOCKER_HOST"],
                "inspect",
                f"{local.cluster}-control-plane",
            ],
            environment=local.environment,
            capture=True,
        ).stdout
    )
    labels = node[0].get("Config", {}).get("Labels", {}) if node else {}
    if labels.get("io.x-k8s.kind.role") != "control-plane":
        abort("the selected kind cluster is not owned by the rootless Podman service")

    storage_classes = json.loads(
        run(
            [tool("kubectl"), "get", "storageclass", "--output", "json"],
            environment=local.environment,
            capture=True,
        ).stdout
    )
    defaults = [
        item
        for item in storage_classes.get("items", [])
        if item.get("metadata", {})
        .get("annotations", {})
        .get("storageclass.kubernetes.io/is-default-class")
        == "true"
    ]
    if len(defaults) != 1:
        abort("the cluster must expose exactly one default dynamic StorageClass")


def kubectl_apply(local: LocalEnvironment, manifest: str) -> None:
    run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "apply",
            "--filename",
            "-",
        ],
        environment=local.environment,
        input_text=manifest,
    )


def bootstrap_local_data(local: LocalEnvironment) -> None:
    kubectl_apply(
        local,
        """apiVersion: v1
kind: Namespace
metadata:
  name: skywright
  labels:
    app.kubernetes.io/part-of: skywright
    skywright.io/local-environment: "true"
""",
    )
    existing_secret = run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "get",
            "secret",
            "skywright-local-database",
            "--namespace",
            NAMESPACE,
        ],
        environment=local.environment,
        capture=True,
        check=False,
    )
    if existing_secret.returncode != 0:
        password = lambda: secrets.token_urlsafe(32)
        kubectl_apply(
            local,
            f"""apiVersion: v1
kind: Secret
metadata:
  name: skywright-local-database
  namespace: {NAMESPACE}
  labels:
    app.kubernetes.io/part-of: skywright
    skywright.io/local-retained-data: "true"
type: Opaque
stringData:
  administratorPassword: {password()}
  migrationUsername: skywright_migration
  migrationPassword: {password()}
  runtimeUsername: skywright_runtime
  runtimePassword: {password()}
""",
        )
    existing_skypilot_secret = run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "get",
            "secret",
            "skywright-local-skypilot-database",
            "--namespace",
            NAMESPACE,
        ],
        environment=local.environment,
        capture=True,
        check=False,
    )
    if existing_skypilot_secret.returncode != 0:
        skypilot_password = secrets.token_urlsafe(32)
        kubectl_apply(
            local,
            f"""apiVersion: v1
kind: Secret
metadata:
  name: skywright-local-skypilot-database
  namespace: {NAMESPACE}
  labels:
    app.kubernetes.io/part-of: skywright
    skywright.io/local-retained-data: "true"
type: Opaque
stringData:
  password: {skypilot_password}
  connectionUri: postgresql://skypilot:{skypilot_password}@skywright-postgresql:5432/skypilot
""",
        )
    kubectl_apply(
        local,
        f"""apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: skywright-postgresql-data
  namespace: {NAMESPACE}
  labels:
    app.kubernetes.io/part-of: skywright
    skywright.io/local-retained-data: "true"
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 4Gi
""",
    )
    kubectl_apply(
        local,
        f"""apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: skywright-skypilot-state
  namespace: {NAMESPACE}
  labels:
    app.kubernetes.io/part-of: skywright
    skywright.io/local-retained-data: "true"
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 4Gi
""",
    )


def local_command(arguments: argparse.Namespace) -> None:
    local = local_environment(arguments.context)
    require_local_preflight(local)
    bootstrap_local_data(local)
    process = subprocess.Popen(
        [
            tool("skaffold"),
            "dev",
            "--profile",
            "local-kind",
            "--kube-context",
            local.context,
            "--port-forward",
            "--cleanup=true",
        ],
        cwd=REPOSITORY,
        env=local.environment,
        text=True,
    )
    previous = signal.getsignal(signal.SIGTERM)

    def request_stop(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, request_stop)
    try:
        returncode = process.wait()
        if returncode != 0:
            abort(f"Skaffold exited with status {returncode}")
    except KeyboardInterrupt:
        stop_skaffold(process)
    finally:
        signal.signal(signal.SIGTERM, previous)


def reset_local_state(arguments: argparse.Namespace) -> None:
    local = local_environment(arguments.context)
    require_local_preflight(local)
    namespace_marker = run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "get",
            "namespace",
            NAMESPACE,
            "--output",
            "jsonpath={.metadata.labels.skywright\\.io/local-environment}",
        ],
        environment=local.environment,
        capture=True,
        check=False,
    )
    if namespace_marker.returncode != 0 or namespace_marker.stdout.strip() != "true":
        abort("the skywright Namespace is not marked as a local environment; nothing was deleted")
    confirmation = arguments.confirm
    if confirmation is None:
        confirmation = input(f"Type {RESET_CONFIRMATION!r} to continue: ")
    if confirmation != RESET_CONFIRMATION:
        abort("confirmation did not match; nothing was deleted")
    output = run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "get",
            "persistentvolumeclaim,secret",
            "--namespace",
            NAMESPACE,
            "--selector",
            LOCAL_LABEL,
            "--output",
            "name",
        ],
        environment=local.environment,
        capture=True,
    ).stdout.splitlines()
    expected = {
        "persistentvolumeclaim/skywright-postgresql-data",
        "persistentvolumeclaim/skywright-skypilot-state",
        "secret/skywright-local-database",
        "secret/skywright-local-skypilot-database",
    }
    if set(output) != expected:
        abort(
            "owned local control-plane resources did not match the reset contract; nothing was deleted"
        )
    run(
        [
            tool("kubectl"),
            "--context",
            local.context,
            "delete",
            "--namespace",
            NAMESPACE,
            *sorted(expected),
        ],
        environment=local.environment,
    )


def git(*arguments: str, capture: bool = False, check: bool = True):
    return run(
        ["git", *arguments],
        capture=capture,
        check=check,
    )


def fetch_branch(branch: str, private_ref: str) -> str:
    completed = git(
        "fetch",
        "--force",
        "origin",
        f"refs/heads/{branch}:{private_ref}",
        capture=True,
        check=False,
    )
    if completed.returncode != 0:
        message = completed.stderr.strip()
        permanent = (
            "couldn't find remote ref",
            "could not read Username",
            "authentication failed",
            "permission denied",
            "repository not found",
        )
        if any(marker.lower() in message.lower() for marker in permanent):
            abort(f"cannot follow origin/{branch}: {message}")
        raise ConnectionError(message or "git fetch failed")
    return git("rev-parse", private_ref, capture=True).stdout.strip()


def reset_owned_worktree(worktree: Path, commit: str) -> None:
    git("-C", str(worktree), "reset", "--hard", commit)


def available_loopback_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def watch_dev_loop(port: int, ready: threading.Event, unavailable: threading.Event) -> None:
    endpoint = f"http://127.0.0.1:{port}/v2/events"
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(endpoint, timeout=5) as response:
                for encoded in response:
                    try:
                        payload = json.loads(encoded)
                        event = payload.get("result", payload).get("event", payload)
                        task = event.get("taskEvent", {})
                    except (AttributeError, json.JSONDecodeError):
                        unavailable.set()
                        return
                    if task.get("task") == "DevLoop" and task.get("status") == "Succeeded":
                        ready.set()
                        return
                unavailable.set()
                return
        except (ConnectionError, OSError, urllib.error.URLError):
            time.sleep(0.1)
    unavailable.set()


def start_skaffold(
    worktree: Path, local: LocalEnvironment
) -> tuple[subprocess.Popen[str], threading.Event, threading.Event]:
    port = available_loopback_port()
    process = subprocess.Popen(
        [
            tool("skaffold"),
            "dev",
            "--profile",
            "local-kind",
            "--kube-context",
            local.context,
            "--port-forward",
            "--cleanup=true",
            "--rpc-http-port",
            str(port),
        ],
        cwd=worktree,
        env=local.environment,
        text=True,
    )
    ready = threading.Event()
    unavailable = threading.Event()
    threading.Thread(
        target=watch_dev_loop,
        args=(port, ready, unavailable),
        daemon=True,
    ).start()
    return process, ready, unavailable


def stop_skaffold(process: subprocess.Popen[str]) -> None:
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def follow_branch(arguments: argparse.Namespace) -> None:
    branch_check = git(
        "check-ref-format", "--branch", arguments.branch, capture=True, check=False
    )
    if branch_check.returncode != 0:
        abort(f"invalid branch name: {arguments.branch!r}")
    git("remote", "get-url", "origin", capture=True)
    local = local_environment(arguments.context)
    require_local_preflight(local)
    bootstrap_local_data(local)

    private_ref = f"refs/skywright-follow/{uuid.uuid4().hex}"
    worktree = Path(tempfile.mkdtemp(prefix="skywright-follow-"))
    created = False
    process: subprocess.Popen[str] | None = None
    backoff = arguments.poll_seconds
    previous = signal.getsignal(signal.SIGTERM)

    def request_stop(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, request_stop)
    try:
        while True:
            try:
                desired_commit = fetch_branch(arguments.branch, private_ref)
                break
            except ConnectionError as error:
                print(f"transient fetch failure: {error}; retrying", file=sys.stderr)
                time.sleep(backoff)
                backoff = min(backoff * 2, 30)
        git("worktree", "add", "--detach", str(worktree), desired_commit)
        created = True
        deployed_commit = desired_commit
        print(f"following origin/{arguments.branch} at {desired_commit}")
        process, ready, unavailable = start_skaffold(worktree, local)
        pending_before_ready = False
        fallback_used = False
        while process.poll() is None:
            time.sleep(arguments.poll_seconds)
            try:
                fetched_commit = fetch_branch(arguments.branch, private_ref)
            except ConnectionError as error:
                print(f"transient fetch failure: {error}; deployed commit unchanged")
                time.sleep(backoff)
                backoff = min(backoff * 2, 30)
                continue
            backoff = arguments.poll_seconds
            if fetched_commit != desired_commit:
                ancestor = git(
                    "merge-base",
                    "--is-ancestor",
                    desired_commit,
                    fetched_commit,
                    check=False,
                )
                if ancestor.returncode != 0:
                    print(
                        f"origin/{arguments.branch} moved non-fast-forward "
                        f"from {desired_commit} to {fetched_commit}"
                    )
                desired_commit = fetched_commit
                if not ready.is_set():
                    pending_before_ready = True
            if ready.is_set() and desired_commit != deployed_commit:
                reset_owned_worktree(worktree, desired_commit)
                deployed_commit = desired_commit
                print(f"updated owned worktree to {deployed_commit}")
            elif unavailable.is_set() and pending_before_ready and not fallback_used:
                stop_skaffold(process)
                reset_owned_worktree(worktree, desired_commit)
                deployed_commit = desired_commit
                fallback_used = True
                pending_before_ready = False
                process, ready, unavailable = start_skaffold(worktree, local)
        if process.returncode not in {0, -signal.SIGTERM}:
            abort(f"Skaffold exited with status {process.returncode}")
    except KeyboardInterrupt:
        pass
    finally:
        signal.signal(signal.SIGTERM, previous)
        if process is not None:
            stop_skaffold(process)
        if created and not arguments.keep_worktree:
            git("worktree", "remove", "--force", str(worktree), check=False)
        elif created:
            print(f"retained supervisor worktree: {worktree}")
        git("update-ref", "-d", private_ref, check=False)
        if not created:
            try:
                worktree.rmdir()
            except OSError:
                pass


def production_preflight(context: str) -> None:
    namespace = json.loads(
        run(
            [
                tool("kubectl"),
                "--context",
                context,
                "get",
                "namespace",
                NAMESPACE,
                "--output",
                "json",
            ],
            capture=True,
        ).stdout
    )
    if namespace.get("metadata", {}).get("name") != NAMESPACE:
        abort("the production skywright Namespace does not exist")
    secret = json.loads(
        run(
            [
                tool("kubectl"),
                "--context",
                context,
                "get",
                "secret",
                "skywright-production-database",
                "--namespace",
                NAMESPACE,
                "--output",
                "json",
            ],
            capture=True,
        ).stdout
    )
    if set(secret.get("data", {})) != PRODUCTION_SECRET_KEYS:
        abort(
            "skywright-production-database must contain exactly the six database configuration keys"
        )
    skypilot_secret_result = run(
        [
            tool("kubectl"),
            "--context",
            context,
            "get",
            "secret",
            "skywright-production-skypilot-database",
            "--namespace",
            NAMESPACE,
            "--output",
            "json",
        ],
        capture=True,
        check=False,
    )
    if skypilot_secret_result.returncode != 0:
        abort("the production SkyPilot database Secret does not exist")
    skypilot_secret = json.loads(skypilot_secret_result.stdout)
    if set(skypilot_secret.get("data", {})) != PRODUCTION_SKYPILOT_SECRET_KEYS:
        abort(
            "skywright-production-skypilot-database must contain exactly the connectionUri key"
        )
    skypilot_state_result = run(
        [
            tool("kubectl"),
            "--context",
            context,
            "get",
            "persistentvolumeclaim",
            "skywright-skypilot-state",
            "--namespace",
            NAMESPACE,
            "--output",
            "json",
        ],
        capture=True,
        check=False,
    )
    if skypilot_state_result.returncode != 0:
        abort("the production SkyPilot retained-state persistent volume claim does not exist")
    skypilot_state = json.loads(skypilot_state_result.stdout)
    if skypilot_state.get("metadata", {}).get("name") != "skywright-skypilot-state":
        abort("the production SkyPilot retained-state persistent volume claim is invalid")
    ingress_class = json.loads(
        run(
            [
                tool("kubectl"),
                "--context",
                context,
                "get",
                "ingressclass",
                "contour",
                "--output",
                "json",
            ],
            capture=True,
        ).stdout
    )
    if ingress_class.get("metadata", {}).get("name") != "contour":
        abort("the contour IngressClass does not exist")


def apply_bundle(arguments: argparse.Namespace) -> None:
    if BUNDLE_PATTERN.fullmatch(arguments.bundle) is None:
        abort("deployment bundle must use the canonical repository and an OCI sha256 digest")
    oras_version = run([tool("oras"), "version"], capture=True).stdout
    if f"Version: {ORAS_VERSION}" not in oras_version:
        abort(f"ORAS {ORAS_VERSION} is required")
    skaffold_version = run(
        [tool("skaffold"), "version"], capture=True
    ).stdout.strip()
    if skaffold_version != SKAFFOLD_VERSION:
        abort(f"Skaffold {SKAFFOLD_VERSION} is required, got {skaffold_version!r}")
    run(
        [
            tool("gh"),
            "attestation",
            "verify",
            f"oci://{arguments.bundle}",
            "--repo",
            "Zorro909/skywright",
            "--signer-workflow",
            "Zorro909/skywright/.github/workflows/deployment-release.yml",
            "--deny-self-hosted-runners",
        ]
    )
    with tempfile.TemporaryDirectory(prefix="skywright-bundle-") as directory:
        bundle = Path(directory)
        run(
            [tool("oras"), "pull", arguments.bundle, "--output", str(bundle)]
        )
        verification = [
            str(REPOSITORY / "deployment" / "scripts" / "release-support"),
            "verify-bundle",
            "--directory",
            str(bundle),
        ]
        if arguments.allow_prerelease:
            verification.append("--allow-prerelease")
        run(verification, capture=True)
        production_preflight(arguments.context)
        previous = run(
            [
                tool("kubectl"),
                "--context",
                arguments.context,
                "get",
                "deployment",
                "skywright-backend",
                "--namespace",
                NAMESPACE,
                "--output",
                "jsonpath={.metadata.annotations.skywright\\.io/deployment-bundle}",
                "--ignore-not-found",
            ],
            capture=True,
        ).stdout.strip()
        print(f"currently deployed bundle: {previous or '<none>'}")
        run(
            [
                tool("skaffold"),
                "apply",
                str(bundle / "release.yaml"),
                "--kube-context",
                arguments.context,
                "--status-check=true",
            ]
        )
        run(
            [
                tool("kubectl"),
                "--context",
                arguments.context,
                "annotate",
                "deployment/skywright-backend",
                "--namespace",
                NAMESPACE,
                f"skywright.io/deployment-bundle={arguments.bundle}",
                "--overwrite",
            ]
        )
        print(f"deployed bundle: {arguments.bundle}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="scripts/deploy")
    commands = result.add_subparsers(required=True)
    local = commands.add_parser("local", help="run the local kind development stack")
    local.add_argument("--context", required=True)
    local.set_defaults(function=local_command)
    reset = commands.add_parser(
        "reset-local-state", help="delete only retained local control-plane state"
    )
    reset.add_argument("--context", required=True)
    reset.add_argument("--confirm")
    reset.set_defaults(function=reset_local_state)
    follow = commands.add_parser(
        "follow", help="deploy and follow one branch from the fixed origin remote"
    )
    follow.add_argument("branch")
    follow.add_argument("--context", required=True)
    follow.add_argument("--poll-seconds", type=float, default=30.0)
    follow.add_argument("--keep-worktree", action="store_true")
    follow.set_defaults(function=follow_branch)
    apply = commands.add_parser(
        "apply", help="verify and apply a production Deployment Bundle by digest"
    )
    apply.add_argument("bundle")
    apply.add_argument("--context", required=True)
    apply.add_argument("--allow-prerelease", action="store_true")
    apply.set_defaults(function=apply_bundle)
    return result


def main() -> None:
    arguments = parser().parse_args()
    try:
        arguments.function(arguments)
    except (json.JSONDecodeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"deployment command failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
