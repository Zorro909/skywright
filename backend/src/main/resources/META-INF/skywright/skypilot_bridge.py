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
    request_id = sky.launch(task, cluster_name=specification["name"])
    return json.dumps({"operation_id": str(request_id)})


def bridge_status(serialized_names):
    request_id = sky.status(cluster_names=json.loads(serialized_names))
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
        if kind == "submission":
            job_id, handle = value
            result = {"job_id": int(job_id), "handle": _handle(handle)}
        elif kind == "status":
            clusters = []
            for record in value:
                status = _field(record, "status")
                clusters.append({
                    "name": str(_field(record, "name")),
                    "status": str(getattr(status, "value", status)),
                    "handle": _handle(_field(record, "handle")),
                })
            result = {"clusters": clusters}
        elif kind == "control":
            result = {"applied": True}
        elif kind == "cleanup":
            result = {"removed": True}
        else:
            raise ValueError(f"unsupported operation kind: {kind}")
        return json.dumps(result)
    except Exception as failure:
        return json.dumps({"failure": {
            "category": type(failure).__name__,
            "message": str(failure).splitlines()[0][:240],
        }})
