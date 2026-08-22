import json
import sky


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


def bridge_probe():
    info = sky.api_info()
    return json.dumps({"server_version": str(info.version)})


def bridge_submit(serialized):
    specification = json.loads(serialized)
    requested = specification["resources"]
    resources = sky.Resources(
        infra=requested["infrastructure"],
        cpus=requested["cpus"],
        memory=requested["memory"],
        image_id=requested.get("imageId"),
    )
    task = sky.Task(
        name=specification["name"],
        setup=specification.get("setup"),
        run=specification["run"],
        envs=specification.get("environment", {}),
        resources=resources,
    )
    request_id = sky.jobs.launch(task, name=specification["name"])
    return json.dumps({"operation_id": str(request_id)})


def bridge_status(serialized_names):
    names = set(json.loads(serialized_names))
    request_id = sky.jobs.queue_v2(refresh=False)
    _operation_filters[str(request_id)] = names
    return json.dumps({"operation_id": str(request_id)})


def bridge_cancel(job_name):
    request_id = sky.jobs.cancel(name=str(job_name))
    return json.dumps({"operation_id": str(request_id)})


def bridge_cleanup(cluster_name):
    request_id = sky.down(str(cluster_name))
    return json.dumps({"operation_id": str(request_id)})


def bridge_complete(operation_id, kind):
    try:
        value = sky.stream_and_get(str(operation_id))
    except (sky.exceptions.ApiServerConnectionError,
            sky.exceptions.ApiServerAuthenticationError,
            sky.exceptions.APIVersionMismatchError):
        raise
    except Exception as failure:
        if kind == "status":
            _operation_filters.pop(str(operation_id), None)
        return json.dumps({"failure": {
            "category": type(failure).__name__,
            "message": _operation_failure_message(failure),
        }})
    if kind == "submission":
        job_ids, handle = value
        if not job_ids or handle is None:
            raise ValueError("managed job launch returned no identity")
        result = {"job_id": int(job_ids[0]), "handle": _handle(handle)}
    elif kind == "status":
        records = value[0]
        names = _operation_filters.pop(str(operation_id), set())
        jobs = []
        for record in records:
            job_name = str(_field(record, "job_name"))
            if names and job_name not in names:
                continue
            status = _field(record, "status")
            jobs.append({
                "job_id": int(_field(record, "job_id")),
                "job_name": job_name,
                "status": str(getattr(status, "value", status)),
                "recovery_count": int(_field(record, "recovery_count")),
            })
        result = {"jobs": jobs}
    elif kind == "control":
        result = {"applied": True}
    elif kind == "cleanup":
        result = {"removed": True}
    else:
        raise ValueError(f"unsupported operation kind: {kind}")
    return json.dumps(result)


_operation_filters = {}


def _operation_failure_message(failure):
    category = type(failure).__name__
    if category == "ResourcesUnavailableError":
        return "Requested resources are unavailable"
    if category in ("NoCloudAccessError", "CloudUserIdentityError"):
        return "Target credentials are unavailable"
    if category in ("ClusterDoesNotExist", "ClusterNotUpError"):
        return "SkyPilot target is unavailable"
    return "SkyPilot operation failed"
