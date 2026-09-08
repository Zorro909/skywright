"""Custody validation and proof persistence at the local authority boundary."""

import copy
import json
import os
import tempfile
import unittest
from pathlib import Path

from deployment.local_writer.common import Uncertain
from deployment.local_writer.custody import (
    Custody,
    validate_proof,
    validate_registration,
)

FIXTURE = Path(__file__).parent / "fixtures/local-writer-registration.json"


def registration():
    return json.loads(FIXTURE.read_text())


def observation(record):
    return {
        "kind": "container-exited-cgroup-removed",
        "container_id": record["container_id"],
        "boot_id": record["boot_id"],
        "finished_at": "2026-09-09T00:00:00Z",
    }


class WriterEvidenceTest(unittest.TestCase):
    def test_incomplete_or_mismatched_registration_never_establishes_custody(self):
        original = registration()
        validate_registration(original, original["owner"])
        for field in original:
            invalid = copy.deepcopy(original)
            del invalid[field]
            with self.subTest(missing=field), self.assertRaises(Uncertain):
                validate_registration(invalid, original["owner"])
        for mutate in (
            lambda r: r["process"].update(container_id="b" * 64),
            lambda r: r["init"].update(pid_namespace="pid:[1]"),
            lambda r: r["process"].update(cgroup="/../escape"),
            lambda r: r.update(cgroup_chain=[]),
            lambda r: r.update(runtime="unqualified/1"),
        ):
            invalid = copy.deepcopy(original)
            mutate(invalid)
            with self.assertRaises(Uncertain):
                validate_registration(invalid, original["owner"])

    def test_reference_ids_alone_are_not_a_proof(self):
        record = registration()
        forged = {"run_id": record["run_id"], "attempt_id": record["attempt_id"]}
        with self.assertRaises(Uncertain):
            validate_proof(forged, record)

    @unittest.skipUnless(
        os.geteuid() == 0,
        "root-owned custody is exercised in the container system check",
    )
    def test_durable_proof_survives_restart_and_cannot_be_replaced(self):
        record = registration()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "authority.json").write_text(json.dumps(record["owner"]))
            first = Custody(directory, record["owner"]["node_uid"])
            first.register(record)
            first.register(dict(record, registered_at=record["registered_at"] + 1))
            with self.assertRaises(Uncertain):
                first.register(dict(record, image="different-image"))
            key = first.key(record["run_id"], record["attempt_id"])
            first.prove(key, observation(record))
            expected = first.response(key)
            first.close()
            second = Custody(directory, record["owner"]["node_uid"])
            self.assertEqual(second.response(key), expected)
            second.close()
            proof_path = directory / ("proof-" + key + ".json")
            proof = json.loads(proof_path.read_text())
            for field in tuple(proof):
                invalid = dict(proof)
                del invalid[field]
                with self.subTest(missing=field), self.assertRaises(Uncertain):
                    validate_proof(invalid, record)
            proof.pop("observation")
            proof_path.write_text(json.dumps(proof))
            with self.assertRaises(Uncertain):
                Custody(directory, record["owner"]["node_uid"])


class WriterMountTest(unittest.TestCase):
    def test_only_qualified_mounts_and_restart_policy_can_enter_custody(self):
        from deployment.local_writer.node import validate_mounts

        fixture = json.loads((FIXTURE.parent / "local-writer-mounts.json").read_text())

        def check(value):
            validate_mounts(
                value["pod"], value["runtime"], value["pod_uid"], value["sandbox_id"]
            )

        check(fixture)
        for mutate in (
            lambda f: f["pod"].update(hostNetwork=True),
            lambda f: f["pod"]["containers"][0].update(restartPolicy="Always"),
            lambda f: f["pod"]["volumes"].append(
                {
                    "name": "extra",
                    "persistentVolumeClaim": {"claimName": "host-runtime"},
                }
            ),
            lambda f: f["runtime"]["mounts"].append(
                {
                    "type": "bind",
                    "source": "/run/containerd/containerd.sock",
                    "destination": "/tmp/runtime.sock",
                    "options": ["ro", "rprivate"],
                }
            ),
            lambda f: f["runtime"]["mounts"].append(f["runtime"]["mounts"][0]),
            lambda f: f["pod"]["containers"][0]["volumeMounts"][0].update(
                readOnly=False
            ),
            lambda f: f["pod"]["containers"][0]["volumeMounts"][0].update(
                subPath="authority.sock"
            ),
        ):
            invalid = copy.deepcopy(fixture)
            mutate(invalid)
            with self.assertRaises(Uncertain):
                check(invalid)


if __name__ == "__main__":
    unittest.main()
