"""Non-secret local service resources; secret material enters through file projections."""

from __future__ import annotations

VAULT = "docker.io/hashicorp/vault:1.21.4@sha256:4e33b126a59c0c333b76fb4e894722462659a6bec7c48c9ee8cea56fccfd2569"
STORAGE = "docker.io/chrislusf/seaweedfs:4.42@sha256:f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6"
POSTGRES = "postgres:18.1-bookworm@sha256:cc9f4143a8d2fa8cf3749d0cb4d26ecf2d53a77a2ac807e9ebd67ae22426221a"
DEVICE_PLUGIN = "docker.io/rocm/k8s-device-plugin:1.31.0.10@sha256:0555caf9ccc1cf407b353d1aade87d4598059f87a784085aafe3ece19405b612"


def resource(kind: str, name: str, *, namespace: str = "skywright", **fields) -> dict:
    version = "apps/v1" if kind in {"Deployment", "DaemonSet"} else (
        "rbac.authorization.k8s.io/v1" if kind in {"Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding"} else (
            "networking.k8s.io/v1" if kind == "NetworkPolicy" else "v1"))
    metadata = {"name": name, "labels": {"app.kubernetes.io/part-of": "skywright"}}
    if namespace:
        metadata["namespace"] = namespace
    return {"apiVersion": version, "kind": kind, "metadata": metadata, **fields}


def claim(name: str, size: str) -> dict:
    item = resource("PersistentVolumeClaim", name, spec={
        "accessModes": ["ReadWriteOnce"], "resources": {"requests": {"storage": size}}})
    item["metadata"]["labels"]["skywright.io/local-retained-data"] = "true"
    return item


def service(name: str, port: int) -> dict:
    return resource("Service", name, spec={"selector": {"app": name},
                    "ports": [{"name": "http", "port": port, "targetPort": port}]})


def deployment(name: str, container: dict, volumes: list[dict]) -> dict:
    return resource("Deployment", name, spec={
        "replicas": 1, "strategy": {"type": "Recreate"},
        "selector": {"matchLabels": {"app": name}},
        "template": {"metadata": {"labels": {"app": name, "app.kubernetes.io/part-of": "skywright"}},
                     "spec": {"automountServiceAccountToken": False,
                              "terminationGracePeriodSeconds": 30,
                              "containers": [container], "volumes": volumes}}})


