# Test subprocess inputs and boto3 responses are dynamically shaped.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownMemberType=false, reportUnknownArgumentType=false, reportUnknownVariableType=false
import json
import os
import shutil
import subprocess
import sys
from copy import deepcopy
from pathlib import Path
from uuid import uuid4

from integration.test_recovery_process import (
    installed_recovery_sdk as installed_recovery_sdk,
)
from integration.test_run_store_system import seaweedfs
from unit.test_managed_runtime import FIXTURE, documents

SDK = Path(__file__).parents[3]


def test_installed_sdk_assembles_exact_continuation_clone_and_reset(
    installed_recovery_sdk, tmp_path
):
    definition, materials = documents()
    # These are the exact bytes checked against the Java backend resolver.
    validation = "import sys;sys.path.insert(0,sys.argv[1]);from skywright._managed_runtime import ManagedRuntime;from pathlib import Path;ManagedRuntime.decode(Path(sys.argv[2]).read_text(),sys.argv[3])"
    subprocess.run(
        [
            sys.executable,
            "-I",
            "-c",
            validation,
            str(installed_recovery_sdk),
            str(FIXTURE / "definition.json"),
            json.dumps(materials),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    with seaweedfs() as (endpoint, client):
        # Resolve the fixture's execution locations for this disposable service.
        definition["storage"]["execution"].update(
            endpoint=endpoint,
            bucket="outputs",
            region="us-east-1",
            addressingMode="path",
        )
        materials["datasetLocation"].update(endpoint=endpoint, region="us-east-1")
        client.create_bucket(Bucket="outputs")
        client.create_bucket(Bucket="datasets")
        for item in materials["dataset"]["objects"]:
            client.put_object(
                Bucket="datasets",
                Key="authority/" + item["object_key"],
                Body=(
                    SDK / "tests/fixtures/mds-reader/raw" / item["object_key"]
                ).read_bytes(),
            )
        environment = dict(os.environ)
        for slot in ("DATASET", "RUN_STORE"):
            environment[f"SKYWRIGHT_{slot}_ACCESS_KEY_ID"] = "test-access-key"
            environment[f"SKYWRIGHT_{slot}_SECRET_ACCESS_KEY"] = "test-secret-key"
        proofs = {}
        counter = 0

        def execute(
            run_id, *, interrupt=-1, source=None, reset=False, changed=False, expected=0
        ):
            nonlocal counter
            directory = tmp_path / str(counter)
            counter += 1
            directory.mkdir()
            d, m = deepcopy(definition), deepcopy(materials)
            m["runId"] = run_id
            m["sourceCheckpoint"] = source
            d["orderingReset"] = reset
            if changed:
                d["datasetDefinition"]["version"] = m["dataset"]["version"] = "v2"
            (directory / "definition.json").write_text(json.dumps(d))
            (directory / "materials.json").write_text(json.dumps(m))
            if run_id in proofs:
                (directory / "proof.json").write_text(json.dumps(proofs[run_id]))
            shutil.copy(
                SDK / "tests/support/managed_project/skywright_project.py", directory
            )
            launch = 'import sys,runpy;from pathlib import Path;sys.path[:0]=[sys.argv[1],str(Path.cwd())];import skywright;assert Path(skywright.__file__).is_relative_to(Path(sys.argv[1]));runpy.run_path(sys.argv[2],run_name="__main__")'
            process = subprocess.run(
                [
                    sys.executable,
                    "-I",
                    "-c",
                    launch,
                    str(installed_recovery_sdk),
                    str(SDK / "tests/support/managed_runtime_scenario.py"),
                ],
                cwd=directory,
                env={**environment, "FIXTURE_INTERRUPT_STEP": str(interrupt)},
                capture_output=True,
                text=True,
                timeout=60,
            )
            assert process.returncode == expected, (process.stdout, process.stderr)
            result = json.loads(process.stdout.splitlines()[-1])
            if result["outcome"] == "startup-refused":
                assert "Traceback" not in process.stderr
                return directory, result
            result["step"] = result["last_committed_step"]
            result["reference"] = result["latest_durable_checkpoint"]
            # subprocess.run reaped the process; the private supervisor owns this evidence.
            proofs[run_id] = {
                "run_id": run_id,
                "attempt_id": result["attempt_id"],
                "condition": "stopped",
                "reference": "fixture-supervisor:reaped",
            }
            return directory, result

        baseline_dir, baseline = execute(str(uuid4()))
        expected_ordinals = json.loads((baseline_dir / "committed.json").read_text())
        resumed_id = str(uuid4())
        _, seed = execute(resumed_id, interrupt=5, expected=75)
        resumed_dir, resumed = execute(resumed_id)
        assert resumed["step"] == baseline["step"] == 12
        assert (
            json.loads((resumed_dir / "committed.json").read_text())
            == expected_ordinals
        )
        assert json.loads((resumed_dir / "started.json").read_text())["step"] == 5
        source = {
            "runId": resumed_id,
            "reference": seed["reference"],
            "storage": definition["storage"]["execution"],
        }
        clone_dir, _ = execute(str(uuid4()), source=source)
        assert (
            json.loads((clone_dir / "committed.json").read_text()) == expected_ordinals
        )
        reset_dir, _ = execute(str(uuid4()), source=source, changed=True, reset=True)
        initial = json.loads((reset_dir / "started.json").read_text())
        assert initial["step"] == 5 and initial["offset"] == 0
        rejected_dir, rejected = execute(
            str(uuid4()), source=source, changed=True, expected=1
        )
        assert not (rejected_dir / "started.json").exists()
        assert rejected["outcome"] == "failed"

        for fault in ("missing", "corrupt"):
            if fault == "missing":
                client.delete_object(Bucket="datasets", Key="authority/index.json")
            else:
                client.put_object(
                    Bucket="datasets", Key="authority/index.json", Body=b"corrupt index"
                )
            refused_id = str(uuid4())
            refused_dir, refusal = execute(refused_id, expected=1)
            assert refusal["outcome"] == "startup-refused"
            assert refusal["code"] == "RECOVERY_UNAVAILABLE"
            assert not (refused_dir / "started.json").exists()
            assert (
                client.list_objects_v2(
                    Bucket="outputs", Prefix=f"stable-project/{refused_id}/v1/"
                ).get("KeyCount", 0)
                == 0
            )
