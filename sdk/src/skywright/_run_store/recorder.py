"""Training Process recorder backed by the Run Store implementation."""

import hashlib
import time
from collections.abc import Callable, Mapping
from decimal import Decimal
from pathlib import Path
from typing import Any, Protocol, cast

from skywright._run_store.implementation import (
    CheckpointCodec,
    OperationControl,
    TargetStorage,
)
from skywright._run_store.implementation import (
    RunStoreRecorder as _RunStoreRecorder,
)
from skywright._run_store.metric_writer import (
    MetricProgressWriter,
    PeriodicWait,
    wait_for_flush,
)
from skywright._training_errors import ObservabilityShutdownIncomplete
from skywright._training_types import (
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationReport,
    MetricObservation,
)


class _WallTimeMetricRecorder(Protocol):
    def publish_wall_time(self, observation: MetricObservation) -> None: ...


class RunStoreRecorder(_RunStoreRecorder):
    """Run Store recorder with attempt-scoped metric and progress persistence."""

    def __init__(
        self,
        target: TargetStorage,
        *,
        progress_recorder: Any | None = None,
        checkpoint_codec: CheckpointCodec | None = None,
        client: Any | None = None,
        session_factory: Any | None = None,
        multipart_threshold: int = 64 * 1024 * 1024,
        multipart_part_size: int = 64 * 1024 * 1024,
        operation_control: OperationControl | None = None,
        metric_staging_directory: Path | None = None,
        metric_wall_clock: Callable[[], float] = time.time,
        metric_periodic_wait: PeriodicWait = wait_for_flush,
    ) -> None:
        super().__init__(
            target,
            progress_recorder=progress_recorder,
            checkpoint_codec=checkpoint_codec,
            client=client,
            session_factory=session_factory,
            multipart_threshold=multipart_threshold,
            multipart_part_size=multipart_part_size,
            operation_control=operation_control,
        )
        self._metric_configuration: Mapping[str, object] | None = None
        self._metric_staging_directory = metric_staging_directory
        self._metric_wall_clock = metric_wall_clock
        self._metric_periodic_wait = metric_periodic_wait
        self._metric_writer: MetricProgressWriter | None = None
        self._observability_finalized = False
        self._observability_shutdown_incomplete: (
            ObservabilityShutdownIncomplete | None
        ) = None
        self._shutdown_grace_seconds = 30.0

    def configure_metrics(
        self,
        configuration: Mapping[str, object],
        *,
        shutdown_grace_seconds: float = 30.0,
    ) -> None:
        """Supply the resolved Run Configuration before attempt publication."""
        if self._attempt is not None:
            raise RuntimeError("metrics must be configured before attempt publication")
        self._metric_configuration = configuration
        self._shutdown_grace_seconds = shutdown_grace_seconds

    def publish_attempt(self, attempt: ExecutionAttemptRecord) -> None:
        super().publish_attempt(attempt)
        if self._metric_configuration is None:
            return
        untyped_metrics = self._metric_configuration.get("metrics", {})
        if not isinstance(untyped_metrics, Mapping):
            raise TypeError("Run Configuration metrics must be an object")
        metrics = cast(Mapping[str, object], untyped_metrics)
        flush_interval = metrics.get("flushInterval", 10)
        segment_roll = metrics.get("segmentRoll", 1000)
        if (
            isinstance(flush_interval, bool)
            or not isinstance(flush_interval, int | float | Decimal)
            or flush_interval <= 0
        ):
            raise ValueError("metrics.flushInterval must be positive")
        if (
            isinstance(segment_roll, bool)
            or not isinstance(segment_roll, int)
            or segment_roll < 1
        ):
            raise ValueError("metrics.segmentRoll must be a positive integer")
        metrics_prefix = f"{self.protocol.run_prefix}metrics/"
        self._metric_writer = MetricProgressWriter(
            attempt=attempt,
            configuration=self._metric_configuration,
            flush_interval=float(flush_interval),
            segment_roll=segment_roll,
            segment_key=lambda segment: self.protocol.metric_segment_key(
                attempt.attempt_id, segment
            ),
            publish_segment=self._publish_metric_segment,
            publish_progress=self._publish_progress,
            configuration_already_exported=bool(self._list_keys(metrics_prefix)),
            staging_directory=self._metric_staging_directory,
            wall_clock=self._metric_wall_clock,
            periodic_wait=self._metric_periodic_wait,
        )

    def publish_step(
        self,
        step: int,
        dataset_cursor: DatasetCursor,
        observations: tuple[MetricObservation, ...],
        latest_durable_step: int | None,
        latest_durable_checkpoint: str | None,
    ) -> None:
        super().publish_step(
            step,
            dataset_cursor,
            observations,
            latest_durable_step,
            latest_durable_checkpoint,
        )
        if self._metric_writer is not None:
            self._metric_writer.publish_step(
                step,
                observations,
                latest_durable_step,
                latest_durable_checkpoint,
            )

    def publish_wall_time(self, observation: MetricObservation) -> None:
        self._require_open()
        accepted_at = self._metric_wall_clock()
        progress = cast(_WallTimeMetricRecorder | None, self._progress)
        if progress is not None:
            progress.publish_wall_time(observation)
        if self._metric_writer is not None:
            self._metric_writer.publish_wall_time(observation, accepted_at)

    def confirm_checkpoint(self, step: int, reference: str) -> None:
        previous = self._latest_confirmation
        super().confirm_checkpoint(step, reference)
        if self._metric_writer is not None and self._latest_confirmation != previous:
            self._metric_writer.confirm_checkpoint(step, reference)

    def finalize_observability(self) -> None:
        if self._observability_finalized:
            return
        self._observability_finalized = True
        if self._metric_writer is not None:
            try:
                self._metric_writer.finalize(
                    deadline=time.monotonic() + self._shutdown_grace_seconds
                )
            except ObservabilityShutdownIncomplete as failure:
                self._observability_shutdown_incomplete = failure
                raise

    def publish_report(self, report: ExecutionTerminationReport) -> None:
        self.finalize_observability()
        if self._observability_shutdown_incomplete is not None:
            raise self._observability_shutdown_incomplete
        super().publish_report(report)

    def _publish_metric_segment(
        self, key: str, body: bytes, expected: bytes | None
    ) -> None:
        self._replace_object(
            key,
            body,
            expected,
            kind="metric-segment",
            content_type="application/octet-stream",
            require_prefix_extension=True,
        )

    def _publish_progress(self, body: bytes, expected: bytes | None) -> None:
        self._replace_object(
            self.protocol.progress_key(),
            body,
            expected,
            kind="progress-record",
            content_type="application/json",
            require_prefix_extension=False,
        )

    def _replace_object(
        self,
        key: str,
        body: bytes,
        expected: bytes | None,
        *,
        kind: str,
        content_type: str,
        require_prefix_extension: bool,
    ) -> None:
        metadata = {
            "skywright-sha256": hashlib.sha256(body).hexdigest(),
            "skywright-kind": kind,
            "skywright-schema": "v1",
            "skywright-size": str(len(body)),
            "skywright-media-type": content_type,
        }
        existing, etag = self._existing_object(key)
        if existing == body:
            return
        if existing is not None:
            if expected is None:
                if require_prefix_extension and not body.startswith(existing):
                    raise RuntimeError(
                        f"Run Store object is not a prefix of its replacement at {key}"
                    )
            elif existing != expected:
                raise RuntimeError(f"Run Store object changed concurrently at {key}")
            if require_prefix_extension and not body.startswith(existing):
                raise RuntimeError(
                    f"Run Store object is not a prefix of its replacement at {key}"
                )
        request: dict[str, Any] = {
            "Bucket": self.target.bucket,
            "Key": key,
            "Body": body,
            "ContentLength": len(body),
            "ContentType": content_type,
            "Metadata": metadata,
        }
        if existing is None:
            request["IfNoneMatch"] = "*"
        elif etag is None:
            raise RuntimeError(
                f"Run Store object has no compare-and-set identity at {key}"
            )
        else:
            request["IfMatch"] = etag
        try:
            self._client.put_object(**request)
        except Exception:
            reconciled, _ = self._existing_object(key)
            if reconciled == body:
                return
            raise

    def _existing_object(self, key: str) -> tuple[bytes | None, str | None]:
        try:
            response = self._client.get_object(Bucket=self.target.bucket, Key=key)
        except Exception as failure:
            response_details = getattr(failure, "response", {})
            code = response_details.get("Error", {}).get("Code")
            status = response_details.get("ResponseMetadata", {}).get("HTTPStatusCode")
            if (
                isinstance(failure, KeyError)
                or code in {"NoSuchKey", "404"}
                or status == 404
            ):
                return None, None
            raise
        body = response["Body"].read()
        metadata = response.get("Metadata", {})
        if (
            response.get("ContentLength") != len(body)
            or metadata.get("skywright-size") != str(len(body))
            or metadata.get("skywright-sha256") != hashlib.sha256(body).hexdigest()
        ):
            raise RuntimeError(f"RUN_STORE_DIGEST_MISMATCH at {key}")
        etag = response.get("ETag")
        return body, etag if isinstance(etag, str) else None