def infrastructure() -> list[dict]:
    vault_config = """disable_mlock = true
ui = false
storage "file" { path = "/vault/data" }
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_cert_file = "/vault/tls/tls.crt"
  tls_key_file = "/vault/tls/tls.key"
}
"""
    vault = deployment("skywright-vault", {
        "name": "vault", "image": VAULT, "command": ["vault", "server", "-config=/vault/config/vault.hcl"],
        "resources": {"requests": {"cpu": "250m", "memory": "256Mi"},
                      "limits": {"cpu": "1", "memory": "512Mi"}},
        "securityContext": {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}},
        "volumeMounts": [{"name": "config", "mountPath": "/vault/config", "readOnly": True},
                         {"name": "tls", "mountPath": "/vault/tls", "readOnly": True},
                         {"name": "data", "mountPath": "/vault/data"}],
        # Sealed Vault remains reachable so the installer can supply the operator's unseal key.
        "readinessProbe": {"tcpSocket": {"port": 8200}, "periodSeconds": 2},
    }, [{"name": "config", "configMap": {"name": "skywright-vault"}},
        {"name": "tls", "secret": {"secretName": "skywright-vault-tls", "defaultMode": 0o440}},
        {"name": "data", "persistentVolumeClaim": {"claimName": "skywright-vault-data"}}])
    vault["spec"]["template"]["spec"]["securityContext"] = {
        "runAsUser": 100, "runAsGroup": 100, "fsGroup": 100, "runAsNonRoot": True}
    storage = deployment("skywright-storage", {
        "name": "storage", "image": STORAGE,
        "args": ["mini", "-dir=/data", "-s3.config=/etc/seaweedfs/s3.json", "-master.telemetry=false",
                 "-bucket=skywright-datasets,skywright-runs", "-admin.ui=false",
                 "-s3.port.iceberg=0", "-webdav=false", "-s3.concurrentFileUploadLimit=2",
                 "-s3.concurrentUploadLimitMB=16", "-filer.concurrentFileUploadLimit=2",
                 "-filer.concurrentUploadLimitMB=16"],
        "resources": {"requests": {"cpu": "500m", "memory": "512Mi"},
                      "limits": {"cpu": "2", "memory": "1Gi"}},
        "volumeMounts": [{"name": "iam", "mountPath": "/etc/seaweedfs", "readOnly": True},
                         {"name": "data", "mountPath": "/data"}],
        "readinessProbe": {"tcpSocket": {"port": 8333}, "periodSeconds": 2},
    }, [{"name": "iam", "secret": {"secretName": "skywright-storage-iam", "defaultMode": 0o440}},
        {"name": "data", "persistentVolumeClaim": {"claimName": "skywright-storage-data"}}])
    storage["spec"]["template"]["spec"]["securityContext"] = {
        "runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000, "runAsNonRoot": True}
    return [
        resource("Namespace", "skywright", namespace=""),
        resource("Namespace", "skywright-training", namespace=""),
        claim("skywright-vault-data", "1Gi"), claim("skywright-storage-data", "32Gi"),
        claim("skywright-postgresql-data", "4Gi"), claim("skywright-skypilot-state", "4Gi"),
        resource("ConfigMap", "skywright-vault", data={"vault.hcl": vault_config}),
        vault, service("skywright-vault", 8200), storage, service("skywright-storage", 8333),
        resource("NetworkPolicy", "skywright-storage", spec={
            "podSelector": {"matchLabels": {"app": "skywright-storage"}}, "policyTypes": ["Ingress"],
            "ingress": [{"from": [{"podSelector": {}},
                {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "skywright-training"}}}],
                "ports": [{"protocol": "TCP", "port": 8333}]}]}),
        resource("NetworkPolicy", "skywright-vault", spec={
            "podSelector": {"matchLabels": {"app": "skywright-vault"}}, "policyTypes": ["Ingress"],
            "ingress": [{"from": [{"podSelector": {"matchLabels": {
                "app.kubernetes.io/name": name}}} for name in ("skywright-backend", "skywright-skypilot-api-server")],
                "ports": [{"protocol": "TCP", "port": 8200}]}]}),
    ]


def provisioner_access() -> list[dict]:
    name = "skywright-provisioner"
    subject = {"kind": "ServiceAccount", "name": name, "namespace": "skywright-training"}
    return [
        resource("ServiceAccount", name, namespace="skywright-training"),
        resource("Role", name, namespace="skywright-training", rules=[
            {"apiGroups": [""], "resources": ["pods", "pods/exec", "pods/log", "services", "secrets",
                                               "configmaps", "serviceaccounts", "events"],
             "verbs": ["get", "list", "watch", "create", "patch", "update", "delete"]},
            {"apiGroups": [""], "resources": ["services"], "verbs": ["deletecollection"]},
            {"apiGroups": ["rbac.authorization.k8s.io"], "resources": ["roles", "rolebindings"],
             "verbs": ["get", "list", "watch", "create", "patch", "update", "delete"]}]),
        resource("RoleBinding", name, namespace="skywright-training", subjects=[subject],
                 roleRef={"apiGroup": "rbac.authorization.k8s.io", "kind": "Role", "name": name}),
        resource("ClusterRole", name, namespace="", rules=[
            {"apiGroups": [""], "resources": ["nodes", "pods", "namespaces"],
             "verbs": ["get", "list", "watch"]},
            {"apiGroups": ["node.k8s.io"], "resources": ["runtimeclasses"], "verbs": ["get", "list", "watch"]},
            {"apiGroups": ["storage.k8s.io"], "resources": ["storageclasses"], "verbs": ["get", "list", "watch"]}]),
        resource("ClusterRoleBinding", name, namespace="", subjects=[subject],
                 roleRef={"apiGroup": "rbac.authorization.k8s.io", "kind": "ClusterRole", "name": name}),
    ]


