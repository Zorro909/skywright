"""Configure the existing backend and SkyPilot deployments for local admission."""

from __future__ import annotations

import base64
import json
import sys
import tempfile
import time
from pathlib import Path

from .local_catalog import SKYPILOT_IDENTITY, binding_id
from .local_operations import Kubernetes, command
from .local_resources import AGENT_CONFIGURATION, control_patches, resource
from .local_secrets import Vault, write_private
from .local_package import protected_json


def configuration(kube: Kubernetes, definitions: list[dict], application: dict) -> None:
    kube.apply(resource("ConfigMap", "skywright-local-configuration", data={
        "credential-bindings.json": json.dumps(definitions),
        "application.json": json.dumps(application),
        "agent.hcl": AGENT_CONFIGURATION,
    }))


def apply(kube: Kubernetes, settings: dict, metadata: dict, source: Path) -> None:
    # Its SQL is idempotent; recreating the owned setup Job applies any revised
    # immutable Pod template after the update's checkpoint.
    kube.run("delete", "job", "skywright-local-skypilot-database-provisioner", "-n", "skywright",
             "--ignore-not-found", "--wait=true", "--timeout=60s", timeout=70)
    with tempfile.TemporaryDirectory(prefix="skywright-render-") as temporary:
        root = Path(temporary)
        patches = []
        for index, patch in enumerate(control_patches(settings, binding_id("skypilot-backend"))):
            path = root / ("patch-" + str(index) + ".json")
            path.write_text(json.dumps(patch))
            patches.append({"path": path.name})
        configuration = {
            "apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization",
            "namespace": "skywright",
            "resources": [str(source / "deployment/overlays/local-kind")],
            "patches": patches,
            "images": [{"name": name, "newName": metadata[field].split("@")[0],
                        "digest": metadata[field].split("@")[1]}
                       for name, field in (("skywright-backend", "backendImage"),
                                           ("skywright-skypilot-api-server", "skypilotImage"))],
        }
        (root / "kustomization.yaml").write_text(json.dumps(configuration))
        manifest = command([kube.prefix[0], "kustomize", str(root), "--load-restrictor=LoadRestrictionsNone"]).stdout
        kube.run("apply", "-f", "-", data=manifest)
    kube.run("wait", "job/skywright-local-skypilot-database-provisioner", "-n", "skywright",
             "--for=condition=Complete", "--timeout=300s", timeout=310)
    kube.rollout("skywright-skypilot-api-server")
    kube.rollout("skywright-backend")


def kubernetes_credential(kube: Kubernetes, settings: dict, vault: Vault) -> None:
    stub = resource("Secret", "skywright-provisioner-token", namespace="skywright-training",
                    type="kubernetes.io/service-account-token")
    stub["metadata"]["annotations"] = {"kubernetes.io/service-account.name": "skywright-provisioner"}
    kube.apply(stub)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        secret = kube.read("secret", "skywright-provisioner-token", "-n", "skywright-training").get("data", {})
        if "token" in secret and "ca.crt" in secret:
            break
        time.sleep(1)
    else:
        raise SystemExit("Kubernetes did not issue the namespace provisioner's credential")
    config = {
        "apiVersion": "v1", "kind": "Config", "current-context": settings["context"],
        "clusters": [{"name": "local", "cluster": {"server": "https://kubernetes.default.svc:443",
                                                  "certificate-authority-data": secret["ca.crt"]}}],
        "users": [{"name": "skywright-provisioner", "user": {
            "token": base64.b64decode(secret["token"]).decode()}}],
        "contexts": [{"name": settings["context"], "context": {
            "cluster": "local", "user": "skywright-provisioner", "namespace": "skywright-training"}}],
    }
    vault.store("skypilot-kubernetes", {"kubeconfig": json.dumps(config)})


def skypilot_credential(kube: Kubernetes, root: Path, vault: Vault) -> None:
    path = root / "skypilot-backend.json"
    if not path.exists():
        # Use the pinned server's own credential persistence implementation.
        # stdout goes directly into a protected operator input, never the console.
        script = """
import json
from sky import global_user_state, models
from sky.users import token_service, permission
identity = "sa-skywright-local"
user = models.User(id=identity, name="skywright-local-backend", user_type=models.UserType.SA.value)
global_user_state.add_or_update_user(user, allow_duplicate_name=False)
permission.seed_new_user_role(identity)
value = token_service.token_service.create_token(
    creator_user_id=identity, service_account_user_id=identity,
    token_name="skywright-local-backend", expires_in_days=None)
global_user_state.add_service_account_token(
    token_id=value["token_id"], token_name="skywright-local-backend",
    token_hash=value["token_hash"], creator_user_hash=identity,
    service_account_user_id=identity, expires_at=value["expires_at"])
print(json.dumps({"token": value["token"]}))
"""
        response = kube.run("exec", "-i", "deployment/skywright-skypilot-api-server", "-n", "skywright",
                             "-c", "skypilot-api-server", "--", "python", "-", data=script.encode())
        value = json.loads(response.stdout)
        if set(value) != {"token"} or not isinstance(value["token"], str):
            raise SystemExit("SkyPilot did not issue its backend service credential")
        write_private(path, response.stdout)
    vault.store("skypilot-backend", protected_json(path))


def writer(kube: Kubernetes, settings: dict, metadata: dict, source: Path) -> None:
    node = kube.read("node", settings["node"])
    rendered = command([sys.executable, str(source / "deployment/scripts/render-local-writer"),
                        "--node", settings["node"], "--node-uid", node["metadata"]["uid"],
                        "--image", metadata["writerImage"]]).stdout
    kube.run("apply", "-f", "-", data=rendered)
    kube.run("rollout", "status", "daemonset/skywright-local-writer", "-n", "skywright",
             "--timeout=300s", timeout=310)
