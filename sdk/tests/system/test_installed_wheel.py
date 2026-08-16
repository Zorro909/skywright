import json
import os
import subprocess
import sys
from importlib.metadata import version
from pathlib import Path
from zipfile import ZipFile

SDK_ROOT = Path(__file__).parents[2]


def test_distribution_paths_expose_equivalent_wheel_contents(
    distribution_wheels: tuple[Path, Path],
) -> None:
    direct_wheel, source_derived_wheel = distribution_wheels

    with ZipFile(direct_wheel) as direct, ZipFile(source_derived_wheel) as rebuilt:
        direct_contents = {name: direct.read(name) for name in direct.namelist()}
        rebuilt_contents = {name: rebuilt.read(name) for name in rebuilt.namelist()}

    assert rebuilt_contents == direct_contents


def test_wheel_installs_only_the_configuration_validation_stack(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    consumer_python = installed_sdk / "bin" / "python"

    consumer_check = """
import importlib.util
from importlib.metadata import metadata, version
from importlib.resources import files

import skywright

assert version("skywright") == skywright.__version__
assert metadata("skywright").get_all("Requires-Dist") == ["jsonschema[format]<5,>=4.25"]
assert files(skywright).joinpath("py.typed").is_file()
assert importlib.util.find_spec("jsonschema") is not None
assert importlib.util.find_spec("torch") is None
print(skywright.__version__)
"""
    completed = subprocess.run(
        [consumer_python, "-I", "-c", consumer_check],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
        text=True,
        capture_output=True,
    )

    assert completed.stdout == f"{version('skywright')}\n"


def test_installed_package_is_complete_for_a_strict_typed_consumer(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    consumer = tmp_path / "consumer.py"
    consumer.write_text(
        (SDK_ROOT / "tests" / "consumer" / "consumer.py").read_text(),
        encoding="utf-8",
    )
    consumer_python = installed_sdk / "bin" / "python"

    subprocess.run(
        [
            sys.executable,
            "-m",
            "pyright",
            "--pythonpath",
            consumer_python,
            consumer,
        ],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
    )
    subprocess.run(
        [
            sys.executable,
            "-m",
            "pyright",
            "--pythonpath",
            consumer_python,
            "--verifytypes",
            "skywright",
        ],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
    )


def test_runtime_command_help_is_available_without_runtime_services(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-runtime", "--help"],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
        text=True,
        capture_output=True,
    )

    assert "usage: skywright-runtime" in completed.stdout
    assert "Execute a Skywright Training Project" in completed.stdout


def test_runtime_command_reports_version_and_source_revision(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-runtime", "--version"],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
        text=True,
        capture_output=True,
    )

    expected_source_revision = os.environ.get("SKYWRIGHT_SOURCE_REVISION", "unknown")
    assert completed.stdout == (
        f"skywright-runtime {version('skywright')}\n"
        f"source revision: {expected_source_revision}\n"
    )


def test_runtime_command_executes_a_training_project(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    (tmp_path / "installed_project.py").write_text(
        """
import random

assert random.random() == random.Random(8).random()


class State:
    def state_dict(self):
        return {"step": 1}

    def load_state_dict(self, state):
        pass


def train(context):
    context.register_checkpoint_state("state", State())
    context.start()
    context.commit_step(next(iter(context.dataset.batches(context.dataset_cursor))))


""",
        encoding="utf-8",
    )
    (tmp_path / "installed_runtime_support.py").write_text(
        """
from skywright import DatasetBatch, DatasetCursor, MetricCatalog


class Dataset:
    @property
    def ordering_fingerprint(self): return "sha256:installed-ordering"

    def batches(self, cursor):
        yield DatasetBatch(("item",), DatasetCursor(
            item_offset=1,
            epoch_step=1,
            ordering_fingerprint=self.ordering_fingerprint,
        ))


class Recorder:
    def publish_attempt(self, attempt): pass
    def publish_checkpoint(self, checkpoint): return "checkpoint:installed"
    def publish_step(self, step, dataset_cursor, observations, durable_step, durable_ref): pass
    def publish_artifact(self, artifact): pass
    def publish_sample(self, sample): pass
    def publish_report(self, report): pass


def dataset(): return Dataset()
def recorder(): return Recorder()


class MetricContracts:
    def compose(self, project_version, schema_identity):
        return MetricCatalog(
            project_identity=project_version,
            project_contract_digest="sha256:project",
            skywright_schema_identity=schema_identity,
            skywright_schema_digest="sha256:skywright",
            units=frozenset(("dimensionless",)),
            project_definitions=(),
        )


def metric_contracts(): return MetricContracts()
""",
        encoding="utf-8",
    )
    definition = tmp_path / "run.json"
    definition.write_text(
        json.dumps(
            {
                "run_id": "installed-run",
                "project_version": "installed-project@abc123",
                "configuration": {},
                "dataset_factory": "installed_runtime_support:dataset",
                "recorder_factory": "installed_runtime_support:recorder",
                "metric_contract_factory": "installed_runtime_support:metric_contracts",
                "skywright_metric_schema": "skywright-metrics@1",
                "seed": 8,
                "accelerator": {"kind": "cpu"},
            }
        ),
        encoding="utf-8",
    )
    environment = isolated_process_environment.copy()
    environment["PYTHONPATH"] = str(tmp_path)
    completed = subprocess.run(
        [
            installed_sdk / "bin" / "skywright-runtime",
            "installed_project:train",
            "--definition",
            definition,
        ],
        check=False,
        cwd=tmp_path,
        env=environment,
        text=True,
        capture_output=True,
    )

    assert completed.returncode == 0, completed.stderr
    report = json.loads(completed.stdout)
    assert report["outcome"] == "completed"
    assert report["cause"] == "completed"
    assert report["last_committed_step"] == 1
    assert report["latest_durable_step"] == 1
