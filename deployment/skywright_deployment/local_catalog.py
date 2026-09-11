"""Enroll the supplied publication through the application catalogs."""

from __future__ import annotations

import datetime
import uuid

from .local_operations import api
from .local_roles import PROJECT, ROLE_NAMES, S3_ROLES

REPOSITORY = "ghcr.io/zorro909/skywright-cifar10-private-qualification"
MANIFEST = "sha256:ce9dd756320d31cfcee5f5980e4b4d9746a8eff5831693e176f93ed0838c6ca9"
IMAGE = REPOSITORY + "@sha256:05e92ec83c366bcb122011605d0ba32537cd7c717ba02cb3d9d0c3faf3247853"
SKYPILOT_IDENTITY = "sa-skywright-local"
STORAGE_ENDPOINT = "http://skywright-storage.skywright.svc:8333"


def binding_id(name: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "https://skywright.internal/local-bindings/" + name))


def bindings(storage_ids: dict, registry: dict) -> list[dict]:
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    result = []
    def add(name, kind, target, role, identity, scope, profile, expiry=None):
        result.append({
            "id": binding_id(name), "revision": 1, "path": "local/" + name,
            "kind": kind, "resource": target, "role": role, "identity": identity,
            "scope": scope, "accessProfile": profile, "validatedAt": now,
            "validUntil": None, "nonExpiring": True, **(expiry or {}),
        })
    for purpose, storage_id in storage_ids.items():
        bucket = "skywright-datasets" if purpose == "dataset" else "skywright-runs"
        for role, name in ROLE_NAMES[purpose].items():
            scope, _, profile = S3_ROLES[name]
            add(name, "S3", storage_id, role, name, scope, profile)
    for role, consumer in (("resolver", "backend-resolver"), ("pull", "execution-target-pull")):
        evidence = registry[role]
        add("ghcr-" + role, "GHCR", REPOSITORY, consumer, evidence["identity"],
            REPOSITORY.removeprefix("ghcr.io/"), "read-only",
            {"validUntil": evidence["validUntil"], "nonExpiring": evidence["nonExpiring"]})
    add("skypilot-kubernetes", "KUBERNETES", "https://kubernetes.default.svc:443",
        "skypilot-api-server", "skywright-provisioner", "skywright-training namespace and cluster resource reads",
        "namespace-provisioner")
    add("skypilot-backend", "SKYPILOT", "http://skywright-skypilot-api-server:46580",
        "backend", SKYPILOT_IDENTITY, "managed jobs in the local server", "managed-jobs")
    return result


def enroll_storage(endpoint: str) -> dict:
    current = api(endpoint, "/api/v1/target-storages")
    result = {}
    for purpose, bucket in (("dataset", "skywright-datasets"), ("run", "skywright-runs")):
        found = [item for item in current if item["name"] == "Installed " + purpose + " storage"]
        expected_purpose = "dataset" if purpose == "dataset" else "run-output"
        if len(found) > 1:
            raise SystemExit("The installed storage name is ambiguous")
        if found:
            item = found[0]
            if (item["bucket"] != bucket or item["purpose"] != expected_purpose
                    or item["configuration"]["endpoint"] != STORAGE_ENDPOINT):
                raise SystemExit("Installed storage differs from the package; resolve the configuration before updating")
        else:
            item = api(endpoint, "/api/v1/target-storages", "POST", {
                "name": "Installed " + purpose + " storage", "purpose": expected_purpose, "bucket": bucket,
                "configuration": {"endpoint": STORAGE_ENDPOINT, "region": "us-east-1",
                                  "pathStyleAccess": True, "compatibilityOptions": {}},
                "bindings": [{"role": role, "bindingId": binding_id(name), "bindingRevision": 1}
                             for role, name in ROLE_NAMES[purpose].items()],
            })
        result[purpose] = item["id"]
    return result


def activate_storage(endpoint: str, storage_ids: dict) -> None:
    for identity in storage_ids.values():
        path = "/api/v1/target-storages/" + identity
        current = api(endpoint, path)
        if not current["activated"]:
            current = api(endpoint, path + "/qualification", "POST")
            current = api(endpoint, path + "/activation", "PUT",
                          {"expectedRegistrationRevision": current["registrationRevision"], "activated": True})
        if not current["eligible"]:
            raise SystemExit("Installed storage did not pass application qualification")
    api(endpoint, "/api/v1/target-storage-defaults/local-single-gpu", "PUT", {
        "executionStorageId": storage_ids["run"], "repatriationEnabled": False,
        "repatriationStorageId": storage_ids["run"]})


def enroll_project(endpoint: str) -> None:
    api(endpoint, "/api/v1/training-projects/import", "POST", {
        "projectId": PROJECT, "displayName": "CIFAR-10 demonstration", "manifestArtifactDigest": MANIFEST,
        "registry": {"repository": REPOSITORY, "accessMode": "private",
                     "resolverCredentialBindingId": binding_id("ghcr-resolver"),
                     "executionCredentialBindingId": binding_id("ghcr-pull")},
    })


def demonstration(definition: str) -> dict:
    return {"skywright": {"demonstration": {
        "training-project-id": PROJECT, "manifest-artifact-digest": MANIFEST,
        "dataset-definition-id": definition, "display-name": "CIFAR-10: 12 steps on one GPU",
        "configuration": {"project": {"steps": 12, "outputEvery": 4}},
    }}}
