"""The supported installation sequence, using the application catalogs."""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from . import local_catalog as catalog, local_control as control
from .local_dataset import publish
from .local_host import target
from .local_operations import api, command
from .local_package import configuration, installation_lock, registry_inputs, protected_json
from .local_release import fetch
from .local_resources import infrastructure, provisioner_access, resource
from .local_secrets import Vault, material, project_inputs, qualify_registry, write_private


def record(path: Path, value: dict) -> None:
    temporary = path.with_suffix(".partial")
    if temporary.exists():
        temporary.unlink()
    write_private(temporary, json.dumps(value, sort_keys=True).encode())
    temporary.replace(path)


def provision(settings: dict, directory: Path, release: Path, metadata: dict, registry: dict) -> None:
    source = release / "code"
    from . import local_vast
    vast = local_vast.selection(settings)
    root = Path(settings["secretDirectory"])
    print("Verifying the dedicated AMD target", flush=True)
    kube = target(settings, directory)
    evidence = qualify_registry(registry)
    inputs = material(root, registry)
    kube.apply(resource("Namespace", "skywright", namespace=""),
               resource("Namespace", "skywright-training", namespace=""))
    project_inputs(kube, root, inputs)
    kube.apply(*infrastructure(), *provisioner_access())
    print("Preparing retained Vault and object storage", flush=True)
    kube.rollout("skywright-vault")
    vault = Vault(kube, root)
    vault.initialize()
    kube.run("rollout", "restart", "deployment/skywright-storage", "-n", "skywright")
    kube.rollout("skywright-storage")
    for name, value in inputs["s3"].items():
        if name != "operator":
            vault.store(name, value)
    for role, value in registry.items():
        vault.store("ghcr-" + role, value)
    control.kubernetes_credential(kube, settings, vault)
    backend_paths = [name for name in inputs["s3"] if name != "operator"]
    backend_paths += ["ghcr-resolver", "ghcr-pull", "skypilot-backend"]
    for consumer, paths in (("backend", backend_paths), ("skypilot", ["skypilot-kubernetes"])):
        token = vault.consumer("skywright-" + consumer, paths,
                               provider_path=local_vast.VAULT_PATH if consumer == "skypilot" and vast else None)
        kube.secret("skywright", "skywright-" + consumer + "-vault", {"token": token})
    pull = root / "control-image-pull.json"
    if not pull.exists():
        value = registry["pull"]
        encoded = base64.b64encode((value["username"] + ":" + value["token"]).encode()).decode()
        write_private(pull, json.dumps({"auths": {"ghcr.io": {"auth": encoded}}}).encode())
    kube.secret("skywright", "skywright-control-pull", {".dockerconfigjson": pull},
                "kubernetes.io/dockerconfigjson")
    storage_path = directory / "storage.json"
    storage = protected_json(storage_path) if storage_path.exists() else {}
    demonstration_path = directory / "demonstration.json"
    application = catalog.demonstration(protected_json(demonstration_path)["definitionId"]) if demonstration_path.exists() else {}
    control.configuration(kube, catalog.bindings(storage, evidence), application, settings)
    print("Starting PostgreSQL, SkyPilot and the backend", flush=True)
    control.apply(kube, settings, metadata, source)
    control.verify_controller(kube, settings)
    provider_projection = local_vast.verify(kube, settings, directory)
    provider_bindings = [provider_projection["binding"]] if provider_projection else []
    print("Preparing backend access to SkyPilot", flush=True)
    control.skypilot_credential(kube, root, vault)
    with kube.forward("skywright-backend", 80) as endpoint:
        storage = catalog.enroll_storage(endpoint)
    record(storage_path, storage)
    control.configuration(kube, catalog.bindings(storage, evidence) + provider_bindings, application, settings)
    kube.run("rollout", "restart", "deployment/skywright-backend", "-n", "skywright")
    kube.rollout("skywright-backend")
    print("Qualifying storage and enrolling the supplied project and Dataset", flush=True)
    with kube.forward("skywright-backend", 80) as endpoint:
        catalog.activate_storage(endpoint, storage)
        catalog.enroll_project(endpoint)
        definition = publish(kube, endpoint, source, directory, root, settings, inputs, storage["dataset"])
    control.configuration(kube, catalog.bindings(storage, evidence) + provider_bindings, catalog.demonstration(definition), settings)
    control.writer(kube, settings, metadata, source)
    kube.run("rollout", "restart", "deployment/skywright-backend", "-n", "skywright")
    kube.rollout("skywright-backend")
    from .local_service import observe
    observe(kube, settings)
    with kube.forward("skywright-backend", 80) as endpoint:
        api(endpoint, "/actuator/health")
        api(endpoint, "/api/v1/dataset-catalog/" + definition)
    record(directory / "configuration.json", settings)


def finish(settings: dict, directory: Path, release: Path, metadata: dict) -> None:
    from .local_service import install as service
    service(settings, directory, release / "code")
    record(directory / "installed.json", {"schemaVersion": 1, "release": settings["release"],
            "version": metadata["version"], "sourceCommit": metadata["sourceCommit"], "configuration": settings})
    print("Installed " + metadata["version"] + "; run preflight before submitting the demonstration", flush=True)


def apply_verified(settings: dict, directory: Path, release: Path) -> None:
    """Execute the requested, verified package while the caller holds the lifecycle lock."""
    requested = directory / "requested-configuration.json"
    record(requested, settings)
    script = """
import json, sys
from pathlib import Path
release = Path(sys.argv[1])
sys.path.insert(0, str(release / 'code/deployment'))
from skywright_deployment.local_installation import provision, finish
from skywright_deployment.local_package import configuration, registry_inputs
settings = configuration(Path(sys.argv[2]))
directory = Path(settings['stateDirectory'])
metadata = json.loads((release / 'payload/release-metadata.json').read_text())
provision(settings, directory, release, metadata, registry_inputs(settings))
finish(settings, directory, release, metadata)
"""
    result = command([sys.executable, "-c", script, str(release), str(requested)], timeout=3600,
                     allow_failure=True)
    # Only our package's phase messages are forwarded, never arbitrary provider diagnostics.
    for line in result.stdout.decode(errors="replace").splitlines():
        if line.startswith(("Verifying ", "Preparing ", "Starting ", "Qualifying ", "Installed ")):
            print(line, flush=True)
    if result.returncode:
        raise SystemExit("Requested package setup failed; inspect its current phase and retry the same release")


def install(arguments) -> None:
    settings = configuration(arguments.configuration)
    registry = registry_inputs(settings)
    with installation_lock(settings) as directory:
        if (directory / "pending-update.json").exists():
            raise SystemExit("An update is incomplete; retry update with its recorded release or follow checkpoint recovery instructions")
        installed = directory / "installed.json"
        if installed.exists() and any(protected_json(installed)["configuration"][key] != settings[key]
                                      for key in settings if key != "release"):
            raise SystemExit("Retained installation configuration changed; restore its recorded configuration")
        if installed.exists() and protected_json(installed)["release"] != settings["release"]:
            raise SystemExit("An instance is installed; use update to checkpoint retained state before changing releases")
        if installed.exists():
            print("The requested digest is already installed; use start or preflight")
            return
        print("Verifying the digest-pinned release and its provenance", flush=True)
        release, metadata = fetch(settings, directory, registry["resolver"])
        apply_verified(settings, directory, release)
