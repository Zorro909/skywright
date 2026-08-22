"""TensorBoard event encoding for one attempt-owned Metric Segment."""

# TensorBoard's generated protobuf modules do not publish complete type metadata.
# pyright: reportAttributeAccessIssue=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Mapping
from decimal import Decimal
from pathlib import Path
from typing import BinaryIO, cast

from tensorboard.compat.proto import (
    event_pb2,
    summary_pb2,
    tensor_pb2,
    tensor_shape_pb2,
    types_pb2,
)
from tensorboard.plugins.text import metadata as text_metadata
from tensorboard.summary.writer.record_writer import RecordWriter

from skywright._training_types import MetricObservation

CONFIGURATION_TAG = "skywright/run_configuration"


def canonical_json(value: object) -> str:
    """Encode the resolved Run Configuration without numeric coercion."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, (float, Decimal)):
        numeric = Decimal(str(value))
        if not numeric.is_finite():
            raise ValueError("canonical JSON cannot contain a non-finite number")
        return format(numeric, "f")
    if isinstance(value, list | tuple):
        items = cast(list[object] | tuple[object, ...], value)
        return "[" + ",".join(canonical_json(item) for item in items) + "]"
    if isinstance(value, Mapping):
        untyped_mapping = cast(Mapping[object, object], value)
        if not all(isinstance(name, str) for name in untyped_mapping):
            raise TypeError("canonical JSON object names must be strings")
        mapping = cast(Mapping[str, object], untyped_mapping)
        return (
            "{"
            + ",".join(
                f"{json.dumps(name, ensure_ascii=False)}:{canonical_json(mapping[name])}"
                for name in sorted(mapping)
            )
            + "}"
        )
    raise TypeError(f"cannot serialize {type(value).__name__} as canonical JSON")


class MetricSegment:
    """Permission-restricted append-only staging file for TensorBoard records."""

    def __init__(
        self,
        *,
        wall_time: float,
        staging_directory: Path | None,
        purge_step: int | None = None,
        configuration: str | None = None,
    ) -> None:
        descriptor, name = tempfile.mkstemp(
            prefix="skywright-metrics-",
            suffix=".tfevents",
            dir=staging_directory,
        )
        os.fchmod(descriptor, 0o600)
        self.path = Path(name)
        self._stream: BinaryIO = os.fdopen(descriptor, "w+b")
        self._records = RecordWriter(self._stream)
        self.scalar_count = 0
        self._write(
            event_pb2.Event(
                wall_time=wall_time,
                file_version="brain.Event:2",
                source_metadata=event_pb2.SourceMetadata(
                    writer="skywright.metric-segment-v1"
                ),
            )
        )
        if purge_step is not None:
            self._write(
                event_pb2.Event(
                    wall_time=wall_time,
                    step=purge_step,
                    session_log=event_pb2.SessionLog(status=event_pb2.SessionLog.START),
                )
            )
        if configuration is not None:
            self._write(
                event_pb2.Event(
                    wall_time=wall_time,
                    step=0,
                    summary=summary_pb2.Summary(
                        value=[
                            summary_pb2.Summary.Value(
                                tag=CONFIGURATION_TAG,
                                metadata=text_metadata.create_summary_metadata(
                                    display_name="Skywright Run Configuration",
                                    description="Complete canonical JSON Run Configuration",
                                ),
                                tensor=tensor_pb2.TensorProto(
                                    dtype=types_pb2.DT_STRING,
                                    tensor_shape=tensor_shape_pb2.TensorShapeProto(),
                                    string_val=[configuration.encode("utf-8")],
                                ),
                            )
                        ]
                    ),
                )
            )

    def append(self, observation: MetricObservation, wall_time: float) -> None:
        self._write(
            event_pb2.Event(
                wall_time=wall_time,
                step=observation.step,
                summary=summary_pb2.Summary(
                    value=[
                        summary_pb2.Summary.Value(
                            tag=observation.name,
                            simple_value=float(observation.value),
                        )
                    ]
                ),
            )
        )
        self.scalar_count += 1

    def bytes(self) -> bytes:
        self._records.flush()
        position = self._stream.tell()
        self._stream.seek(0)
        body = self._stream.read()
        self._stream.seek(position)
        return body

    def close(self) -> None:
        if not self._records.closed:
            self._records.close()
        self.path.unlink(missing_ok=True)

    def _write(self, event: event_pb2.Event) -> None:
        self._records.write(event.SerializeToString())