def control_patches(settings: dict, skypilot_binding: str) -> list[dict]:
    def projection(uid: int, consumer: str, image: str):
        token_file = "vault-token" if consumer == "backend" else "agent-token"
        prepare = {
            "name": "prepare-vault-projection", "image": image,
            "command": ["sh", "-ec",
                        "cp /source/token /run/skywright/" + token_file + "; "
                        "chown " + str(uid) + ":" + str(uid) + " /run/skywright /run/skywright/" + token_file + "; "
                        "chmod 0700 /run/skywright; chmod 0400 /run/skywright/" + token_file],
            "securityContext": {"runAsUser": 0, "runAsNonRoot": False, "allowPrivilegeEscalation": False,
                                "capabilities": {"drop": ["ALL"], "add": ["CHOWN", "FOWNER", "DAC_OVERRIDE"]}},
            "resources": {"requests": {"cpu": "100m", "memory": "64Mi"},
                          "limits": {"cpu": "1", "memory": "64Mi"}},
            "volumeMounts": [{"name": "vault-token-source", "mountPath": "/source", "readOnly": True},
                             {"name": "vault-projection", "mountPath": "/run/skywright"}]}
        volumes = [
            {"name": "vault-token-source", "secret": {"secretName": "skywright-" + consumer + "-vault",
                                                    "defaultMode": 0o400}},
            {"name": "vault-projection", "emptyDir": {"medium": "Memory", "sizeLimit": "2Mi"}},
            {"name": "vault-ca", "configMap": {"name": "skywright-vault-ca"}},
            {"name": "local-configuration", "configMap": {"name": "skywright-local-configuration"}},
        ]
        mounts = [
            {"name": "vault-projection", "mountPath": "/run/skywright"},
            {"name": "vault-ca", "mountPath": "/etc/skywright-ca", "readOnly": True},
            {"name": "local-configuration", "mountPath": "/etc/skywright", "readOnly": True},
        ]
        return prepare, volumes, mounts

    prepare, volumes, mounts = projection(10001, "backend", "skywright-backend")
    trust = {
        "name": "prepare-vault-trust", "image": "skywright-backend",
        "command": ["sh", "-ec",
                    'cp "$JAVA_HOME/lib/security/cacerts" /run/skywright/truststore; '
                    'keytool -importcert -noprompt -alias skywright-vault -file /etc/skywright-ca/ca.crt '
                    '-keystore /run/skywright/truststore -storepass changeit; '
                    'chmod 0400 /run/skywright/truststore'],
        "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                      "limits": {"cpu": "1", "memory": "128Mi"}},
        "securityContext": {"runAsUser": 10001, "runAsNonRoot": True, "allowPrivilegeEscalation": False},
        "volumeMounts": mounts}
    environment = {
        "SKYWRIGHT_CREDENTIALS_VAULT_ADDRESS": "https://skywright-vault.skywright.svc:8200",
        "SKYWRIGHT_CREDENTIALS_VAULT_MOUNT": "skywright",
        "SKYWRIGHT_CREDENTIALS_VAULT_TOKEN_FILE": "/run/skywright/vault-token",
        "SKYWRIGHT_CREDENTIALS_VAULT_BINDINGS_FILE": "/etc/skywright/credential-bindings.json",
        "SKYWRIGHT_CREDENTIALS_SKYPILOT_BINDING": skypilot_binding,
        "JAVA_TOOL_OPTIONS": "-Djavax.net.ssl.trustStore=/run/skywright/truststore -Djavax.net.ssl.trustStorePassword=changeit",
        "SKYWRIGHT_LOCAL_RUN_IDENTITY": "local/amd",
        "SKYWRIGHT_LOCAL_RUN_KUBERNETES_CONTEXT": settings["context"],
        "SKYWRIGHT_LOCAL_RUN_GPU_MODEL": settings["gpuModel"],
        "SKYWRIGHT_LOCAL_RUN_MAXIMUM_GPU_COUNT": str(settings["gpuCount"]),
        "SKYWRIGHT_LOCAL_RUN_GPU_MEMORY_BYTES": str(settings["gpuMemoryBytes"]),
        "SKYWRIGHT_LOCAL_RUN_CPUS": "2", "SKYWRIGHT_LOCAL_RUN_MEMORY": "8",
        "SKYWRIGHT_LOCAL_RUN_WRITER_AUTHORITY_ENABLED": "true",
    }
    backend = resource("Deployment", "skywright-backend", spec={"template": {"spec": {
        "imagePullSecrets": [{"name": "skywright-control-pull"}],
        "initContainers": [prepare, trust], "volumes": volumes,
        "containers": [{"name": "backend", "volumeMounts": mounts,
                        "env": [{"name": name, "value": value} for name, value in environment.items()] + [{
                            "name": "SPRING_APPLICATION_JSON", "valueFrom": {"configMapKeyRef": {
                                "name": "skywright-local-configuration", "key": "application.json"}}}]}]}}})

    prepare, volumes, mounts = projection(10002, "skypilot", VAULT)
    agent = {
        "name": "project-kubernetes-credential", "image": VAULT,
        "command": ["vault", "agent", "-config=/etc/skywright/agent.hcl"],
        "securityContext": {"runAsUser": 10002, "runAsGroup": 10002, "runAsNonRoot": True,
                            "allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}},
        "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                      "limits": {"cpu": "1", "memory": "128Mi"}},
        "volumeMounts": mounts,
    }
    if settings.get("vastProvider") is not None:
        from .local_vast import DESTINATION
        volumes.append({"name": "vast-provider", "emptyDir": {"medium": "Memory", "sizeLimit": "64Ki"}})
        provider_mount = {"name": "vast-provider", "mountPath": "/run/skywright-vast"}
        prepare["volumeMounts"].append(provider_mount)
        prepare["command"][2] += "; chown 10002:10002 /run/skywright-vast; chmod 0700 /run/skywright-vast"
        agent["volumeMounts"] = [*mounts, provider_mount]
    remove = {
        "name": "remove-agent-token", "image": VAULT,
        "command": ["rm", "/run/skywright/agent-token"], "volumeMounts": mounts,
        "securityContext": {"runAsUser": 10002, "runAsNonRoot": True, "allowPrivilegeEscalation": False},
        "resources": {"requests": {"cpu": "100m", "memory": "32Mi"},
                      "limits": {"cpu": "1", "memory": "32Mi"}},
    }
    containers = []
    for name in ("skypilot-api-server", "runtime-pull", "log-collector"):
        env = [{"name": "SKYWRIGHT_KUBECONFIG", "value": "/run/skywright/kubeconfig"}]
        if name == "skypilot-api-server":
            env += [{"name": "ENABLE_BASIC_AUTH", "value": "true"},
                    {"name": "ENABLE_SERVICE_ACCOUNTS", "value": "true"},
                    {"name": "SKYPILOT_GLOBAL_CONFIG", "value": "/etc/skywright/skypilot.yaml"}]
        selected_mounts = mounts
        if name == "skypilot-api-server" and settings.get("vastProvider") is not None:
            selected_mounts = [*mounts, {"name": "vast-provider", "mountPath": DESTINATION,
                                       "subPath": "vast_api_key", "readOnly": True}]
        containers.append({"name": name, "volumeMounts": selected_mounts, "env": env})
    skypilot = resource("Deployment", "skywright-skypilot-api-server", spec={"template": {"spec": {
        "imagePullSecrets": [{"name": "skywright-control-pull"}],
        "initContainers": [prepare, agent, remove], "volumes": volumes, "containers": containers}}})
    postgres = resource("Deployment", "skywright-postgresql", spec={"template": {"spec": {"containers": [{
        "name": "postgresql", "resources": {"requests": {"cpu": "250m", "memory": "256Mi"},
                                            "limits": {"cpu": "1", "memory": "512Mi"}}}]}}})
    return [backend, skypilot, postgres]


AGENT_CONFIGURATION = """exit_after_auth = true
vault {
  address = "https://skywright-vault.skywright.svc:8200"
  ca_cert = "/etc/skywright-ca/ca.crt"
}
auto_auth {
  method "token_file" {
    config = { token_file_path = "/run/skywright/agent-token" }
  }
}
template {
  contents = <<KUBECONFIG
{{ with secret "skywright/data/local/skypilot-kubernetes?version=1" }}{{ .Data.data.kubeconfig }}{{ end }}
KUBECONFIG
  destination = "/run/skywright/kubeconfig"
  perms = "0400"
  error_on_missing_key = true
}
"""
