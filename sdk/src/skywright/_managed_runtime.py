"""Library-owned assembly of an accepted Run Definition and its delivery materials.

Materials supply pinned artifact bytes and Run Record/location facts. Configuration,
ordering seed and recovery policy are read only from the accepted definition.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from contextlib import ExitStack
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, cast
from uuid import UUID

from skywright._run_definition import RunDefinition
from skywright._training import Accelerator, TrainingProcessResult, run_training_process
from skywright._training_types import CheckpointSnapshot
from skywright.configuration import ConfigurationContract
from skywright.credentials import CredentialProjectionError, s3_credentials
from skywright.dataset import (
    DatasetDefinition,
    DatasetObject,
    MdsDatasetAccess,
    StorageLocation,
)
from skywright.metrics import MetricSchema, ProjectMetricContract
from skywright.recovery import (
    PreviousWriterVerifier,
    RecoveryAdmissionError,
    uncertain_previous_writer,
)
from skywright.run_store import (
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)

ENTRY_POINT = "skywright_project:train"
_MAX_DOCUMENT_BYTES = 16 * 1024 * 1024


def _object(value: object, fields: set[str], name: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(cast(dict[str, Any], value)) != fields:
        raise ValueError(f"{name} has missing or unsupported fields")
    return cast(dict[str, Any], value)


def read_document(path: Path) -> str:
    with path.open("rb") as source:
        body = source.read(_MAX_DOCUMENT_BYTES + 1)
    if len(body) > _MAX_DOCUMENT_BYTES:
        raise ValueError("runtime document exceeds its byte limit")
    return body.decode("utf-8")


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate runtime material field")
        value[key] = item
    return value


def _artifact(document: object, identity: Mapping[str, Any]) -> str:
    if not isinstance(document, str):
        raise ValueError("pinned contract bytes are missing")
    if "sha256:" + hashlib.sha256(document.encode()).hexdigest() != identity["digest"]:
        raise ValueError(
            "contract artifact digest differs from the accepted definition"
        )
    return document


def _storage(value: Mapping[str, Any], project: str, run_id: str) -> TargetStorage:
    return TargetStorage(
        value["storageId"],
        value["endpoint"],
        value["bucket"],
        value["region"],
        project,
        run_id,
        addressing_style=value["addressingMode"],
        compatibility_options=value["compatibilityOptions"],
        credential_slot="run_store",
    )


@dataclass(frozen=True)
class ManagedRuntime:
    definition: RunDefinition
    run_id: str
    project_version: str
    configuration: Mapping[str, object]
    dataset_definition: DatasetDefinition
    dataset_location: StorageLocation
    target: TargetStorage
    metrics: ProjectMetricContract
    source_run_id: str | None
    source_reference: str | None
    source_target: TargetStorage | None
    image: str

    @classmethod
    def decode(
        cls, definition_document: str, materials_document: str
    ) -> ManagedRuntime:
        definition = RunDefinition.decode(definition_document)
        value = definition.value()
        if value["schemaVersion"] != 2:
            raise ValueError("managed runtime requires Run Definition version 2")
        materials = _object(
            json.loads(materials_document, object_pairs_hook=_pairs),
            {
                "materialsVersion",
                "runId",
                "image",
                "configurationContract",
                "metricContract",
                "dataset",
                "datasetLocation",
                "sourceCheckpoint",
            },
            "runtime materials",
        )
        if (
            type(materials["materialsVersion"]) is not int
            or materials["materialsVersion"] != 1
        ):
            raise ValueError("runtime materials version is unsupported")
        run_id = str(UUID(materials["runId"]))
        project = value["trainingProjectVersion"]
        target_request = value["targetRequest"]
        if (
            target_request["purchaseMode"] != "local"
            or target_request["targetClass"]
            not in (
                "local-single-gpu",
                "local-multi-gpu",
            )
            or "rocm" not in project["images"]
        ):
            raise ValueError("managed runtime supports only local AMD targets")
        image = materials["image"]
        if (
            not isinstance(image, str)
            or not image.endswith("@" + project["images"]["rocm"])
            or re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}", image)
            is None
        ):
            raise ValueError(
                "runtime image is not the accepted digest-pinned ROCm artifact"
            )
        configuration_artifact = _artifact(
            materials["configurationContract"], project["configurationContract"]
        )
        if (
            project["configurationContract"]["skywrightSchema"]
            != ConfigurationContract.skywright_schema_identity()
        ):
            raise ValueError(
                "configuration schema identity differs from the installed SDK"
            )
        contract = ConfigurationContract.compile(configuration_artifact)
        configuration = contract.resolve(
            cast(Mapping[str, object], value["configuration"])
        )
        if configuration != value["configuration"]:
            raise ValueError("accepted configuration is not fully resolved")
        if project["metricContract"]["skywrightSchema"] != MetricSchema.identity():
            raise ValueError("metric schema identity differs from the installed SDK")
        metrics = ProjectMetricContract(
            _artifact(materials["metricContract"], project["metricContract"]),
            expected_digest=project["metricContract"]["digest"],
            project_identity=project["projectIdentity"],
        )
        data = _object(
            materials["dataset"],
            {
                "datasetIdentity",
                "version",
                "contentFingerprint",
                "manifestIdentity",
                "objects",
            },
            "Dataset materials",
        )
        if any(
            data[key] != expected
            for key, expected in value["datasetDefinition"].items()
        ):
            raise ValueError("Dataset identity differs from the accepted definition")
        dataset = DatasetDefinition(
            json.dumps(
                [data["datasetIdentity"], data["version"]], separators=(",", ":")
            ),
            data["contentFingerprint"],
            data["manifestIdentity"],
            tuple(
                DatasetObject(
                    **_object(
                        item, {"object_key", "byte_count", "sha256"}, "Dataset object"
                    )
                )
                for item in data["objects"]
            ),
        )
        location = StorageLocation(
            **_object(
                materials["datasetLocation"],
                {
                    "storage_id",
                    "endpoint",
                    "bucket",
                    "region",
                    "prefix",
                    "copy_id",
                    "generation",
                    "lease_id",
                    "path_style",
                    "checksum_calculation",
                },
                "Dataset location",
            )
        )
        source = materials["sourceCheckpoint"]
        source_run = source_reference = None
        source_target = None
        if source is not None:
            source = _object(
                source, {"runId", "reference", "storage"}, "source checkpoint"
            )
            source_run = str(UUID(source["runId"]))
            source_reference = source["reference"]
            source_target = _storage(
                _object(
                    source["storage"],
                    {
                        "storageId",
                        "registrationRevision",
                        "configurationRevision",
                        "endpoint",
                        "bucket",
                        "region",
                        "addressingMode",
                        "compatibilityOptions",
                    },
                    "source storage",
                ),
                project["projectIdentity"],
                source_run,
            )
            if (
                source_run == run_id
                or not isinstance(source_reference, str)
                or not source_reference
            ):
                raise ValueError("clone requires a distinct source Run and checkpoint")
        elif value["orderingReset"]:
            raise ValueError("Ordering Reset requires an explicit checkpoint seed")
        return cls(
            definition,
            run_id,
            project["manifestArtifactDigest"],
            configuration,
            dataset,
            location,
            _storage(value["storage"]["execution"], project["projectIdentity"], run_id),
            metrics,
            source_run,
            source_reference,
            source_target,
            image,
        )

    def run(
        self,
        cache_directory: Path,
        *,
        _accelerator: Accelerator | None = None,
        _previous_writer_verifier: PreviousWriterVerifier = uncertain_previous_writer,
    ) -> TrainingProcessResult:
        # Validate both projections before any storage access or project import.
        try:
            s3_credentials("dataset")
            s3_credentials("run_store")
        except CredentialProjectionError as failure:
            raise RecoveryAdmissionError(
                "RECOVERY_UNAVAILABLE", str(failure)
            ) from failure
        value = self.definition.value()
        configuration = value["configuration"]
        seed = configuration["reproducibility"]["seed"]
        ordering = configuration["dataset"]["ordering"]
        recorder = RunStoreRecorder(self.target)

        def seed_checkpoint() -> CheckpointSnapshot:
            assert self.source_run_id is not None and self.source_reference is not None
            return RunStoreReader(
                self.source_target or replace(self.target, run_id=self.source_run_id)
            ).read_exact(self.source_reference)

        with ExitStack() as resources:

            def dataset_factory() -> MdsDatasetAccess:
                return resources.enter_context(
                    MdsDatasetAccess(
                        self.dataset_definition,
                        self.dataset_location,
                        cache_directory=cache_directory,
                        seed=seed,
                        ordering_policy=ordering["policy"],
                        ordering_version=ordering["version"],
                    )
                )

            return run_training_process(
                ENTRY_POINT,
                run_id=self.run_id,
                project_version=self.project_version,
                configuration=self.configuration,
                dataset=dataset_factory,
                metric_contracts=self.metrics,
                skywright_metric_schema=value["trainingProjectVersion"][
                    "metricContract"
                ]["skywrightSchema"]["version"],
                recorder=recorder,
                seed=seed,
                maximum_recovery_debt=value["executionPolicy"]["maximumRecoveryDebt"],
                previous_writer_verifier=_previous_writer_verifier,
                resume_from=seed_checkpoint if self.source_run_id is not None else None,
                source_run_id=self.source_run_id,
                ordering_reset=value["orderingReset"],
                accelerator=_accelerator or Accelerator("rocm", 0),
            )
