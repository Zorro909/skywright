"""Publish the demonstration corpus using the shipped source-side CLI on a CPU pod."""

from __future__ import annotations

import base64
import io
import json
import tarfile
import time
from pathlib import Path

from .local_catalog import IMAGE
from .local_operations import Kubernetes, api
from .local_resources import claim, resource
from .local_secrets import write_private
from .local_package import protected_json

FINGERPRINT = "sha256:e976807a277906fca257c7714c0ecc40829330aa1b8c8bed6fe2ada74918a0ba"
JOB = "skywright-demonstration-publication"

SCRIPT = r"""
import contextlib,hashlib,io,json,os,shutil,subprocess,sys,tarfile,urllib.request
from pathlib import Path
work=Path("/work")
source=work/"source"
source.mkdir(exist_ok=True)
with tarfile.open("/bootstrap/source.tar.gz","r:gz") as archive:
    archive.extractall(source,filter="data")
corpus=work/"mds"
archive_path=work/"cifar-10-binary.tar.gz"
if not archive_path.exists():
    temporary=work/"download.partial"
    digest=hashlib.sha256()
    with urllib.request.urlopen("https://www.cs.toronto.edu/~kriz/cifar-10-binary.tar.gz",timeout=30) as response, temporary.open("wb") as output:
        total=0
        while block:=response.read(1024*1024):
            total+=len(block)
            if total>256*1024*1024:
                raise RuntimeError("Demonstration source exceeds its byte limit")
            digest.update(block)
            output.write(block)
    if digest.hexdigest()!="c4a38c50a1bc5f3a1c5537f2155ab9d68f9f25eb1ed8d9ddda3db29a59bca1dd":
        raise RuntimeError("Demonstration source checksum failed")
    temporary.rename(archive_path)
sys.path.insert(0,str(source/"sdk/src"))
if not (corpus/"index.json").exists():
    if corpus.exists():
        shutil.rmtree(corpus)
    subprocess.run([sys.executable,str(source/"examples/cifar10/prepare_dataset.py"),
                    str(archive_path),str(corpus)],check=True,stdout=subprocess.DEVNULL)
from skywright._dataset_cli import main
receipt_path=work/"publication-receipt.json"
identity_path=work/"publication-id"
if receipt_path.exists():
    result=json.loads(receipt_path.read_text())
else:
    class IdentityJournal(io.TextIOBase):
        def __init__(self):
            self.buffer=""
        def write(self,text):
            self.buffer+=text
            while "\n" in self.buffer:
                line,self.buffer=self.buffer.split("\n",1)
                try:
                    event=json.loads(line)
                    if event.get("event")=="dataset-publication-identity":
                        identity_path.write_text(event["publicationId"])
                except (ValueError,AttributeError):
                    pass
            sys.__stderr__.write(text)
            return len(text)
        def flush(self):
            sys.__stderr__.flush()
    output=io.StringIO()
    arguments=["publish",str(corpus),"--control-plane","http://skywright-backend",
               "--target-storage",os.environ["DATASET_STORAGE"],"--concurrency","2",
               "--version-label","cifar10-train-binary-v1"]
    if identity_path.exists():
        arguments+=["--resume",identity_path.read_text().strip()]
    with contextlib.redirect_stdout(output),contextlib.redirect_stderr(IdentityJournal()):
        status=main(arguments)
    if status:
        raise SystemExit(status)
    result=json.loads(output.getvalue())
    receipt_path.write_text(json.dumps(result))
if result["state"]!="committed":
    raise RuntimeError("Demonstration publication did not commit")
receipt={key:result[key] for key in ("publicationId","definitionId","contentFingerprint","state")}
Path("/dev/termination-log").write_text(json.dumps(receipt))
"""


