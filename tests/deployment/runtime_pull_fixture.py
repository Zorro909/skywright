"""Run the production HTTP helper with a durable, controlled Kubernetes Secret boundary."""
import importlib.util
import json
import os
from pathlib import Path
import sys

root, directory = Path(sys.argv[1]), Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("runtime_pull", root / "skypilot-api-server-deployment/src/main/docker/startup/runtime_pull.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
config = directory / "kubeconfig"
if not config.exists():
    config.write_text(json.dumps({"apiVersion": "v1", "kind": "Config", "users": [{"name": "role", "user": {"token": "fixture-kubernetes"}}],
        "clusters": [{"name": "cluster", "cluster": {"server": "https://fixture.invalid", "certificate-authority-data": "fixture-ca"}}],
        "contexts": [{"name": "local", "context": {"user": "role", "cluster": "cluster", "namespace": "training"}}]}))
    config.chmod(0o400)
os.environ["SKYWRIGHT_KUBECONFIG"] = str(config)


class Secrets:
    def __init__(self, *_):
        if (directory / "offline").exists():
            raise RuntimeError("target unavailable")

    def create(self, namespace, secret):
        path = directory / (secret["metadata"]["name"] + ".json")
        try:
            with path.open("x") as destination:
                json.dump(secret, destination)
        except FileExistsError:
            pass
        if (directory / "lost-ack").exists():
            raise RuntimeError("target acknowledgement lost")

    def read(self, namespace, name):
        path = directory / (name + ".json")
        return json.loads(path.read_text()) if path.exists() else None

    def close(self):
        pass


server = helper.Server(("127.0.0.1", 0), Secrets)
print(server.server_port, flush=True)
server.serve_forever()
