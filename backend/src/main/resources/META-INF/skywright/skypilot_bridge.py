import contextvars
import json
import math
import os
import re
import socket
import sys
import threading
import urllib.error
import urllib.request
import weakref


_SKY_MODULES = None
_SKY_LOCK = threading.Lock()


def _sky_modules():
    global _SKY_MODULES
    if _SKY_MODULES is None:
        with _SKY_LOCK:
            if _SKY_MODULES is None:
                import sky
                from sky.server import common as server_common
                from sky.utils import common as sky_common
                from sky.utils import context

                os.environ = context.ContextualEnviron(os.environ)
                _SKY_MODULES = (sky, server_common, sky_common)
    return _SKY_MODULES


def _bridge_boundary(function):
    def guarded(*arguments):
        try:
            return function(*arguments)
        except Exception as failure:
            category = type(failure).__name__
            if category == "ApiServerConnectionError":
                _clear_api_server_status_cache()
                return _bridge_failure(
                    "REACHABILITY", "SkyPilot API server is unreachable"
                )
            if category == "ApiServerAuthenticationError":
                _clear_api_server_status_cache()
                return _bridge_failure(
                    "AUTHENTICATION", "SkyPilot API authentication failed"
                )
            if category == "APIVersionMismatchError":
                _clear_api_server_status_cache()
                return _bridge_failure(
                    "VERSION_MISMATCH", "SkyPilot API version is incompatible"
                )
            return _bridge_failure(
                "ADAPTER_CONTRACT", "SkyPilot bridge operation failed"
            )

    return guarded


def _clear_api_server_status_cache():
    if _SKY_MODULES is not None:
        _SKY_MODULES[1].get_api_server_status_response.cache_clear()


def _probe_failure(failure):
    if isinstance(failure, urllib.error.HTTPError) and failure.code in (401, 403):
        return _bridge_failure(
            "AUTHENTICATION", "SkyPilot API authentication failed"
        )
    if isinstance(failure, (urllib.error.URLError, TimeoutError)):
        return _bridge_failure("REACHABILITY", "SkyPilot API server is unreachable")
    return _bridge_failure("ADAPTER_CONTRACT", "SkyPilot bridge operation failed")


def _bridge_failure(cause, message):
    return json.dumps({"bridge_failure": {"cause": cause, "message": message}})


def _field(value, name):
    if isinstance(value, dict):
        return value[name]
    return getattr(value, name)


def _optional_field(value, name):
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


def _handle(value):
    return {
        "type": type(value).__name__,
        "cluster_name": str(value.cluster_name),
        "cluster_name_on_cloud": str(value.cluster_name_on_cloud),
        "launched_nodes": int(value.launched_nodes),
        "launched_resources": str(value.launched_resources),
    }


_SOCKETS = weakref.WeakSet()
_SOCKET_LOCK = threading.Lock()
_STOPPING = False


def _socket_audit(event, arguments):
    # Include socket wrappers created with an existing descriptor, such as TLS.
    if event in ("socket.__new__", "socket.connect"):
        with _SOCKET_LOCK:
            if _STOPPING:
                raise RuntimeError("SkyPilot bridge is shutting down")
            _SOCKETS.add(arguments[0])


def bridge_initialize():
    sys.addaudithook(_socket_audit)


def bridge_call(function, token, *arguments):
    if function == "bridge_probe":
        return bridge_probe(token)
    _sky_modules()
    from sky.utils import context

    def execute():
        with context.initialize() as current:
            if token is not None:
                current.override_envs({"SKYPILOT_SERVICE_ACCOUNT_TOKEN": token})
            return globals()[function](*arguments)

    return contextvars.copy_context().run(execute)


def bridge_interrupt():
    global _STOPPING
    with _SOCKET_LOCK:
        _STOPPING = True
        sockets = list(_SOCKETS)
    for connection in sockets:
        try:
            connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


@_bridge_boundary
def bridge_probe(token=None):
    endpoint = os.environ["SKYPILOT_API_SERVER_ENDPOINT"].rstrip("/")
    try:
        request = urllib.request.Request(f"{endpoint}/api/health")
        if token is None:
            token = os.environ.get("SKYPILOT_SERVICE_ACCOUNT_TOKEN")
        if token:
            request.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(request, timeout=5) as response:
            info = json.load(response)
    except Exception as failure:
        return _probe_failure(failure)
    return json.dumps({"server_version": str(info["version"])})


