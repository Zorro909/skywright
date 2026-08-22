import json
from collections import OrderedDict

import sky
from sky.server import common as server_common
from sky.utils import common as sky_common

_PENDING_SUBMISSION_LIMIT = 1024
_pending_submissions = OrderedDict()


def _bridge_boundary(function):
    def guarded(*arguments):
        try:
            return function(*arguments)
        except sky.exceptions.ApiServerConnectionError:
            server_common.get_api_server_status_response.cache_clear()
            return _bridge_failure("REACHABILITY", "SkyPilot API server is unreachable")
        except sky.exceptions.ApiServerAuthenticationError:
            server_common.get_api_server_status_response.cache_clear()
            return _bridge_failure(
                "AUTHENTICATION", "SkyPilot API authentication failed"
            )
        except sky.exceptions.APIVersionMismatchError:
            server_common.get_api_server_status_response.cache_clear()
            return _bridge_failure(
                "VERSION_MISMATCH", "SkyPilot API version is incompatible"
            )
        except Exception:
            return _bridge_failure(
                "ADAPTER_CONTRACT", "SkyPilot bridge operation failed"
            )

    return guarded


def _bridge_failure(cause, message):
    return json.dumps({"bridge_failure": {"cause": cause, "message": message}})


def _field(value, name):
    if isinstance(value, dict):
        return value[name]
    return getattr(value, name)


def _handle(value):
    return {
        "type": type(value).__name__,
        "cluster_name": str(value.cluster_name),
        "cluster_name_on_cloud": str(value.cluster_name_on_cloud),
        "launched_nodes": int(value.launched_nodes),
        "launched_resources": str(value.launched_resources),
    }


@_bridge_boundary
def bridge_probe():
    info = sky.api_info()
    return json.dumps({"server_version": str(info.version)})


@_bridge_boundary
def bridge_submit(serialized):
    specification = json.loads(serialized)
    name = specification["name"]
    pending = _pending_submissions.get(name)
    if pending is not None:
        _pending_submissions.move_to_end(name)
        return json.dumps({"operation_id": pending})

    existing_job_id = _existing_managed_job_id(name)
    if existing_job_id is not None:
        request_id = sky.status(cluster_names=[sky_common.JOB_CONTROLLER_NAME])
        return json.dumps(
            {"operation_id": _submission_operation(request_id, existing_job_id)}
        )

    requested = specification["resources"]
    resources = sky.Resources(
        infra=requested["infrastructure"],
        cpus=requested["cpus"],
        memory=requested["memory"],
        image_id=requested.get("imageId"),
    )
    task = sky.Task(
        name=name,
        setup=specification.get("setup"),
        run=specification["run"],
        envs=specification.get("environment", {}),
        resources=resources,
    )
    request_id = sky.jobs.launch(task, name=name)
    operation_id = _submission_operation(request_id)
    _remember_pending_submission(name, operation_id)
    return json.dumps({"operation_id": operation_id})


@_bridge_boundary
def bridge_status(serialized_names):
    names = set(json.loads(serialized_names))
    request_id = sky.jobs.queue_v2(refresh=False)
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
    request_id = sky.jobs.cancel(name=str(job_name))
    return json.dumps({"operation_id": str(request_id)})


@_bridge_boundary
def bridge_cleanup(cluster_name):
    request_id = sky.down(str(cluster_name))
    return json.dumps({"operation_id": str(request_id)})


@_bridge_boundary
def bridge_complete(operation_id, kind):
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
    except (
        sky.exceptions.ApiServerConnectionError,
        sky.exceptions.ApiServerAuthenticationError,
        sky.exceptions.APIVersionMismatchError,
    ):
        raise
    except Exception as failure:
        if kind == "submission":
            _forget_pending_submission(operation_id)
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
        _forget_pending_submission(operation_id)
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


def _existing_managed_job_id(name):
    try:
        request_id = sky.jobs.queue_v2(refresh=True, all_users=True)
        records = sky.get(request_id)[0]
    except Exception as failure:
        if type(failure).__name__ in ("ClusterNotUpError", "ClusterDoesNotExist"):
            return None
        raise
    matching_ids = [
        int(_field(record, "job_id"))
        for record in records
        if str(_field(record, "job_name")) == name
        and _field(record, "job_id") is not None
    ]
    return max(matching_ids, default=None)


def _submission_operation(request_id, existing_job_id=None):
    operation = {"request_id": str(request_id)}
    if existing_job_id is not None:
        operation["existing_job_id"] = int(existing_job_id)
    return json.dumps(operation, separators=(",", ":"))


def _remember_pending_submission(name, operation_id):
    _pending_submissions[name] = operation_id
    _pending_submissions.move_to_end(name)
    while len(_pending_submissions) > _PENDING_SUBMISSION_LIMIT:
        _pending_submissions.popitem(last=False)


def _forget_pending_submission(operation_id):
    for name, pending in list(_pending_submissions.items()):
        if pending == operation_id:
            _pending_submissions.pop(name, None)


def _operation_failure_message(failure):
    category = type(failure).__name__
    if category == "ResourcesUnavailableError":
        return "Requested resources are unavailable"
    if category in ("NoCloudAccessError", "CloudUserIdentityError"):
        return "Target credentials are unavailable"
    if category in ("ClusterDoesNotExist", "ClusterNotUpError"):
        return "SkyPilot target is unavailable"
    return "SkyPilot operation failed"
