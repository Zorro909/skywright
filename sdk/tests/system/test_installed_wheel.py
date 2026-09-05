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


def test_wheel_declares_the_runtime_and_run_store_dependencies(
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
assert metadata("skywright").get_all("Requires-Dist") == [
    "boto3<2,>=1.40",
    "brotli<2,>=1.1",
    "jsonschema[format]<5,>=4.25",
    "numpy<3,>=2.1; python_version < '3.14'",
    "numpy<3,>=2.2; python_version >= '3.14'",
    "pillow<13,>=11",
    "protobuf==7.36.0",
    "python-snappy<1,>=0.7",
    "safetensors<1,>=0.6",
    "tensorboard==2.21.0",
    "xxhash<4,>=3.5",
    "zstandard<1,>=0.23",
    "mosaicml-streaming==0.13.0; (python_version < '3.14') and extra == 'dataset'",
    "torchvision==0.27.0; (python_version < '3.14') and extra == 'dataset'",
]
assert files(skywright).joinpath("py.typed").is_file()
assert importlib.util.find_spec("boto3") is None
assert importlib.util.find_spec("brotli") is None
assert importlib.util.find_spec("jsonschema") is None
assert importlib.util.find_spec("numpy") is None
assert importlib.util.find_spec("PIL") is None
assert importlib.util.find_spec("google") is None
assert importlib.util.find_spec("safetensors") is None
assert importlib.util.find_spec("snappy") is None
assert importlib.util.find_spec("tensorboard") is None
assert importlib.util.find_spec("torch") is None
assert importlib.util.find_spec("xxhash") is None
assert importlib.util.find_spec("zstandard") is None
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


def test_installed_wheel_ships_tensorboard_event_encoding(
    direct_wheel_path: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    environment = tmp_path / "metric-environment"
    subprocess.run(
        [sys.executable, "-m", "venv", environment],
        check=True,
        env=isolated_process_environment,
    )
    subprocess.run(
        [
            environment / "bin" / "python",
            "-m",
            "pip",
            "--disable-pip-version-check",
            "install",
            direct_wheel_path,
        ],
        check=True,
        env=isolated_process_environment,
    )
    check = """
from pathlib import Path
from tempfile import TemporaryDirectory

from tensorboard.backend.event_processing.event_file_loader import EventFileLoader

from skywright._run_store.metric_events import MetricSegment
from skywright import MetricObservation

with TemporaryDirectory() as directory:
    segment = MetricSegment(
        wall_time=1.0,
        staging_directory=Path(directory),
        configuration='{"nested":[1,null]}',
    )
    segment.append(MetricObservation("loss", 1, 0.5), 2.0)
    published = Path(directory) / "published.tfevents"
    published.write_bytes(segment.bytes())
    segment.close()
    events = list(EventFileLoader(str(published)).Load())
    assert events[0].file_version == "brain.Event:2"
    assert any(value.tag == "loss" for event in events for value in event.summary.value)
"""
    subprocess.run(
        [environment / "bin" / "python", "-I", "-c", check],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
    )


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


def test_dataset_command_help_is_available_without_runtime_services(
    installed_sdk: Path,
    tmp_path: Path,
    isolated_process_environment: dict[str, str],
) -> None:
    completed = subprocess.run(
        [installed_sdk / "bin" / "skywright-datasets", "--help"],
        check=True,
        cwd=tmp_path,
        env=isolated_process_environment,
        text=True,
        capture_output=True,
    )

    assert "usage: skywright-datasets" in completed.stdout
    assert "Publish storage-ready Dataset corpora" in completed.stdout


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
import json
import os

from skywright import DatasetBatch, DatasetCursor, MetricCatalog, MetricDefinition


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
    def confirm_checkpoint(self, step, reference): pass
    def publish_step(self, step, dataset_cursor, observations, durable_step, durable_ref):
        with open(os.environ["SKYWRIGHT_SYSTEM_METRICS_OUTPUT"], "w") as output:
            json.dump([
                [observation.name, observation.step, observation.value]
                for observation in observations
            ], output)
    def publish_wall_time(self, observation): pass
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
            units=frozenset(("bytes", "items_per_second", "seconds")),
            project_definitions=(),
            system_definitions=(
                MetricDefinition(
                    "skywright/system/throughput",
                    "real",
                    "items_per_second",
                    "maximize",
                    step_reduction="mean",
                    minimum=0,
                ),
                MetricDefinition(
                    "skywright/system/data_loading_wait",
                    "real",
                    "seconds",
                    "minimize",
                    step_reduction="sum",
                    minimum=0,
                ),
                MetricDefinition(
                    "skywright/system/memory_used",
                    "integer",
                    "bytes",
                    "none",
                    recording_basis="wall_time",
                    step_reduction=None,
                    minimum=0,
                ),
            ),
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
    metrics_output = tmp_path / "system-metrics.json"
    environment["SKYWRIGHT_SYSTEM_METRICS_OUTPUT"] = str(metrics_output)
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

    assert completed.returncode == 0, completed.stdout + completed.stderr
    report = json.loads(completed.stdout)
    assert report["outcome"] == "completed"
    assert report["cause"] == "completed"
    assert report["last_committed_step"] == 1
    assert report["latest_durable_step"] == 1
    observations = json.loads(metrics_output.read_text(encoding="utf-8"))
    assert [observation[:2] for observation in observations] == [
        ["skywright/system/throughput", 1],
        ["skywright/system/data_loading_wait", 1],
    ]
    assert observations[0][2] > 0
    assert observations[1][2] >= 0