@_bridge_boundary
def bridge_catalog_price(serialized):
    sky, _, _ = _sky_modules()
    from sky.client import sdk as sky_sdk

    query = json.loads(serialized)
    target = str(query["target"])
    gpu_model = str(query["gpuModel"])
    request_id = sky_sdk.list_accelerators(
        gpus_only=True,
        name_filter=re.escape(gpu_model),
        region_filter=re.escape(str(query["region"])),
        quantity_filter=int(query["gpuCount"]),
        clouds=[target],
        all_regions=True,
        require_price=True,
        case_sensitive=False,
    )
    catalogue = sky_sdk.stream_and_get(request_id)
    for catalogued_model, offerings in catalogue.items():
        if str(catalogued_model).lower() != gpu_model.lower():
            continue
        for offering in offerings:
            if not _catalogue_offering_matches(offering, query):
                continue
            hourly_rate = _field(
                offering, "spot_price" if query["spot"] else "price"
            )
            if hourly_rate is None or not math.isfinite(float(hourly_rate)):
                continue
            if float(hourly_rate) < 0:
                continue
            native_currency = _optional_field(offering, "native_currency")
            native_unit = _optional_field(offering, "native_unit")
            minimum_quantity = _optional_field(offering, "minimum_quantity")
            billing_quantum = _optional_field(offering, "billing_quantum")
            if any(
                value is None
                for value in (
                    native_currency,
                    native_unit,
                    minimum_quantity,
                    billing_quantum,
                )
            ):
                continue
            observed_at = str(query["observedAt"])
            return json.dumps(
                {
                    "outcome": "available",
                    "hourly_rate": str(hourly_rate),
                    "native_currency": str(native_currency),
                    "native_unit": str(native_unit),
                    "minimum_quantity": str(minimum_quantity),
                    "billing_quantum": str(billing_quantum),
                    "observed_at": observed_at,
                    "effective_from": observed_at,
                    "effective_until": None,
                    "provenance": {
                        "source": "SkyPilot catalogue",
                        "valueKind": "estimate",
                        "skyPilotVersion": str(sky.__version__),
                        "catalogRequestId": str(request_id),
                        "target": target,
                        "region": str(query["region"]),
                        "instanceType": str(query["instanceType"]),
                        "gpuModel": gpu_model,
                        "gpuCount": int(query["gpuCount"]),
                        "purchaseMode": (
                            "spot" if query["spot"] else "on-demand"
                        ),
                    },
                },
                separators=(",", ":"),
            )
    return json.dumps({"outcome": "missing"})


def _catalogue_offering_matches(offering, query):
    return (
        str(_field(offering, "cloud")).lower() == str(query["target"]).lower()
        and str(_field(offering, "region")) == str(query["region"])
        and str(_field(offering, "instance_type")) == str(query["instanceType"])
        and str(_field(offering, "accelerator_name")).lower()
        == str(query["gpuModel"]).lower()
        and float(_field(offering, "accelerator_count"))
        == float(query["gpuCount"])
    )


@_bridge_boundary
def bridge_submit(serialized, serialized_secrets="{}"):
    sky, _, sky_common = _sky_modules()
    specification = json.loads(serialized)
    name = specification["name"]
    existing_request_id = _existing_launch_request(name)
    if existing_request_id is not None:
        return json.dumps({"operation_id": _submission_operation(existing_request_id)})

    existing_job_id = _existing_managed_job_id(name)
    if existing_job_id is not None:
        request_id = sky.status(cluster_names=[sky_common.JOB_CONTROLLER_NAME])
        return json.dumps(
            {"operation_id": _submission_operation(request_id, existing_job_id)}
        )

    request_id = sky.jobs.launch(_task(specification, json.loads(serialized_secrets)), name=name)
    return json.dumps({"operation_id": _submission_operation(request_id)})


@_bridge_boundary
def bridge_status(serialized_names):
    sky, _, _ = _sky_modules()
    names = set(json.loads(serialized_names))
    request_id = sky.jobs.queue_v2(refresh=False, all_users=True)
    operation_id = json.dumps(
        {
            "request_id": str(request_id),
            "names": sorted(names),
        },
        separators=(",", ":"),
    )
    return json.dumps({"operation_id": operation_id})


@_bridge_boundary
def bridge_cancel(job_name):
    sky, _, _ = _sky_modules()
    job_name = str(job_name)
    existing_job_id = _existing_managed_job_id(job_name, refresh=False)
    if existing_job_id is None:
        request_id = sky.jobs.cancel(name=job_name)
    else:
        request_id = sky.jobs.cancel(job_ids=[existing_job_id])
    return json.dumps({"operation_id": str(request_id)})


@_bridge_boundary
def bridge_cleanup(cluster_name):
    sky, _, _ = _sky_modules()
    request_id = sky.down(str(cluster_name))
    return json.dumps({"operation_id": str(request_id)})


