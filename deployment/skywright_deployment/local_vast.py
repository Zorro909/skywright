"""Project an explicitly selected Vault revision into the SkyPilot provider role."""

from __future__ import annotations

import datetime
import json
import re
import subprocess
import uuid
from pathlib import Path

from .local_catalog import binding_id

VAULT_PATH = "provider/vast/on-demand"
DESTINATION = "/var/lib/skypilot/.config/vastai/vast_api_key"
# Vast includes these baseline operations in addition to the requested categories.
# Refuse unexpected grants until the operator has reviewed their meaning.
BASELINE_RIGHTS = {
    "api.team": {"POST": {}}, "api.ssh.keys": {}, "api.template": {"GET": {}},
    "api.instances": {"GET": {}}, "api.users.root": {"GET": {}}, "api.template_v1": {"GET": {}},
    "api.auth.apikeys": {"GET": {}}, "api.instances_v1": {"GET": {}}, "api.auth.sessions": {"GET": {}},
    "api.users.current": {"GET": {}}, "api.instances.count": {"GET": {}},
    "api.template.params": {"GET": {}}, "api.user.save_context": {"PUT": {}},
    "api.user.verify-email": {"POST": {}}, "api.hubspot.newsletters": {"GET": {}},
    "api.users.crisp-identity": {"GET": {}}, "api.user.created_templates": {"GET": {}},
}


