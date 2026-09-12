"""No-network reproduction of the released SkyPilot/Vast selection boundary."""
import copy
import importlib.metadata
import json
import sys
from unittest.mock import patch

from sky.provision.vast import utils
from vastai_sdk import VastAI


def prohibit(event, args):
    if event in {"socket.connect", "socket.getaddrinfo", "subprocess.Popen", "os.system"}:
        raise RuntimeError("Offline diagnostic prohibits " + event)


sys.addaudithook(prohibit)
provider = VastAI(api_key="synthetic-offline-key-never-a-real-credential")
offers = [
    {"id": 101, "gpu_name": "RTX 3060", "num_gpus": 1, "gpu_ram": 12288,
     "cpu_ram": 16065, "cpu_cores": 32, "disk_space": 115, "min_bid": 0.04,
     "geolocation": "Beijing, CN"},
    {"id": 202, "gpu_name": "B200", "num_gpus": 1, "gpu_ram": 180000,
     "cpu_ram": 131072, "cpu_cores": 64, "disk_space": 1000, "min_bid": 5,
     "geolocation": "Oregon, US"},
]
requests = []


class Response:
    def __init__(self, body): self.body = body
    def raise_for_status(self): pass
    def json(self): return copy.deepcopy(self.body)


def post(path, *, json_data):
    assert path == "/bundles/"
    requests.append({"method": "POST", "path": path, "body": json_data})
    return Response({"offers": offers})


def put(path, *, json_data):
    assert path == "/asks/202/"
    requests.append({"method": "PUT", "path": path, "body": {
        key: json_data[key] for key in ("price", "disk", "cancel_unavail")
    }})
    return Response({"success": True, "new_contract": 9001})


def get(path, *, query_args):
    assert path == "/instances/9001/" and query_args == {"owner": "me"}
    return Response({"instances": {"id": 9001, "start_date": 0, "extra_env": []}})


with patch.object(utils.vast, "vast", return_value=provider), \
        patch.object(provider.client, "post", side_effect=post), \
        patch.object(provider.client, "put", side_effect=put), \
        patch.object(provider.client, "get", side_effect=get):
    result = utils.launch(
        "offline-probe", "1x-RTX_3060-32-65536", "Beijing, CN", 40,
        "example.invalid/offline-image", None, True, False,
        create_instance_kwargs={"price": 0.04, "cancel_unavail": True},
    )
assert result == 9001
assert set(requests[0]["body"]) == {
    "verified", "external", "rentable", "order", "type", "allocated_storage"
}
assert requests[1]["body"]["price"] == 0.04
print(json.dumps({
    "skypilot": importlib.metadata.version("skypilot"),
    "vastai": importlib.metadata.version("vastai"),
    "requestedGpu": "RTX 3060", "selectedSyntheticGpu": "B200",
    "syntheticRequests": requests,
    "networkAndSubprocesses": "prohibited", "realCredentialsRead": False,
}, indent=2))
