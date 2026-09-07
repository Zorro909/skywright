"""Exercise the shipped helper over HTTP against a controlled Secret store."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
import urllib.request
import uuid

SOURCE = Path(__file__).resolve().parents[2] / "skypilot-api-server-deployment/src/main/docker/startup/runtime_pull.py"
spec = importlib.util.spec_from_file_location("runtime_pull", SOURCE)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class PullTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        path = Path(self.directory.name) / "kubeconfig"
        path.write_text(json.dumps({"apiVersion": "v1", "kind": "Config", "users": [
            {"name": "operator", "user": {"token": "kubernetes-sentinel"}}], "clusters": [
            {"name": "cluster", "cluster": {"server": "https://local.invalid", "certificate-authority-data": "ca"}}],
            "contexts": [{"name": "local", "context": {"user": "operator", "cluster": "cluster", "namespace": "training"}}]}))
        path.chmod(0o400)
        environment = patch.dict(os.environ, SKYWRIGHT_KUBECONFIG=str(path))
        environment.start()
        self.addCleanup(environment.stop)
        self.secret = None
        self.creates = 0
        self.offline = False
        self.lost_ack = False
        owner = self

        class Secrets:
            def __init__(self, *_):
                if owner.offline:
                    raise RuntimeError("provider Kubernetes credential sentinel")

            def create(self, namespace, secret):
                owner.assertEqual(namespace, "training")
                if owner.secret is None:
                    owner.secret = secret
                    owner.creates += 1
                if owner.lost_ack:
                    raise RuntimeError("lost acknowledgement with credential sentinel")

            def read(self, *_):
                return owner.secret

            def close(self):
                pass

        self.server = helper.Server(("127.0.0.1", 0), Secrets)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.values = {"run": str(uuid.uuid4()), "binding": str(uuid.uuid4()), "revision": "2",
                       "context": "local", "namespace": "training"}
        self.body = json.dumps({"auths": {"ghcr.io": {"auth": base64.b64encode(b"user:ghcr-sentinel").decode()}}}).encode()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def call(self, path="pull", body=None, values=None):
        url = f"http://127.0.0.1:{self.server.server_port}/{path}?" + urllib.parse.urlencode(values or self.values)
        request = urllib.request.Request(url, data=body, method="GET" if body is None else "PUT")
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = response.read()
            self.assertNotIn(b"sentinel", result)
            return response.status, json.loads(result)

    def test_lost_ack_replay_restart_and_foreign_revision_never_replace_secret(self):
        self.assertEqual(self.call()[0], 404)
        self.lost_ack = True
        self.assertEqual(self.call(body=self.body)[0], 503)
        self.assertEqual(self.creates, 1)
        original = json.dumps(self.secret, sort_keys=True)
        self.lost_ack = False
        self.assertEqual(self.call()[0], 200)
        self.assertEqual(self.call(body=self.body)[0], 200)
        # A new helper instance reads only the durable Kubernetes object.
        replacement = helper.Server(("127.0.0.1", 0), self.server.secrets_factory)
        old = self.server
        old.shutdown()
        old.server_close()
        self.thread.join(2)
        self.server = replacement
        self.thread = threading.Thread(target=replacement.serve_forever, daemon=True)
        self.thread.start()
        self.assertEqual(self.call()[0], 200)
        self.assertEqual(self.call(values=dict(self.values, revision="3"))[0], 409)
        changed = self.body.replace(b"dXNlcjpnaGNyLXNlbnRpbmVs", b"dXNlcjpuZXctY3JlZGVudGlhbA==")
        self.assertEqual(self.call(body=changed)[0], 409)
        self.assertEqual(json.dumps(self.secret, sort_keys=True), original)
        self.assertEqual(self.creates, 1)

    def test_namespace_pin_unavailable_role_and_bounded_invalid_delivery(self):
        self.assertEqual(self.call("namespace", values={"context": "local"}), (200, {"namespace": "training"}))
        self.assertEqual(self.call("namespace", values={"context": "other"})[0], 503)
        self.assertEqual(self.call(values=dict(self.values, namespace="other"), body=self.body)[0], 503)
        self.offline = True
        self.assertEqual(self.call(body=self.body)[0], 503)
        self.offline = False
        self.assertEqual(self.call(body=b"{}")[0], 503)
        # Reject the header before consuming the oversized body.
        import http.client
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.putrequest("PUT", "/pull?" + urllib.parse.urlencode(self.values))
        connection.putheader("Content-Length", str(helper.MAX_BODY + 1))
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 503)
        connection.close()
        self.assertEqual(self.creates, 0)

    def test_unqualified_existing_secret_is_never_adopted(self):
        self.secret = helper.manifest(self.values, self.body)
        del self.secret["metadata"]["labels"]
        self.assertEqual(self.call()[0], 409)
        self.assertEqual(self.call(body=self.body)[0], 409)


if __name__ == "__main__":
    unittest.main()
