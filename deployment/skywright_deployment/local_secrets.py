"""Operator input custody and exact Vault policies for one retained local instance."""

from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

from .local_operations import Kubernetes, command
from .local_package import protected_json
from .local_roles import S3_ROLES, actions


def write_private(path: Path, data: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def material(root: Path, registry: dict) -> dict:
    path = root / "generated-inputs.json"
    if path.exists():
        value = protected_json(path)
        if value.get("registry") != registry:
            raise SystemExit("Registry inputs changed; rotate their Vault bindings before updating the installation")
        return value
    password = lambda: secrets.token_urlsafe(32)
    identities = {name: {"accessKeyId": name, "secretAccessKey": password()} for name in S3_ROLES}
    value = {"schemaVersion": 1, "registry": registry, "s3": identities,
             "database": {"administratorPassword": password(), "migrationUsername": "skywright_migration",
                          "migrationPassword": password(), "runtimeUsername": "skywright_runtime",
                          "runtimePassword": password()},
             "skypilotPassword": password()}
    write_private(path, json.dumps(value).encode())
    return value


def project_inputs(kube: Kubernetes, root: Path, inputs: dict) -> None:
    """Files are retained operator inputs; Kubernetes receives file references only."""
    def project(name: str, values: dict[str, str]):
        directory = root / name
        directory.mkdir(mode=0o700, exist_ok=True)
        for key, value in values.items():
            path = directory / key
            if name == "skywright-storage-iam" and path.exists() and path.read_text() != value:
                from .local_installation import record
                record(path, json.loads(value))
                kube.run("delete", "secret", name, "-n", "skywright", "--ignore-not-found")
            elif not path.exists():
                write_private(path, value.encode())
        kube.secret("skywright", name, {key: directory / key for key in values})

    project("skywright-local-database", inputs["database"])
    password = inputs["skypilotPassword"]
    project("skywright-local-skypilot-database", {
        "password": password,
        "connectionUri": "postgresql://skypilot:" + password + "@skywright-postgresql:5432/skypilot"})
    identities = []
    for name, value in inputs["s3"].items():
        identities.append({"name": name, "credentials": [{"accessKey": value["accessKeyId"],
                           "secretKey": value["secretAccessKey"]}], "actions": actions(name)})
    project("skywright-storage-iam", {"s3.json": json.dumps({"identities": identities})})

    tls = root / "skywright-vault-tls"
    tls.mkdir(mode=0o700, exist_ok=True)
    if not (tls / "tls.crt").exists():
        # Keep generation in a protected directory. The private key is never a process argument.
        command(["openssl", "req", "-x509", "-nodes", "-newkey", "rsa:3072", "-days", "365",
                 "-keyout", str(tls / "tls.key"), "-out", str(tls / "tls.crt"),
                 "-subj", "/CN=skywright-vault.skywright.svc",
                 "-addext", "subjectAltName=DNS:skywright-vault,DNS:skywright-vault.skywright.svc",
                 "-addext", "basicConstraints=critical,CA:TRUE"])
        (tls / "tls.key").chmod(0o600)
        (tls / "tls.crt").chmod(0o600)
    kube.secret("skywright", "skywright-vault-tls", {key: tls / key for key in ("tls.crt", "tls.key")})
    from .local_resources import resource
    kube.apply(resource("ConfigMap", "skywright-vault-ca", data={"ca.crt": (tls / "tls.crt").read_text()}))


class Vault:
    def __init__(self, kube: Kubernetes, root: Path):
        self.kube = kube
        self.root = root
        self.token = ""

    def run(self, *arguments: str, value: dict | None = None, authenticated: bool = True,
            allow_failure: bool = False):
        script = ('read -r VAULT_TOKEN; export VAULT_TOKEN; '
                  'export VAULT_ADDR=https://127.0.0.1:8200 VAULT_TLS_SERVER_NAME=skywright-vault.skywright.svc '
                  'VAULT_CACERT=/vault/tls/tls.crt; exec vault "$@"')
        data = (self.token if authenticated else "") + "\n"
        if value is not None:
            data += json.dumps(value)
        return self.kube.run("exec", "-i", "deployment/skywright-vault", "-n", "skywright", "--",
                             "sh", "-c", script, "--", *arguments, data=data.encode(),
                             allow_failure=allow_failure)

    def initialize(self) -> None:
        observed = json.loads(self.run("status", "-format=json", authenticated=False, allow_failure=True).stdout)
        path = self.root / "vault-recovery.json"
        if not observed["initialized"]:
            if path.exists():
                raise SystemExit("Vault data disappeared while recovery inputs remain; restore the retained checkpoint")
            response = self.run("operator", "init", "-format=json", "-key-shares=1", "-key-threshold=1",
                                authenticated=False)
            write_private(path, response.stdout)
        recovery = protected_json(path)
        if observed["sealed"]:
            self.run("write", "sys/unseal", "-", value={"key": recovery["unseal_keys_b64"][0]},
                     authenticated=False)
        self.token = recovery["root_token"]
        mounts = json.loads(self.run("secrets", "list", "-format=json").stdout)
        if "skywright/" not in mounts:
            self.run("secrets", "enable", "-path=skywright", "kv-v2")

    def store(self, name: str, value: dict) -> None:
        existing = self.run("kv", "get", "-format=json", "skywright/local/" + name, allow_failure=True)
        if existing.returncode == 0:
            observed = json.loads(existing.stdout)["data"]
            if observed["metadata"]["version"] != 1 or observed["data"] != value:
                raise SystemExit("Installed Vault input differs; resolve credential rotation before proceeding")
            return
        # CAS zero refuses overwriting retained or concurrently provisioned credentials.
        self.run("write", "skywright/data/local/" + name, "-",
                 value={"options": {"cas": 0}, "data": value})

    def consumer(self, name: str, paths: list[str]) -> Path:
        policy = "\n".join('path "skywright/data/local/' + path + '" { capabilities = ["read"] }'
                           for path in paths)
        # Policy text is non-secret and only grants exact KV paths.
        data = (self.token + "\n" + policy).encode()
        script = ('read -r VAULT_TOKEN; export VAULT_TOKEN; '
                  'export VAULT_ADDR=https://127.0.0.1:8200 VAULT_TLS_SERVER_NAME=skywright-vault.skywright.svc '
                  'VAULT_CACERT=/vault/tls/tls.crt; exec vault policy write "$1" -')
        self.kube.run("exec", "-i", "deployment/skywright-vault", "-n", "skywright", "--",
                      "sh", "-c", script, "--", name, data=data)
        path = self.root / (name + "-vault.json")
        if not path.exists():
            response = json.loads(self.run("token", "create", "-format=json", "-policy=" + name,
                                           "-period=768h", "-orphan").stdout)
            write_private(path, json.dumps({"token": response["auth"]["client_token"]}).encode())
        token = protected_json(path)["token"]
        projection = self.root / (name + "-vault-token")
        if not projection.exists():
            write_private(projection, token.encode())
        return projection


def qualify_registry(inputs: dict) -> dict:
    import datetime
    import hashlib
    import urllib.request
    result = {}
    for role, value in inputs.items():
        request = urllib.request.Request("https://api.github.com/user",
            headers={"Authorization": "Bearer " + value["token"], "Accept": "application/vnd.github+json"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                scopes = {scope.strip() for scope in response.headers.get("X-OAuth-Scopes", "").split(",") if scope.strip()}
                expiration = response.headers.get("GitHub-Authentication-Token-Expiration")
                account = json.loads(response.read(65536))
            if scopes != {"read:packages"} or account["login"].lower() != value["username"].lower():
                raise ValueError
            expiry = None if not expiration else datetime.datetime.fromisoformat(
                expiration.replace(" UTC", "+00:00")).isoformat()
            if expiry and datetime.datetime.fromisoformat(expiry) <= datetime.datetime.now(datetime.timezone.utc):
                raise ValueError
            identity = "github-user:" + str(account["id"]) + ":pat-sha256:" + hashlib.sha256(
                value["token"].encode()).hexdigest()
            result[role] = {"identity": identity, "validUntil": expiry, "nonExpiring": expiry is None}
        except (OSError, ValueError, KeyError, TypeError):
            raise SystemExit("Registry input must be a valid classic GitHub token with only read:packages scope") from None
    return result
