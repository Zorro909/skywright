"""Explicit identities and provider scopes for the packaged single project."""

PROJECT = "6b4c790f-d8cd-4bdf-898c-deaf074861e9"
ROLE_NAMES = {
    "dataset": {"training-process": "dataset-reader", "backend": "dataset-backend",
                "transfer-worker": "dataset-transfer"},
    "run": {"training-process": "run-writer", "backend": "run-backend",
            "transfer-worker": "run-transfer", "metric-view": "run-metric"},
}
# Metric Views are not installed. Their retained role identity has no object access;
# enrolling a view must grant only its selected Run's metric prefix.
S3_ROLES = {
    "operator": ("*", ["Admin", "Read", "Write", "List", "Tagging"], "administrator"),
    "dataset-reader": ("skywright-datasets", ["Read", "List"], "read-only"),
    "dataset-backend": ("skywright-datasets", ["Read", "List", "Write", "Tagging"], "observe-control-delete"),
    "dataset-transfer": ("skywright-datasets", ["Read", "List", "Write"], "read-write-delete"),
    "run-writer": ("skywright-runs/" + PROJECT + "/*", ["Read", "List", "Write"], "read-write-delete"),
    "run-backend": ("skywright-runs/" + PROJECT + "/*", ["Read", "List", "Write", "Tagging"], "observe-control-delete"),
    "run-transfer": ("skywright-runs/" + PROJECT + "/*", ["Read", "List", "Write"], "read-write-delete"),
    "run-metric": ("no Metric View enrolled", [], "read-only"),
}


def actions(name: str) -> list[str]:
    scope, operations, _ = S3_ROLES.get(name, ("", [], "read-only"))
    result = operations[:] if scope == "*" else [operation + ":" + scope for operation in operations]
    if name == "run-backend":
        result += [operation + ":skywright-runs/.skywright-qualification/*" for operation in operations]
    return result