def selection(settings: dict) -> dict | None:
    value = settings.get("vastProvider")
    if value is None:
        return None
    if (not isinstance(value, dict) or set(value) != {"revision", "identity", "providerKeyId", "enrolledAt", "requestedPermissions"}
            or type(value["revision"]) is not int or value["revision"] < 1
            or not isinstance(value["identity"], str)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", value["identity"])):
        raise SystemExit("Vast provider selection requires exact non-secret enrollment evidence")
    expected = {"api": {name: {} for name in ("misc", "user_read", "instance_read", "instance_write")}}
    try:
        enrolled = datetime.datetime.fromisoformat(value["enrolledAt"])
        if (enrolled.tzinfo is None or enrolled > datetime.datetime.now(datetime.timezone.utc)
                or not str(value["providerKeyId"]).isdigit() or value["requestedPermissions"] != expected):
            raise ValueError()
    except (TypeError, ValueError):
        raise SystemExit("Vast enrollment evidence must record the accepted scoped creation request") from None
    return value


def binding(settings: dict, observed: dict) -> dict | None:
    value = selection(settings)
    if value is None:
        return None
    return {"id": binding_id("vast-provider"), "revision": value["revision"], "path": VAULT_PATH,
            "kind": "VAST", "resource": "vast", "role": "skypilot-api-server",
            "identity": value["identity"], "scope": json.dumps(observed["effectivePermissions"], sort_keys=True),
            "accessProfile": "provision-and-cleanup", "validatedAt": observed["observedAt"],
            # This is a conservative deployment validity window, not a provider expiry claim.
            "validUntil": (datetime.datetime.fromisoformat(value["enrolledAt"])
                           + datetime.timedelta(hours=24)).isoformat(), "nonExpiring": False}


def template(settings: dict) -> str:
    value = selection(settings)
    if value is None:
        return ""
    return '''template {
  contents = <<VASTKEY
{{ with secret "skywright/data/''' + VAULT_PATH + '?version=' + str(value["revision"]) + '''" }}{{ .Data.data.apiKey }}{{ end }}
VASTKEY
  destination = "/run/skywright-vast/vast_api_key"
  perms = "0400"
  error_on_missing_key = true
}
'''


def verify(kube, settings: dict, directory: Path) -> dict | None:
    """Read-only provider operations in the API-server role; stdout is an allowlist."""
    value = selection(settings)
    if value is None:
        return None
    projected = observe_projection(kube, settings, directory)
    from .local_installation import record
    records = directory / "credential-projections"
    try:
        observed = validate(kube, value)
    except (SystemExit, OSError, ValueError, subprocess.TimeoutExpired):
        failed = records / (projected["id"] + ".validation-failed.json")
        if not failed.exists():
            record(failed, {"projectionId": projected["id"], "status": "unavailable",
                           "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                           "code": "VAST_PROVIDER_VALIDATION_UNAVAILABLE"})
        return None
    # Every validation is a separate observation. Never replace earlier evidence.
    record(records / (projected["id"] + "." + str(uuid.uuid4()) + ".validation.json"), observed)
    return {**projected, "binding": binding(settings, observed), "validation": observed}


def validate(kube, value: dict) -> dict:
    """Failure leaves only the optional provider binding unavailable."""
    if (datetime.datetime.fromisoformat(value["enrolledAt"]) + datetime.timedelta(hours=24)
            <= datetime.datetime.now(datetime.timezone.utc)):
        raise SystemExit("Vast enrollment evidence requires renewal before provider validation")
    script = '''
import datetime, hashlib, json, logging, os, stat, sys
from pathlib import Path
logging.disable(logging.CRITICAL)
try:
    path = Path("/var/lib/skypilot/.config/vastai/vast_api_key")
    metadata = path.stat()
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o400 or metadata.st_uid != os.getuid():
        raise ValueError()
    fingerprint = "sha256:" + hashlib.sha256(path.read_text().strip().encode()).hexdigest()
    if fingerprint != sys.argv[1]: raise ValueError()
    from sky.adaptors import vast
    provider = vast.vast()
    account = provider.show_user()
    instances = provider.show_instances()
    if not isinstance(account, dict) or "credit" not in account or not isinstance(instances, list): raise ValueError()
    if str(account.get("key_id")) != sys.argv[2]: raise ValueError()
    expected_rights = json.loads(sys.argv[3])
    if account.get("rights") != expected_rights: raise ValueError()
    from decimal import Decimal
    credit = Decimal(str(account["credit"]))
    if not credit.is_finite() or credit < 0: raise ValueError()
    print(json.dumps({"identity": fingerprint, "accountReadVerified": True, "instanceReadVerified": True,
                      "observedCreditUsd": str(credit), "instanceCount": len(instances),
                      "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      "providerKeyId": str(account["key_id"]), "effectivePermissions": expected_rights,
                      "scopeEvidence": "provider-current-user-rights", "provisioningQualified": False}))
except BaseException:
    print("Provider projection verification failed; provider values suppressed.", file=sys.stderr)
    sys.exit(1)
'''
    expected = {"api": {**value["requestedPermissions"]["api"], "*": BASELINE_RIGHTS}}
    response = kube.run("exec", "-i", "deployment/skywright-skypilot-api-server", "-n", "skywright",
                        "-c", "skypilot-api-server", "--", "python", "-", value["identity"],
                        str(value["providerKeyId"]), json.dumps(expected),
                        data=script.encode(), allow_failure=True, timeout=45)
    if response.returncode:
        raise SystemExit("Vast provider projection or its read access is unavailable")
    observed = json.loads(response.stdout)
    if observed.get("identity") != value["identity"]:
        raise SystemExit("Vast provider projection identity differs from its selected binding")
    return observed


def observe_projection(kube, settings: dict, directory: Path) -> dict:
    """Record observed projection before validating its external permissions."""
    selected = selection(settings)
    pods = kube.read("pods", "-n", "skywright", "-l", "app.kubernetes.io/name=skywright-skypilot-api-server")
    active = [pod for pod in pods["items"] if not pod["metadata"].get("deletionTimestamp")
              and pod.get("status", {}).get("phase") == "Running"]
    if len(active) != 1:
        raise SystemExit("Vast provider consumer identity is ambiguous")
    pod = active[0]
    records = directory / "credential-projections"
    records.mkdir(mode=0o700, exist_ok=True)
    for path in records.glob("*.projection.json"):
        previous = json.loads(path.read_text())
        if previous["consumerId"] == pod["metadata"]["uid"]:
            if previous["selection"] != selected:
                raise SystemExit("The provider consumer still holds a different selected revision")
            return previous
    projected_at = next((item["state"]["terminated"]["finishedAt"]
        for item in pod.get("status", {}).get("initContainerStatuses", [])
        if item["name"] == "project-kubernetes-credential"
        and item.get("state", {}).get("terminated", {}).get("exitCode") == 0), None)
    if projected_at is None:
        raise SystemExit("Vault projection completion is unobserved")
    projected = {"id": str(uuid.uuid4()), "bindingId": binding_id("vast-provider"),
                 "selection": selected, "consumerRole": "skypilot-api-server",
                 "consumerId": pod["metadata"]["uid"], "destination": DESTINATION,
                 "projectedAt": projected_at, "paidLaunch": False}
    from .local_installation import record
    record(records / (projected["id"] + ".projection.json"), projected)
    return projected


def reconcile(kube, settings: dict, directory: Path) -> None:
    """Append observed releases and verify newly observed Pod consumers."""
    records = directory / "credential-projections"
    if selection(settings) is None and not records.exists():
        return
    pods = kube.read("pods", "-n", "skywright", "-l", "app.kubernetes.io/name=skywright-skypilot-api-server")
    present = {pod["metadata"]["uid"] for pod in pods["items"]}
    from .local_installation import record
    for path in records.glob("*.projection.json"):
        previous = json.loads(path.read_text())
        released = records / (previous["id"] + ".release.json")
        if previous["consumerId"] not in present and not released.exists():
            record(released, {"projectionId": previous["id"], "consumerId": previous["consumerId"],
                              "releasedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              "evidence": "consumer-pod-absent", "providerRevoked": False})
    if selection(settings) is not None and any(pod.get("status", {}).get("phase") == "Running"
                                              and not pod["metadata"].get("deletionTimestamp") for pod in pods["items"]):
        observed = observe_projection(kube, settings, directory)
        if not any(records.glob(observed["id"] + ".*.validation.json")):
            verify(kube, settings, directory)
