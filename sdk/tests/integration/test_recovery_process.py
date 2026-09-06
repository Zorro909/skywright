# S3 service responses are runtime-shaped; test supervisors own process evidence.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
from integration.test_run_store_system import seaweedfs

SDK = Path(__file__).parents[2]
SCENARIO = SDK / "tests/support/recovery_process_scenario.py"


@pytest.fixture(scope="module")
def installed_recovery_sdk(tmp_path_factory):
    root = tmp_path_factory.mktemp("installed-recovery")
    subprocess.run(
        ["uv", "build", "--wheel", "--out-dir", str(root / "dist")],
        cwd=SDK,
        check=True,
        capture_output=True,
        text=True,
    )
    wheel = next((root / "dist").glob("*.whl"))
    subprocess.run(
        [
            "uv",
            "pip",
            "install",
            "--no-deps",
            "--target",
            str(root / "installed"),
            str(wheel),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return root / "installed"


class Supervisor:
    def __init__(self, installed, root, endpoint, bucket, run_id):
        self.installed, self.root, self.endpoint, self.bucket, self.run_id = (
            installed,
            root,
            endpoint,
            bucket,
            run_id,
        )
        self.processes = []
        self.latest_proof = None
        self.counter = 0
        self.seed_inputs = {}

    def start(self, mode):
        directory = self.root / str(self.counter)
        self.counter += 1
        directory.mkdir(parents=True)
        if self.latest_proof is not None:
            (directory / "stopped-proof.json").write_text(json.dumps(self.latest_proof))
        source = 'import sys,runpy; from pathlib import Path; sys.path.insert(0,sys.argv[1]); import skywright; assert Path(skywright.__file__).is_relative_to(Path(sys.argv[1])); runpy.run_path(sys.argv[2],run_name="__main__")'
        process = subprocess.Popen(
            [sys.executable, "-I", "-c", source, str(self.installed), str(SCENARIO)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=directory,
        )
        assert process.stdin is not None
        process.stdin.write(
            json.dumps(
                {
                    "directory": str(directory),
                    "endpoint": self.endpoint,
                    "bucket": self.bucket,
                    "run_id": self.run_id,
                    "mode": mode,
                    **self.seed_inputs,
                }
            )
        )
        process.stdin.close()
        process.stdin = None
        self.processes.append(process)
        return process, directory

    def finish(self, process, directory, *, expected):
        stdout, stderr = process.communicate(timeout=60)
        assert process.returncode == expected, (stdout, stderr)
        attempt_path = directory / "attempt.json"
        if attempt_path.exists():
            attempt = json.loads(attempt_path.read_text())
            self.latest_proof = {
                "run_id": self.run_id,
                "attempt_id": attempt["attempt_id"],
                "condition": "stopped",
                "reference": f"fixture-supervisor:waitpid:{process.pid}",
            }
        return json.loads(stdout.splitlines()[-1]) if stdout.strip() else {}

    def wait_file(self, process, directory, name):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if (directory / name).exists():
                return json.loads((directory / name).read_text())
            if process.poll() is not None:
                pytest.fail(str(process.communicate()))
            time.sleep(0.02)
        pytest.fail(f"process did not publish {name}")

    def close(self):
        for process in self.processes:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=10)


def test_installed_sdk_refuses_suspended_writer_then_recovers_after_proven_death(
    installed_recovery_sdk, tmp_path
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        supervisor = Supervisor(
            installed_recovery_sdk, tmp_path, endpoint, "recovery", "run"
        )
        try:
            old, directory = supervisor.start("hold")
            supervisor.wait_file(old, directory, "entered.json")
            os.kill(old.pid, signal.SIGSTOP)
            refused, refused_dir = supervisor.start("interrupted")
            result = supervisor.finish(refused, refused_dir, expected=1)
            assert result["code"] == "RECOVERY_WRITER_UNCERTAIN"
            assert not (refused_dir / "entered.json").exists()
            assert not (refused_dir / "attempt.json").exists()
            os.kill(old.pid, signal.SIGCONT)
            (directory / "advance").touch()
            assert supervisor.wait_file(old, directory, "advanced.json")["step"] == 2
            objects = client.list_objects_v2(Bucket="recovery")["Contents"]
            assert (
                len([item for item in objects if "/checkpoints/" in item["Key"]]) == 1
            )
            progress = json.loads(
                client.get_object(
                    Bucket="recovery", Key="project/run/v1/progress.json"
                )["Body"].read()
            )
            assert progress["currentStep"] == 2
            old.kill()
            supervisor.finish(old, directory, expected=-signal.SIGKILL)
            recovered, recovered_dir = supervisor.start("interrupted")
            result = supervisor.finish(recovered, recovered_dir, expected=75)
            assert result["step"] == 3 and result["cause"] == "interrupted"
            entered = json.loads((recovered_dir / "entered.json").read_text())
            assert entered == {"step": 2, "value": 2, "cursor": 2}
        finally:
            supervisor.close()


def test_installed_sdk_bounds_recovery_after_abrupt_upload_loss(
    installed_recovery_sdk, tmp_path
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        supervisor = Supervisor(
            installed_recovery_sdk, tmp_path, endpoint, "recovery", "run"
        )
        try:
            lost, directory = supervisor.start("upload-loss")
            supervisor.wait_file(lost, directory, "upload-pending.json")
            lost.kill()
            supervisor.finish(lost, directory, expected=-signal.SIGKILL)
            assert client.list_multipart_uploads(Bucket="recovery").get("Uploads")
            for _ in range(3):
                crashed, crash_dir = supervisor.start("crash")
                supervisor.finish(crashed, crash_dir, expected=137)
                assert json.loads((crash_dir / "entered.json").read_text())["step"] == 0
            for _ in range(2):
                refused, refused_dir = supervisor.start("interrupted")
                result = supervisor.finish(refused, refused_dir, expected=1)
                assert result["code"] == "RECOVERY_EXHAUSTED"
                assert not (refused_dir / "attempt.json").exists()
            keys = [
                item["Key"]
                for item in client.list_objects_v2(Bucket="recovery")["Contents"]
            ]
            assert len([key for key in keys if key.endswith("/record.json")]) == 4
            assert not any(key.endswith("/report.json") for key in keys)
            exhaustion = json.loads(
                client.get_object(
                    Bucket="recovery", Key="project/run/v1/recovery/exhaustion.json"
                )["Body"].read()
            )
            assert exhaustion["prospectiveDebt"] == 4
        finally:
            supervisor.close()


@pytest.mark.parametrize("fault", ["missing", "corrupt", "denied", "timeout"])
def test_installed_sdk_checkpoint_fallback_and_storage_failure_outcomes(
    installed_recovery_sdk, tmp_path, fault
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        supervisor = Supervisor(
            installed_recovery_sdk, tmp_path, endpoint, "recovery", "run"
        )
        try:
            for _ in range(2):
                process, directory = supervisor.start("interrupted")
                supervisor.finish(process, directory, expected=75)
            checkpoints = sorted(
                item["Key"]
                for item in client.list_objects_v2(Bucket="recovery")["Contents"]
                if "/checkpoints/" in item["Key"]
            )
            if fault == "missing":
                client.delete_object(Bucket="recovery", Key=checkpoints[-1])
            elif fault == "corrupt":
                response = client.get_object(Bucket="recovery", Key=checkpoints[-1])
                with response["Body"] as body:
                    data = body.read()
                client.put_object(
                    Bucket="recovery",
                    Key=checkpoints[-1],
                    Body=data[:-1] + bytes([data[-1] ^ 1]),
                    Metadata=response["Metadata"],
                    ContentType=response["ContentType"],
                )
            process, directory = supervisor.start(
                fault if fault in {"denied", "timeout"} else "crash"
            )
            if fault in {"denied", "timeout"}:
                result = supervisor.finish(process, directory, expected=1)
                assert result["code"] == "RECOVERY_UNAVAILABLE"
                assert (
                    "CredentialProjectionError" if fault == "denied" else "TimeoutError"
                ) in result["detail"]
                assert not (directory / "entered.json").exists()
                assert not (directory / "attempt.json").exists()
            else:
                supervisor.finish(process, directory, expected=137)
                assert json.loads((directory / "entered.json").read_text()) == {
                    "step": 1,
                    "value": 1,
                    "cursor": 1,
                }
                attempt = json.loads((directory / "attempt.json").read_text())
                assert attempt["rejected_corrupt_checkpoints"][0]["code"] == (
                    "RUN_STORE_MISSING_OBJECT"
                    if fault == "missing"
                    else "RUN_STORE_DIGEST_MISMATCH"
                )
        finally:
            supervisor.close()


def test_installed_sdk_never_emits_75_when_interruption_report_is_not_durable(
    installed_recovery_sdk, tmp_path
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        supervisor = Supervisor(
            installed_recovery_sdk, tmp_path, endpoint, "recovery", "run"
        )
        try:
            process, directory = supervisor.start("report-loss")
            result = supervisor.finish(process, directory, expected=1)
            assert result["cause"] == "skywright_failure"
            assert result["outcome"] == "failed"
            keys = [
                item["Key"]
                for item in client.list_objects_v2(Bucket="recovery")["Contents"]
            ]
            assert not any(key.endswith("/report.json") for key in keys)
            assert any("/checkpoints/" in key for key in keys)
        finally:
            supervisor.close()


def test_installed_sdk_checks_project_state_after_registration_before_training(
    installed_recovery_sdk, tmp_path
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        supervisor = Supervisor(
            installed_recovery_sdk, tmp_path, endpoint, "recovery", "run"
        )
        try:
            initial, initial_dir = supervisor.start("interrupted")
            supervisor.finish(initial, initial_dir, expected=75)
            recovered, recovered_dir = supervisor.start("incomplete")
            result = supervisor.finish(recovered, recovered_dir, expected=1)
            assert result["cause"] == "contract_violation"
            assert (recovered_dir / "attempt.json").exists()
            assert (recovered_dir / "registered.json").exists()
            assert not (recovered_dir / "entered.json").exists()
            checkpoints = [
                item
                for item in client.list_objects_v2(Bucket="recovery")["Contents"]
                if "/checkpoints/" in item["Key"]
            ]
            assert len(checkpoints) == 1
        finally:
            supervisor.close()


def test_installed_sdk_recovers_clone_before_and_after_own_checkpoint(
    installed_recovery_sdk, tmp_path
):
    with seaweedfs() as (endpoint, client):
        client.create_bucket(Bucket="recovery")
        source = Supervisor(
            installed_recovery_sdk, tmp_path / "source", endpoint, "recovery", "source"
        )
        clone = Supervisor(
            installed_recovery_sdk, tmp_path / "clone", endpoint, "recovery", "clone"
        )
        try:
            process, directory = source.start("interrupted")
            seed = source.finish(process, directory, expected=75)
            clone.seed_inputs = {
                "source_run_id": "source",
                "seed_reference": seed["checkpoint"],
            }
            process, directory = clone.start("crash")
            clone.finish(process, directory, expected=137)
            assert json.loads((directory / "entered.json").read_text()) == {
                "step": 1,
                "value": 1,
                "cursor": 1,
            }
            process, directory = clone.start("interrupted")
            result = clone.finish(process, directory, expected=75)
            assert result["step"] == 2
            assert json.loads((directory / "entered.json").read_text()) == {
                "step": 1,
                "value": 1,
                "cursor": 1,
            }
            # The accepted clone inputs remain present, but recovery now owns its
            # continuation and no longer needs access to the original seed bytes.
            for item in client.list_objects_v2(
                Bucket="recovery", Prefix="project/source/v1/checkpoints/"
            )["Contents"]:
                client.delete_object(Bucket="recovery", Key=item["Key"])
            process, directory = clone.start("interrupted")
            result = clone.finish(process, directory, expected=75)
            assert result["step"] == 3
            assert json.loads((directory / "entered.json").read_text()) == {
                "step": 2,
                "value": 2,
                "cursor": 2,
            }
        finally:
            source.close()
            clone.close()