@_bridge_boundary
def bridge_complete(operation_id, kind):
    sky, _, _ = _sky_modules()
    request_id = str(operation_id)
    names = set()
    existing_job_id = None
    if kind == "submission":
        submission = json.loads(operation_id)
        request_id = submission["request_id"]
        existing_job_id = submission.get("existing_job_id")
    elif kind == "status":
        status = json.loads(operation_id)
        request_id = status["request_id"]
        names = set(status["names"])
    try:
        value = sky.stream_and_get(str(request_id))
    except Exception as failure:
        if type(failure).__name__ in (
            "ApiServerConnectionError",
            "ApiServerAuthenticationError",
            "APIVersionMismatchError",
        ):
            raise
        return json.dumps(
            {
                "failure": {
                    "category": type(failure).__name__,
                    "message": _operation_failure_message(failure),
                }
            }
        )
    if kind == "submission":
        if existing_job_id is None:
            job_ids, handle = value
        else:
            job_ids = [existing_job_id]
            handle = _field(value[0], "handle") if value else None
        if not job_ids or handle is None:
            raise ValueError("managed job launch returned no identity")
        result = {"job_id": int(job_ids[0]), "handle": _handle(handle)}
    elif kind == "status":
        records = value[0]
        jobs = []
        for record in records:
            job_name = str(_field(record, "job_name"))
            if names and job_name not in names:
                continue
            status = _field(record, "status")
            jobs.append(
                {
                    "job_id": int(_field(record, "job_id")),
                    "job_name": job_name,
                    "status": str(getattr(status, "value", status)),
                    "recovery_count": int(_field(record, "recovery_count")),
                }
            )
        result = {"jobs": jobs}
    elif kind == "control":
        result = {"applied": True}
    elif kind == "cleanup":
        result = {"removed": True}
    else:
        raise ValueError(f"unsupported operation kind: {kind}")
    return json.dumps(result)


def _existing_managed_job_id(name, refresh=True):
    sky, _, _ = _sky_modules()
    try:
        request_id = sky.jobs.queue_v2(refresh=refresh, all_users=True)
        records = sky.stream_and_get(request_id)[0]
    except Exception as failure:
        if type(failure).__name__ in ("ClusterNotUpError", "ClusterDoesNotExist"):
            records = []
        else:
            raise
    matching_ids = [
        int(_field(record, "job_id"))
        for record in records
        if str(_field(record, "job_name")) == name
        and _field(record, "job_id") is not None
    ]
    return max(matching_ids, default=None)


def _existing_launch_request(name):
    sky, _, _ = _sky_modules()
    requests = sky.api_status(all_status=True)
    matching = [
        request
        for request in requests
        if str(request.name) == "sky.jobs.launch"
        and json.loads(request.request_body).get("name") == name
    ]
    if not matching:
        return None
    return max(matching, key=lambda request: request.created_at).request_id


def _submission_operation(request_id, existing_job_id=None):
    operation = {"request_id": str(request_id)}
    if existing_job_id is not None:
        operation["existing_job_id"] = int(existing_job_id)
    return json.dumps(operation, separators=(",", ":"))


def _task(specification, secrets=None):
    secrets = secrets or {}
    allowed = {
        f"SKYWRIGHT_{slot}_{field}"
        for slot in ("DATASET", "RUN_STORE")
        for field in ("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN")
    }
    if not set(secrets) <= allowed or set(secrets) & set(specification.get("environment", {})):
        raise ValueError("Invalid Training Process secret channel")
    if set(specification.get("environment", {})) & allowed:
        raise ValueError("Storage credentials must use the secret channel")
    sky, _, _ = _sky_modules()
    runtime_options = {}
    if specification.get("runtimePullSecret"):
        runtime_options["_cluster_config_overrides"] = {
            "kubernetes": {
                "pod_config": {
                    "spec": {"imagePullSecrets": [{"name": specification["runtimePullSecret"]}]}
                }
            }
        }
    resources = [
        sky.Resources(
            infra=requested["infrastructure"],
            cpus=requested["cpus"],
            memory=requested["memory"],
            accelerators=requested.get("accelerators"),
            image_id=requested.get("imageId"),
            use_spot=requested["useSpot"],
            **runtime_options,
        )
        for requested in specification["resources"]
    ]
    return sky.Task(
        name=specification["name"],
        setup=specification.get("setup"),
        run=specification["run"],
        envs=specification.get("environment", {}),
        secrets=secrets,
        resources=resources,
    )


def _operation_failure_message(failure):
    category = type(failure).__name__
    if category == "ResourcesUnavailableError":
        return "Requested resources are unavailable"
    if category in ("NoCloudAccessError", "CloudUserIdentityError"):
        return "Target credentials are unavailable"
    if category in ("ClusterDoesNotExist", "ClusterNotUpError"):
        return "SkyPilot target is unavailable"
    return "SkyPilot operation failed"