def publish(kube: Kubernetes, endpoint: str, source: Path, directory: Path, root: Path,
            settings: dict, inputs: dict, storage_id: str) -> str:
    receipt_path = directory / "demonstration.json"
    if receipt_path.exists():
        receipt = protected_json(receipt_path)
        observed = api(endpoint, "/api/v1/dataset-catalog/" + receipt["definitionId"])
        if observed["definition"]["contentFingerprint"] != FINGERPRINT:
            raise SystemExit("The retained demonstration Dataset does not match the supplied corpus")
        return receipt["definitionId"]
    credentials = root / "demonstration-aws-credentials"
    if not credentials.exists():
        value = inputs["s3"]["dataset-transfer"]
        write_private(credentials, ("[default]\naws_access_key_id=" + value["accessKeyId"]
                                    + "\naws_secret_access_key=" + value["secretAccessKey"] + "\n").encode())
    pull = root / "demonstration-image-pull.json"
    if not pull.exists():
        value = inputs["registry"]["pull"]
        encoded = base64.b64encode((value["username"] + ":" + value["token"]).encode()).decode()
        write_private(pull, json.dumps({"auths": {"ghcr.io": {"auth": encoded}}}).encode())
    kube.secret("skywright", "skywright-demonstration-transfer", {"credentials": credentials})
    kube.secret("skywright", "skywright-demonstration-pull", {".dockerconfigjson": pull},
                "kubernetes.io/dockerconfigjson")
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w:gz") as archive:
        for relative in ("sdk/src/skywright", "examples/cifar10/prepare_dataset.py"):
            path = source / relative
            archive.add(path, arcname=relative, filter=lambda member: None if
                        "__pycache__" in member.name or member.name.endswith(".pyc") else member)
    if len(payload.getvalue()) > 512 * 1024:
        raise SystemExit("Demonstration bootstrap source exceeds its ConfigMap budget")
    config = resource("ConfigMap", "skywright-demonstration-source", data={"prepare.py": SCRIPT},
                      binaryData={"source.tar.gz": base64.b64encode(payload.getvalue()).decode()})
    kube.apply(claim("skywright-demonstration-source", "1Gi"), config)
    job = {
        "apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": JOB, "namespace": "skywright"},
        "spec": {"activeDeadlineSeconds": 900, "backoffLimit": 0, "template": {
            "metadata": {"labels": {"app": JOB}},
            "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                     "nodeSelector": {"kubernetes.io/hostname": settings["node"]},
                     "securityContext": {"runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000,
                                         "runAsNonRoot": True},
                     "imagePullSecrets": [{"name": "skywright-demonstration-pull"}],
                     "containers": [{"name": "publication", "image": IMAGE,
                         "command": ["python", "/bootstrap/prepare.py"],
                         "env": [{"name": "DATASET_STORAGE", "value": storage_id},
                                 {"name": "AWS_SHARED_CREDENTIALS_FILE", "value": "/credentials/credentials"},
                                 {"name": "AWS_DEFAULT_REGION", "value": "us-east-1"}],
                         "resources": {"requests": {"cpu": "1", "memory": "1Gi"},
                                       "limits": {"cpu": "2", "memory": "2Gi"}},
                         "volumeMounts": [{"name": "source", "mountPath": "/bootstrap", "readOnly": True},
                                          {"name": "credentials", "mountPath": "/credentials", "readOnly": True},
                                          {"name": "work", "mountPath": "/work"}]}],
                     "volumes": [{"name": "source", "configMap": {"name": "skywright-demonstration-source"}},
                                 {"name": "credentials", "secret": {"secretName": "skywright-demonstration-transfer",
                                                                     "defaultMode": 0o440}},
                                 {"name": "work", "persistentVolumeClaim": {
                                     "claimName": "skywright-demonstration-source"}}]}}}}
    kube.apply(job)
    deadline = time.monotonic() + 920
    try:
        while time.monotonic() < deadline:
            observed = kube.read("job", JOB, "-n", "skywright").get("status", {})
            if observed.get("succeeded") == 1:
                pods = kube.read("pods", "-n", "skywright", "-l", "job-name=" + JOB)["items"]
                receipt = json.loads(pods[0]["status"]["containerStatuses"][0]["state"]["terminated"]["message"])
                if receipt["state"] != "committed" or receipt["contentFingerprint"] != FINGERPRINT:
                    raise SystemExit("Demonstration publication did not verify the supplied corpus")
                write_private(receipt_path, json.dumps(receipt).encode())
                return receipt["definitionId"]
            if observed.get("failed", 0):
                raise SystemExit("Demonstration publication failed; inspect its source-side CLI diagnostics")
            time.sleep(2)
        raise SystemExit("Demonstration publication exceeded its fifteen-minute deadline")
    finally:
        kube.run("delete", "job", JOB, "-n", "skywright", "--cascade=foreground", "--wait=true",
                 "--timeout=60s", timeout=70)
        kube.run("delete", "secret", "skywright-demonstration-transfer", "skywright-demonstration-pull",
                 "-n", "skywright", "--ignore-not-found")
