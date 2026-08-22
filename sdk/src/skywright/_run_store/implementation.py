"""Private implementation behind the portable Run Store deep module."""

# The S3, NumPy, and optional PyTorch adapters necessarily cross libraries whose
# runtime-shaped values are not fully described by their published type metadata.
# Keep strict checking for concrete incompatibilities while containing Unknown at
# this private integration boundary.
# pyright: reportMissingImports=false, reportMissingTypeStubs=false
# pyright: reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false

from __future__ import annotations

import hashlib
import json
import os
import random
import re
import struct
import tempfile
import threading
import time
import uuid
from collections.abc import Callable, Mapping
from contextlib import suppress
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType, TracebackType
from typing import TYPE_CHECKING, Any, BinaryIO, NoReturn, cast
from urllib.parse import quote, unquote

import numpy as np

from skywright._training_errors import (
    CheckpointPublicationCancelled,
    TrainingContractViolation,
)

if TYPE_CHECKING:
    from skywright._run_store.progress import ProgressRecord
from skywright._training_types import (
    ArtifactRecord,
    CheckpointRejectionEvidence,
    CheckpointSnapshot,
    DatasetCursor,
    ExecutionAttemptRecord,
    ExecutionTerminationReport,
    MetricObservation,
    SampleRecord,
)

_MAX_COUNTER = (1 << 63) - 1
_DIGEST = re.compile(r"[0-9a-f]{64}")
_REFERENCE = re.compile(
    r"skywright-checkpoint:v1:(0|[1-9][0-9]*):sha256:([0-9a-f]{64})"
)
_MAX_DEPTH = 64
_MAX_LEAVES = 1_000_000
_MAX_MANIFEST = 16 * 1024 * 1024

_TORCH_DTYPES = {
    "torch.bool": "BOOL",
    "torch.uint8": "U8",
    "torch.int8": "I8",
    "torch.int16": "I16",
    "torch.int32": "I32",
    "torch.int64": "I64",
    "torch.float16": "F16",
    "torch.bfloat16": "BF16",
    "torch.float32": "F32",
    "torch.float64": "F64",
    "torch.complex64": "C64",
}
_NUMPY_FOR_TORCH = {
    "BOOL": np.dtype("bool"),
    "U8": np.dtype("uint8"),
    "I8": np.dtype("int8"),
    "I16": np.dtype("<i2"),
    "I32": np.dtype("<i4"),
    "I64": np.dtype("<i8"),
    "F16": np.dtype("<f2"),
    "F32": np.dtype("<f4"),
    "F64": np.dtype("<f8"),
    "C64": np.dtype("<c8"),
}


def _identity(value: str, label: str) -> str:
    if not value or value in {".", ".."} or "/" in value or "\x00" in value:
        raise ValueError(f"{label} must be a non-empty portable key component")
    return quote(value, safe="-._~", encoding="utf-8", errors="strict")


def _output_name(value: str) -> str:
    if not value or "\x00" in value:
        raise ValueError("Output name must be non-empty Unicode text")
    return quote(value, safe="-._~", encoding="utf-8", errors="strict")


def _step(value: int) -> str:
    if isinstance(value, bool) or not 0 <= value <= _MAX_COUNTER:
        raise ValueError("Step must be a non-negative signed 64-bit integer")
    return f"{value:019d}"


def _attempt(value: str) -> str:
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError) as error:
        raise ValueError(
            "Execution Attempt identity must be canonical UUID text"
        ) from error
    if str(parsed) != value:
        raise ValueError("Execution Attempt identity must be canonical UUID text")
    return value


def _digest(value: str) -> str:
    if _DIGEST.fullmatch(value) is None:
        raise ValueError("SHA-256 digest must be 64 lowercase hexadecimal characters")
    return value


@dataclass(frozen=True)
class CheckpointReference:
    """Location-independent identity of one immutable Checkpoint."""

    step: int
    digest: str

    def __post_init__(self) -> None:
        _step(self.step)
        _digest(self.digest)

    def __str__(self) -> str:
        return f"skywright-checkpoint:v1:{self.step}:sha256:{self.digest}"

    @classmethod
    def parse(cls, value: str) -> CheckpointReference:
        match = _REFERENCE.fullmatch(value)
        if match is None:
            raise ValueError("invalid checkpoint reference")
        step = int(match.group(1))
        if step > _MAX_COUNTER:
            raise ValueError("invalid checkpoint reference: Step exceeds signed 64-bit")
        return cls(step, match.group(2))


class RunStoreProtocol:
    """Construct and validate protocol-v1 semantic object identities."""

    def __init__(self, training_project_id: str, run_id: str) -> None:
        self._project = _identity(training_project_id, "Training Project identity")
        self._run = _identity(run_id, "Run identity")

    @property
    def run_prefix(self) -> str:
        return f"{self._project}/{self._run}/v1/"

    def attempt_record_key(self, attempt_id: str) -> str:
        return f"{self.run_prefix}attempts/{_attempt(attempt_id)}/record.json"

    def attempt_report_key(self, attempt_id: str) -> str:
        return f"{self.run_prefix}attempts/{_attempt(attempt_id)}/report.json"

    def checkpoint_key(self, step: int, digest: str) -> str:
        return (
            f"{self.run_prefix}checkpoints/{_step(step)}/{_digest(digest)}.safetensors"
        )

    def metric_segment_key(self, attempt_id: str, segment: int) -> str:
        return (
            f"{self.run_prefix}metrics/{_attempt(attempt_id)}/"
            f"events.out.tfevents.{_step(segment)}.skywright"
        )

    def progress_key(self) -> str:
        return f"{self.run_prefix}progress.json"

    def artifact_key(self, attempt_id: str, step: int, name: str) -> str:
        return self._output_key("artifacts", attempt_id, step, name)

    def sample_key(self, attempt_id: str, step: int, name: str) -> str:
        return self._output_key("samples", attempt_id, step, name)

    def _output_key(self, kind: str, attempt_id: str, step: int, name: str) -> str:
        encoded_name = _output_name(name)
        return f"{self.run_prefix}{kind}/{_attempt(attempt_id)}/{_step(step)}/{encoded_name}"


@dataclass
class SerializedCheckpoint:
    """Permission-restricted staged checkpoint removed when its context exits."""

    path: Path
    digest: str
    size: int

    def close(self) -> None:
        with suppress(FileNotFoundError):
            self.path.unlink()

    def __enter__(self) -> SerializedCheckpoint:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()


class _PortableTree:
    def __init__(self) -> None:
        self.entries: list[tuple[str, str, tuple[int, ...], bytes]] = []
        self._active: set[int] = set()

    def encode(self, value: object, depth: int = 0) -> object:
        if depth > _MAX_DEPTH:
            self._violate("portable value tree exceeds 64 recursive levels")
        if value is None:
            return {"type": "null"}
        if isinstance(value, bool):
            return {"type": "bool", "value": value}
        if isinstance(value, int):
            if not -(1 << 63) <= value <= _MAX_COUNTER:
                self._violate("integer is outside the signed 64-bit range")
            return {"type": "integer", "value": value}
        if isinstance(value, float):
            if not np.isfinite(value):
                self._violate("float is not finite")
            return {"type": "float", "value": value}
        if isinstance(value, str):
            return {"type": "string", "value": value}
        if isinstance(value, bytes):
            return self._entry("bytes", "U8", (len(value),), value)
        if isinstance(value, np.generic):
            scalar = np.asarray(value)
            return self._numpy_entry("numpy-scalar", scalar)
        if isinstance(value, np.ndarray):
            return self._numpy_entry("numpy-array", value)
        torch = _torch()
        if torch is not None and isinstance(value, torch.Tensor):
            return self._torch_entry(value, torch)
        if isinstance(value, list | tuple):
            return self._container(
                value,
                {"type": "tuple" if isinstance(value, tuple) else "list"},
                depth,
            )
        if isinstance(value, Mapping):
            return self._mapping(value, depth)
        self._violate(f"unsupported value of type {type(value).__name__}")

    def _container(
        self,
        value: list[object] | tuple[object, ...],
        result: dict[str, object],
        depth: int,
    ) -> object:
        self._enter(value)
        try:
            result["items"] = [self.encode(item, depth + 1) for item in value]
            return result
        finally:
            self._active.remove(id(value))

    def _mapping(self, value: Mapping[object, object], depth: int) -> object:
        self._enter(value)
        try:
            items: list[object] = []
            for key, item in value.items():
                if isinstance(key, bool) or not isinstance(key, str | int):
                    self._violate("mapping key is neither a string nor an integer")
                if isinstance(key, int) and not -(1 << 63) <= key <= _MAX_COUNTER:
                    self._violate("mapping key is outside the signed 64-bit range")
                items.append(
                    {
                        "keyType": "integer" if isinstance(key, int) else "string",
                        "key": key,
                        "value": self.encode(item, depth + 1),
                    }
                )
            return {"type": "mapping", "items": items}
        finally:
            self._active.remove(id(value))

    def _enter(self, value: object) -> None:
        identity = id(value)
        if identity in self._active:
            self._violate("recursive containers are not portable")
        self._active.add(identity)

    def _numpy_entry(self, kind: str, value: np.ndarray[Any, Any]) -> object:
        if value.dtype.hasobject:
            self._violate("NumPy object arrays can execute project serialization")
        contiguous = np.ascontiguousarray(value)
        return self._entry(
            kind,
            "U8",
            (contiguous.nbytes,),
            contiguous.tobytes(order="C"),
            numpyDtype=value.dtype.str,
            numpyShape=list(value.shape),
        )

    def _torch_entry(self, value: Any, torch: Any) -> object:
        if value.layout != torch.strided or value.is_sparse or value.is_quantized:
            self._violate("unsupported PyTorch tensor layout")
        if value.device.type not in {"cpu", "cuda"} or value.device.type == "meta":
            self._violate("unsupported PyTorch tensor device")
        if value.is_conj() or value.is_neg() or getattr(value, "is_nested", False):
            self._violate("unsupported PyTorch tensor view")
        dtype = _TORCH_DTYPES.get(str(value.dtype))
        if dtype is None:
            self._violate(f"unsupported PyTorch dtype {value.dtype}")
        portable = value.detach().cpu().contiguous()
        raw = portable.view(torch.uint8).numpy().tobytes()
        return self._entry("torch-tensor", dtype, tuple(value.shape), raw)

    def _entry(
        self,
        kind: str,
        dtype: str,
        shape: tuple[int, ...],
        data: bytes,
        **fields: object,
    ) -> object:
        if len(self.entries) >= _MAX_LEAVES:
            self._violate("portable value tree exceeds 1,000,000 referenced leaves")
        identifier = f"skywright.leaf.{len(self.entries):07d}"
        self.entries.append((identifier, dtype, shape, data))
        return {"type": kind, "entry": identifier, **fields}

    @staticmethod
    def _violate(problem: str) -> NoReturn:
        raise TrainingContractViolation(
            "checkpoint-state/portable-value",
            problem,
            "use only the protocol-v1 portable Checkpoint State value types",
        )


class CheckpointCodec:
    """Encode safe portable Checkpoint State as one uncompressed Safetensors file."""

    def __init__(self, *, staging_directory: Path | None = None) -> None:
        self._staging_directory = staging_directory

    def serialize(self, checkpoint: CheckpointSnapshot) -> SerializedCheckpoint:
        _step(checkpoint.step)
        tree = _PortableTree()
        manifest = {
            "schemaVersion": 1,
            "runId": checkpoint.run_id,
            "projectVersion": checkpoint.project_version,
            "step": checkpoint.step,
            "datasetCursor": {
                "epoch": checkpoint.dataset_cursor.epoch,
                "itemOffset": checkpoint.dataset_cursor.item_offset,
                "epochStep": checkpoint.dataset_cursor.epoch_step,
                "orderingFingerprint": checkpoint.dataset_cursor.ordering_fingerprint,
            },
            "state": tree.encode(checkpoint.state),
            "runtimeState": tree.encode(checkpoint.runtime_state),
        }
        manifest_json = _canonical_json(manifest)
        if len(manifest_json.encode("utf-8")) > _MAX_MANIFEST:
            raise TrainingContractViolation(
                "checkpoint-state/manifest-size",
                "Safetensors manifest exceeds 16 MiB",
                "reduce the number or size of structural Checkpoint State values",
            )
        header: dict[str, object] = {
            "__metadata__": {
                "skywright.schema": "checkpoint-v1",
                "skywright.manifest": manifest_json,
            }
        }
        offset = 0
        for identifier, dtype, shape, data in tree.entries:
            header[identifier] = {
                "dtype": dtype,
                "shape": list(shape),
                "data_offsets": [offset, offset + len(data)],
            }
            offset += len(data)
        header_bytes = _canonical_json(header).encode("utf-8")
        header_bytes += b" " * ((8 - len(header_bytes) % 8) % 8)
        descriptor, name = tempfile.mkstemp(
            prefix="skywright-checkpoint-",
            suffix=".safetensors",
            dir=self._staging_directory,
        )
        path = Path(name)
        digest = hashlib.sha256()
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                for part in (struct.pack("<Q", len(header_bytes)), header_bytes):
                    stream.write(part)
                    digest.update(part)
                for _, _, _, data in tree.entries:
                    stream.write(data)
                    digest.update(data)
            return SerializedCheckpoint(path, digest.hexdigest(), path.stat().st_size)
        except BaseException:
            path.unlink(missing_ok=True)
            raise

    def deserialize(self, path: Path, *, expected_digest: str) -> CheckpointSnapshot:
        actual_digest = _file_digest(path)
        if actual_digest != _digest(expected_digest):
            raise ValueError(
                "RUN_STORE_DIGEST_MISMATCH: Checkpoint content digest differs"
            )
        with path.open("rb") as stream:
            raw_length = stream.read(8)
            if len(raw_length) != 8:
                raise ValueError(
                    "RUN_STORE_MALFORMED_SAFETENSORS: missing header length"
                )
            header_length = struct.unpack("<Q", raw_length)[0]
            if header_length > _MAX_MANIFEST * 2:
                raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: header is too large")
            header_raw = stream.read(header_length)
            try:
                header = json.loads(header_raw)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ValueError(
                    "RUN_STORE_MALFORMED_SAFETENSORS: invalid header"
                ) from error
            data = stream.read()
        metadata = header.pop("__metadata__", None)
        if (
            not isinstance(metadata, dict)
            or metadata.get("skywright.schema") != "checkpoint-v1"
        ):
            raise ValueError("RUN_STORE_INCOMPATIBLE_SCHEMA: unknown Checkpoint schema")
        try:
            manifest = json.loads(metadata["skywright.manifest"])
        except (KeyError, TypeError, json.JSONDecodeError) as error:
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: invalid manifest"
            ) from error
        entries = _decode_entries(header, data)
        used: set[str] = set()
        state = _decode_tree(manifest["state"], entries, used)
        runtime_state = _decode_tree(manifest["runtimeState"], entries, used)
        if not isinstance(state, Mapping) or not isinstance(runtime_state, Mapping):
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: Checkpoint roots are not mappings"
            )
        if used != set(entries):
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: unreferenced tensor entry"
            )
        cursor = manifest["datasetCursor"]
        return CheckpointSnapshot(
            step=manifest["step"],
            state=cast(Mapping[str, object], state),
            runtime_state=cast(Mapping[str, object], runtime_state),
            dataset_cursor=DatasetCursor(
                cursor["epoch"],
                cursor["itemOffset"],
                cursor["epochStep"],
                cursor["orderingFingerprint"],
            ),
            reference=str(CheckpointReference(manifest["step"], actual_digest)),
            run_id=manifest["runId"],
            project_version=manifest["projectVersion"],
        )


def _torch() -> Any | None:
    try:
        import torch
    except ImportError:
        return None
    return torch


def _canonical_json(value: object) -> str:
    return json.dumps(
        _json_value(value),
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def _json_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {str(name): _json_value(item) for name, item in value.items()}
    if isinstance(value, list | tuple):
        return [_json_value(item) for item in value]
    return value


def _file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _decode_entries(
    header: object, data: bytes
) -> dict[str, tuple[str, tuple[int, ...], bytes]]:
    if not isinstance(header, dict):
        raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: header is not an object")
    result: dict[str, tuple[str, tuple[int, ...], bytes]] = {}
    expected_offset = 0
    for name, descriptor in header.items():
        if not isinstance(name, str) or not isinstance(descriptor, dict):
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: invalid tensor descriptor"
            )
        try:
            start, end = descriptor["data_offsets"]
            dtype = descriptor["dtype"]
            shape = tuple(descriptor["shape"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: invalid tensor descriptor"
            ) from error
        if (
            start != expected_offset
            or not isinstance(end, int)
            or end < start
            or end > len(data)
        ):
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: inconsistent tensor offsets"
            )
        result[name] = (dtype, shape, data[start:end])
        expected_offset = end
    if expected_offset != len(data):
        raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: unaccounted payload bytes")
    return result


def _decode_tree(
    node: object,
    entries: Mapping[str, tuple[str, tuple[int, ...], bytes]],
    used: set[str],
    depth: int = 0,
) -> object:
    if (
        depth > _MAX_DEPTH
        or not isinstance(node, dict)
        or not isinstance(node.get("type"), str)
    ):
        raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: invalid portable value tree")
    kind = node["type"]
    if kind == "null":
        return None
    if kind in {"bool", "integer", "float", "string"}:
        return node["value"]
    if kind in {"list", "tuple"}:
        values = [
            _decode_tree(item, entries, used, depth + 1) for item in node["items"]
        ]
        return tuple(values) if kind == "tuple" else values
    if kind == "mapping":
        result: dict[object, object] = {}
        for item in node["items"]:
            key = item["key"]
            if item["keyType"] not in {"string", "integer"} or key in result:
                raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: invalid mapping key")
            result[key] = _decode_tree(item["value"], entries, used, depth + 1)
        return result
    identifier = node.get("entry")
    if (
        not isinstance(identifier, str)
        or identifier in used
        or identifier not in entries
    ):
        raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: invalid tensor reference")
    used.add(identifier)
    dtype, shape, raw = entries[identifier]
    if kind == "bytes":
        if dtype != "U8" or shape != (len(raw),):
            raise ValueError("RUN_STORE_MALFORMED_SAFETENSORS: inconsistent byte entry")
        return raw
    if kind in {"numpy-array", "numpy-scalar"}:
        value_dtype = np.dtype(node["numpyDtype"])
        expected_shape = tuple(node["numpyShape"])
        expected_size = (
            int(np.prod(expected_shape, dtype=np.int64)) * value_dtype.itemsize
        )
        if dtype != "U8" or len(raw) != expected_size:
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: inconsistent NumPy entry"
            )
        value = np.frombuffer(raw, dtype=value_dtype).copy().reshape(expected_shape)
        return value[()] if kind == "numpy-scalar" else value
    if kind == "torch-tensor":
        torch = _torch()
        if torch is None:
            raise ValueError("RUN_STORE_INCOMPATIBLE_VALUE: PyTorch is unavailable")
        if dtype == "BF16":
            value = (
                torch.frombuffer(bytearray(raw), dtype=torch.uint8)
                .clone()
                .view(torch.bfloat16)
            )
        else:
            numpy_dtype = _NUMPY_FOR_TORCH.get(dtype)
            if numpy_dtype is None:
                raise ValueError(
                    "RUN_STORE_INCOMPATIBLE_VALUE: unsupported tensor dtype"
                )
            value = torch.from_numpy(np.frombuffer(raw, dtype=numpy_dtype).copy())
        expected = int(np.prod(shape, dtype=np.int64))
        if value.numel() != expected:
            raise ValueError(
                "RUN_STORE_MALFORMED_SAFETENSORS: inconsistent tensor shape"
            )
        return value.reshape(shape)
    raise ValueError(
        "RUN_STORE_INCOMPATIBLE_VALUE: unknown required portable value type"
    )


@dataclass(frozen=True)
class TargetStorage:
    """Injected resolved address and credential-provider settings for one Run Store."""

    storage_id: str
    endpoint_url: str
    bucket: str
    region: str
    training_project_id: str
    run_id: str
    addressing_style: str = "path"
    profile_name: str | None = None
    compatibility_options: Mapping[str, str] = field(default_factory=dict, hash=False)

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            "compatibility_options",
            MappingProxyType(dict(self.compatibility_options)),
        )


class RunStoreError(RuntimeError):
    """Base for typed Run Store failures retained below the process boundary."""


class RunStoreIntegrityError(RunStoreError, ValueError):
    """Persisted bytes or protocol metadata failed integrity validation."""


class RunStoreConflictError(RunStoreError):
    """An immutable semantic identity already has different content."""


class RunStoreDeadlineError(RunStoreError):
    """The caller-owned operation deadline expired."""


class RunStoreCancelledError(RunStoreError, CheckpointPublicationCancelled):
    """The caller cancelled an in-flight Run Store operation."""


@dataclass(frozen=True)
class OperationControl:
    """Caller-owned cancellation and absolute monotonic deadline."""

    deadline: float | None = None
    cancellation_requested: Callable[[], bool] = lambda: False


@dataclass(frozen=True)
class OperationMeasurement:
    """One non-secret provider request measurement retained for cost accounting."""

    operation: str
    bytes: int
    direction: str
    request_number: int
    run_id: str
    timestamp: float
    provenance: str
    succeeded: bool


class _S3Gateway:
    _RETRYABLE = frozenset(
        {
            "get_object",
            "list_objects_v2",
            "list_multipart_uploads",
            "list_parts",
            "upload_part",
            "delete_object",
            "abort_multipart_upload",
        }
    )

    def __init__(
        self, client: Any, target: TargetStorage, control: OperationControl
    ) -> None:
        self._client = client
        self._target = target
        self._control = control
        self.measurements: list[OperationMeasurement] = []

    def __getattr__(self, operation: str) -> Any:
        provider_operation = getattr(self._client, operation)

        def invoke(**request: object) -> Any:
            attempt = 0
            while True:
                self._check_control()
                attempt += 1
                started = time.time()
                transferred = _request_bytes(request)
                direction = (
                    "write"
                    if operation
                    in {
                        "put_object",
                        "upload_part",
                        "complete_multipart_upload",
                    }
                    else "read"
                    if operation == "get_object"
                    else "control"
                )
                try:
                    response = provider_operation(**request)
                    if operation == "get_object":
                        transferred = int(response.get("ContentLength", 0))
                    self._measure(
                        operation, transferred, direction, attempt, started, True
                    )
                    return response
                except Exception as failure:
                    self._measure(
                        operation, transferred, direction, attempt, started, False
                    )
                    conditional_put = operation == "put_object" and (
                        request.get("IfNoneMatch") == "*" or "IfMatch" in request
                    )
                    if not _is_transient_failure(failure) or not (
                        operation in self._RETRYABLE or conditional_put
                    ):
                        raise
                    if attempt >= 8:
                        raise
                    delay = min(0.05 * (2 ** (attempt - 1)), 1.0) * random.uniform(
                        0.5, 1.5
                    )
                    if (
                        self._control.deadline is not None
                        and time.monotonic() + delay >= self._control.deadline
                    ):
                        raise RunStoreDeadlineError(
                            "Run Store retry would exceed the caller-owned deadline"
                        ) from failure
                    time.sleep(delay)

        return invoke

    def _check_control(self) -> None:
        if self._control.cancellation_requested():
            raise RunStoreCancelledError(
                "Run Store operation was cancelled by its caller"
            )
        if (
            self._control.deadline is not None
            and time.monotonic() >= self._control.deadline
        ):
            raise RunStoreDeadlineError("Run Store operation deadline expired")

    def _measure(
        self,
        operation: str,
        transferred: int,
        direction: str,
        attempt: int,
        timestamp: float,
        succeeded: bool,
    ) -> None:
        self.measurements.append(
            OperationMeasurement(
                operation,
                transferred,
                direction,
                attempt,
                self._target.run_id,
                timestamp,
                self._target.storage_id,
                succeeded,
            )
        )


class RunStoreRecorder:
    """S3-backed production implementation of ``TrainingProcessRecorder``."""

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
    ) -> None:
        self.target = target
        self.protocol: RunStoreProtocol = RunStoreProtocol(
            target.training_project_id, target.run_id
        )
        self._progress = progress_recorder
        self._codec = checkpoint_codec or CheckpointCodec()
        control = operation_control or OperationControl()
        self._checkpoint_cancellation = threading.Event()

        def cancellation_requested() -> bool:
            return (
                control.cancellation_requested()
                or self._checkpoint_cancellation.is_set()
            )

        controlled_publication = OperationControl(
            control.deadline, cancellation_requested
        )
        self._client = _S3Gateway(
            client or _s3_client(target, session_factory, controlled_publication),
            target,
            controlled_publication,
        )
        self._attempt: ExecutionAttemptRecord | None = None
        self._confirmation_lock = threading.Lock()
        self._latest_confirmation: tuple[int, str] | None = None
        self._closed = False
        self._multipart_threshold = multipart_threshold
        self._multipart_part_size = multipart_part_size

    @property
    def measurements(self) -> tuple[OperationMeasurement, ...]:
        return tuple(self._client.measurements)

    def publish_attempt(self, attempt: ExecutionAttemptRecord) -> None:
        if attempt.run_id != self.target.run_id:
            raise TrainingContractViolation(
                "execution-attempt/run-identity",
                "Execution Attempt Run does not match the resolved Run Store",
                "publish the attempt to its owning Run Store",
            )
        body = _canonical_json(
            {
                "schemaVersion": 1,
                "attemptId": attempt.attempt_id,
                "runId": attempt.run_id,
                "projectVersion": attempt.project_version,
                "seedCheckpointStep": attempt.seed_checkpoint_step,
                "seedCheckpointReference": attempt.seed_checkpoint_reference,
                "rejectedCorruptCheckpoints": [
                    {
                        "step": item.step,
                        "reference": item.reference,
                        "code": item.code,
                        "summary": item.summary,
                    }
                    for item in attempt.rejected_corrupt_checkpoints
                ],
            }
        ).encode()
        self._put_immutable(
            self.protocol.attempt_record_key(attempt.attempt_id),
            body,
            kind="execution-attempt-record",
            content_type="application/json",
        )
        self._attempt = attempt
        if (
            attempt.seed_checkpoint_step is not None
            and attempt.seed_checkpoint_reference is not None
        ):
            with self._confirmation_lock:
                self._latest_confirmation = (
                    attempt.seed_checkpoint_step,
                    attempt.seed_checkpoint_reference,
                )

    def publish_checkpoint(self, checkpoint: CheckpointSnapshot) -> str:
        self._require_open()
        if checkpoint.run_id and checkpoint.run_id != self.target.run_id:
            raise TrainingContractViolation(
                "checkpoint/run-identity",
                "Checkpoint Run does not match the resolved Run Store",
                "publish the Checkpoint to its owning Run Store",
            )
        prefix = f"{self.protocol.run_prefix}checkpoints/{_step(checkpoint.step)}/"
        existing = self._list_keys(prefix)
        with self._codec.serialize(checkpoint) as staged:
            key = self.protocol.checkpoint_key(checkpoint.step, staged.digest)
            different = [candidate for candidate in existing if candidate != key]
            if different:
                raise TrainingContractViolation(
                    "checkpoint/step-identity",
                    f"Step {checkpoint.step} already identifies a different Checkpoint",
                    "publish identical state when retrying a committed Step",
                )
            if staged.size >= self._multipart_threshold:
                self._put_multipart_checkpoint(key, staged)
            else:
                with staged.path.open("rb") as stream:
                    self._put_immutable(
                        key,
                        stream,
                        kind="checkpoint",
                        content_type="application/octet-stream",
                        digest=staged.digest,
                        size=staged.size,
                    )
            after = self._list_keys(prefix)
            if any(candidate != key for candidate in after):
                raise TrainingContractViolation(
                    "checkpoint/step-identity",
                    f"Step {checkpoint.step} concurrently acquired conflicting Checkpoints",
                    "retain one immutable Checkpoint identity per committed Step",
                )
            return str(CheckpointReference(checkpoint.step, staged.digest))

    def publish_step(
        self,
        step: int,
        dataset_cursor: DatasetCursor,
        observations: tuple[MetricObservation, ...],
        latest_durable_step: int | None,
        latest_durable_checkpoint: str | None,
    ) -> None:
        self._require_open()
        if self._progress is not None:
            self._progress.publish_step(
                step,
                dataset_cursor,
                observations,
                latest_durable_step,
                latest_durable_checkpoint,
            )

    def confirm_checkpoint(self, step: int, reference: str) -> None:
        self._require_open()
        if self._checkpoint_cancellation.is_set():
            raise RunStoreCancelledError(
                "Checkpoint confirmation was cancelled by its coordinator"
            )
        parsed = CheckpointReference.parse(reference)
        if parsed.step != step:
            raise TrainingContractViolation(
                "checkpoint/confirmation-step",
                "Checkpoint confirmation Step does not match its reference",
                "confirm the exact reference returned by checkpoint publication",
            )
        with self._confirmation_lock:
            current = self._latest_confirmation
            if current is not None:
                current_step, current_reference = current
                if step < current_step:
                    return
                if step == current_step:
                    if reference != current_reference:
                        raise TrainingContractViolation(
                            "checkpoint/confirmation-conflict",
                            f"Step {step} already has a different confirmed Checkpoint",
                            "confirm only the immutable Checkpoint already recorded for this Step",
                        )
                    return
            if self._progress is not None:
                self._progress.confirm_checkpoint(step, reference)
            self._latest_confirmation = (step, reference)

    def cancel_checkpoint_publication(self) -> None:
        """Request cancellation of the current attempt-owned checkpoint operation."""
        self._checkpoint_cancellation.set()

    def resume_after_checkpoint_cancellation(self) -> None:
        """Allow report publication after cancelled checkpoint work has stopped."""
        self._checkpoint_cancellation.clear()

    def publish_artifact(self, artifact: ArtifactRecord) -> None:
        attempt = self._require_open()
        self._put_immutable(
            self.protocol.artifact_key(
                attempt.attempt_id, artifact.step, artifact.name
            ),
            artifact.data,
            kind="artifact",
            content_type="application/octet-stream",
            conflict_is_contract=True,
        )

    def publish_sample(self, sample: SampleRecord) -> None:
        attempt = self._require_open()
        self._put_immutable(
            self.protocol.sample_key(attempt.attempt_id, sample.step, sample.name),
            sample.data,
            kind="sample",
            content_type=sample.media_type,
            conflict_is_contract=True,
        )

    def publish_report(self, report: ExecutionTerminationReport) -> None:
        attempt = self._attempt
        if attempt is None or report.attempt_id != attempt.attempt_id:
            raise TrainingContractViolation(
                "execution-attempt/identity",
                "Termination Report does not identify the active Execution Attempt",
                "close the same Execution Attempt that this recorder opened",
            )
        body = _canonical_json(
            {
                "schemaVersion": report.schema_version,
                "attemptId": report.attempt_id,
                "runId": report.run_id,
                "projectVersion": report.project_version,
                "cause": report.cause.value,
                "lastCommittedStep": report.last_committed_step,
                "latestDurableStep": report.latest_durable_step,
                "latestDurableCheckpoint": report.latest_durable_checkpoint,
                "diagnostics": report.diagnostics,
            }
        ).encode()
        self._put_immutable(
            self.protocol.attempt_report_key(report.attempt_id),
            body,
            kind="execution-termination-report",
            content_type="application/json",
        )
        self._closed = True

    def _require_open(self) -> ExecutionAttemptRecord:
        if self._attempt is None:
            raise TrainingContractViolation(
                "execution-attempt/missing",
                "process-owned output was published before an Execution Attempt Record",
                "publish durable attempt identity before project code runs",
            )
        if self._closed:
            raise TrainingContractViolation(
                "execution-attempt/closed",
                "process-owned output was published after its Termination Report",
                "treat the Termination Report as the final process-owned write",
            )
        return self._attempt

    def _put_immutable(
        self,
        key: str,
        body: bytes | BinaryIO,
        *,
        kind: str,
        content_type: str,
        digest: str | None = None,
        size: int | None = None,
        conflict_is_contract: bool = False,
    ) -> None:
        payload = body if isinstance(body, bytes) else None
        if digest is None or size is None:
            if payload is None:
                stream = cast(BinaryIO, body)
                payload = stream.read()
                stream.seek(0)
            actual_digest = hashlib.sha256(payload).hexdigest()
            actual_size = len(payload)
        else:
            actual_digest = digest
            actual_size = size
        metadata = {
            "skywright-sha256": actual_digest,
            "skywright-kind": kind,
            "skywright-schema": "v1",
            "skywright-size": str(actual_size),
            "skywright-media-type": content_type,
        }
        try:
            self._client.put_object(
                Bucket=self.target.bucket,
                Key=key,
                Body=body,
                ContentLength=actual_size,
                ContentType=content_type,
                Metadata=metadata,
                IfNoneMatch="*",
            )
            return
        except Exception as failure:
            if not _is_precondition_failure(failure):
                raise
        existing = self._read_verified(key)
        if payload is None:
            stream = cast(BinaryIO, body)
            payload = stream.read()
            stream.seek(0)
        response = self._client.get_object(Bucket=self.target.bucket, Key=key)
        same = existing == payload and all(
            response.get("Metadata", {}).get(name) == value
            for name, value in metadata.items()
        )
        if same:
            return
        if conflict_is_contract:
            raise TrainingContractViolation(
                "run-output/immutable-identity",
                "the same Execution Attempt, Step, and output name has different bytes or metadata",
                "retry immutable output publication with identical content",
            )
        raise RunStoreConflictError(f"immutable Run Store identity conflicts at {key}")

    def _put_multipart_checkpoint(self, key: str, staged: SerializedCheckpoint) -> None:
        metadata = {
            "skywright-sha256": staged.digest,
            "skywright-kind": "checkpoint",
            "skywright-schema": "v1",
            "skywright-size": str(staged.size),
            "skywright-media-type": "application/octet-stream",
        }
        created = self._client.create_multipart_upload(
            Bucket=self.target.bucket,
            Key=key,
            ContentType="application/octet-stream",
            Metadata=metadata,
        )
        upload_id = created["UploadId"]
        completed = False
        try:
            part_size = max(
                self._multipart_part_size,
                (staged.size + 9_998) // 9_999,
            )
            parts: list[dict[str, object]] = []
            with staged.path.open("rb") as stream:
                part_number = 1
                while chunk := stream.read(part_size):
                    response = self._client.upload_part(
                        Bucket=self.target.bucket,
                        Key=key,
                        UploadId=upload_id,
                        PartNumber=part_number,
                        Body=chunk,
                        ContentLength=len(chunk),
                    )
                    parts.append({"ETag": response["ETag"], "PartNumber": part_number})
                    part_number += 1
            self._client.complete_multipart_upload(
                Bucket=self.target.bucket,
                Key=key,
                UploadId=upload_id,
                MultipartUpload={"Parts": parts},
                IfNoneMatch="*",
            )
            completed = True
        except Exception as failure:
            if _is_precondition_failure(failure) or _is_transient_failure(failure):
                try:
                    existing = self._read_verified(key)
                except Exception:
                    existing = None
                if existing is not None and (
                    len(existing) == staged.size
                    and hashlib.sha256(existing).hexdigest() == staged.digest
                ):
                    return
            raise
        finally:
            if not completed:
                with suppress(Exception):
                    self._client.abort_multipart_upload(
                        Bucket=self.target.bucket, Key=key, UploadId=upload_id
                    )

    def _read_verified(self, key: str) -> bytes:
        response = self._client.get_object(Bucket=self.target.bucket, Key=key)
        body = response["Body"].read()
        metadata = response.get("Metadata", {})
        if (
            metadata.get("skywright-size") != str(len(body))
            or metadata.get("skywright-sha256") != hashlib.sha256(body).hexdigest()
        ):
            raise RunStoreIntegrityError(f"RUN_STORE_DIGEST_MISMATCH at {key}")
        return body

    def _list_keys(self, prefix: str) -> list[str]:
        result: list[str] = []
        continuation: str | None = None
        while True:
            request: dict[str, object] = {
                "Bucket": self.target.bucket,
                "Prefix": prefix,
            }
            if continuation is not None:
                request["ContinuationToken"] = continuation
            response = self._client.list_objects_v2(**request)
            result.extend(item["Key"] for item in response.get("Contents", ()))
            if not response.get("IsTruncated"):
                return result
            continuation = response["NextContinuationToken"]


def _s3_client(
    target: TargetStorage,
    session_factory: Any | None,
    control: OperationControl,
) -> Any:
    import boto3
    from botocore.config import Config

    factory = session_factory or boto3.Session
    session = factory(profile_name=target.profile_name, region_name=target.region)
    remaining = min(
        max(control.deadline - time.monotonic(), 0.001)
        if control.deadline is not None
        else 30.0,
        30.0,
    )
    checksum_calculation = target.compatibility_options.get("checksumCalculation")
    config_options: dict[str, Any] = {
        "s3": {"addressing_style": target.addressing_style},
        "connect_timeout": remaining,
        "read_timeout": remaining,
        "retries": {"mode": "standard", "max_attempts": 8},
    }
    if checksum_calculation is not None:
        config_options["request_checksum_calculation"] = checksum_calculation.replace(
            "-", "_"
        )
    return session.client(
        "s3",
        endpoint_url=target.endpoint_url,
        config=Config(**config_options),
    )


def _is_precondition_failure(failure: Exception) -> bool:
    response = getattr(failure, "response", {})
    code = response.get("Error", {}).get("Code")
    status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return code in {
        "PreconditionFailed",
        "ConditionalRequestConflict",
        "412",
        "409",
    } or status in {
        409,
        412,
    }


def _is_transient_failure(failure: Exception) -> bool:
    response = getattr(failure, "response", {})
    code = response.get("Error", {}).get("Code")
    status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return (
        isinstance(failure, (OSError, TimeoutError))
        or code
        in {
            "SlowDown",
            "Throttling",
            "RequestTimeout",
            "InternalError",
            "ServiceUnavailable",
        }
        or status in {429, 500, 502, 503, 504}
    )


def _is_missing_upload(failure: Exception) -> bool:
    response = getattr(failure, "response", {})
    code = response.get("Error", {}).get("Code")
    status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return code in {"NoSuchUpload", "404"} or status == 404


def _request_bytes(request: Mapping[str, object]) -> int:
    length = request.get("ContentLength")
    if isinstance(length, int):
        return length
    body = request.get("Body")
    return len(body) if isinstance(body, bytes) else 0


@dataclass(frozen=True, order=True)
class CheckpointSummary:
    """Immutable Checkpoint identity discoverable without decoding its State."""

    step: int
    reference: str
    key: str
    size: int


@dataclass(frozen=True)
class CheckpointResolution:
    """Selected Checkpoint plus every newer corrupt candidate rejected first."""

    checkpoint: CheckpointSnapshot
    rejected: tuple[CheckpointRejectionEvidence, ...]


@dataclass(frozen=True)
class MultipartUpload:
    """Incomplete provider upload discoverable beneath the stable Run prefix."""

    key: str
    upload_id: str
    part_numbers: tuple[int, ...]


class RunStoreReader:
    """Checkpoint discovery, exact reads, corruption fallback, and retention."""

    def __init__(
        self,
        target: TargetStorage,
        *,
        checkpoint_codec: CheckpointCodec | None = None,
        client: Any | None = None,
        session_factory: Any | None = None,
        operation_control: OperationControl | None = None,
    ) -> None:
        self.target = target
        self.protocol: RunStoreProtocol = RunStoreProtocol(
            target.training_project_id, target.run_id
        )
        self._codec = checkpoint_codec or CheckpointCodec()
        control = operation_control or OperationControl()
        self._client = _S3Gateway(
            client or _s3_client(target, session_factory, control),
            target,
            control,
        )

    @property
    def measurements(self) -> tuple[OperationMeasurement, ...]:
        return tuple(self._client.measurements)

    def list_checkpoints(self) -> tuple[CheckpointSummary, ...]:
        prefix = f"{self.protocol.run_prefix}checkpoints/"
        summaries: list[CheckpointSummary] = []
        for item in self._list(prefix):
            suffix = item["Key"][len(prefix) :]
            match = re.fullmatch(r"([0-9]{19})/([0-9a-f]{64})\.safetensors", suffix)
            if match is None:
                raise RunStoreIntegrityError(
                    f"RUN_STORE_INVALID_KEY: malformed Checkpoint key {item['Key']}"
                )
            step = int(match.group(1))
            reference = str(CheckpointReference(step, match.group(2)))
            summaries.append(
                CheckpointSummary(step, reference, item["Key"], item["Size"])
            )
        summaries.sort(key=lambda item: (item.step, item.reference))
        seen: set[int] = set()
        for item in summaries:
            if item.step in seen:
                raise RunStoreIntegrityError(
                    f"RUN_STORE_CONFLICTING_CHECKPOINTS: Step {item.step} has multiple objects"
                )
            seen.add(item.step)
        return tuple(summaries)

    def read_progress(self) -> ProgressRecord:
        """Read and validate the Run's current Progress Record."""
        from skywright._run_store.progress import ProgressRecord

        body, response = self._read_verified_object(self.protocol.progress_key())
        metadata = response.get("Metadata", {})
        if (
            metadata.get("skywright-kind") != "progress-record"
            or metadata.get("skywright-schema") != "v1"
        ):
            raise RunStoreIntegrityError(
                "RUN_STORE_METADATA_MISMATCH: expected Progress Record schema v1"
            )
        progress = ProgressRecord.decode(body)
        if progress.run_id != self.target.run_id:
            raise RunStoreIntegrityError(
                "RUN_STORE_WRONG_RUN: Progress Record belongs to another Run"
            )
        return progress

    def read_exact(
        self,
        reference: str,
        *,
        project_version: str | None = None,
        ordering_fingerprint: str | None = None,
    ) -> CheckpointSnapshot:
        parsed = CheckpointReference.parse(reference)
        key = self.protocol.checkpoint_key(parsed.step, parsed.digest)
        body = self._read_verified(key, parsed.digest)
        descriptor, name = tempfile.mkstemp(
            prefix="skywright-read-", suffix=".safetensors"
        )
        path = Path(name)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(body)
            checkpoint = self._codec.deserialize(path, expected_digest=parsed.digest)
        finally:
            path.unlink(missing_ok=True)
        if checkpoint.run_id != self.target.run_id:
            raise RunStoreError(
                "RUN_STORE_WRONG_RUN: Checkpoint belongs to another Run"
            )
        if (
            project_version is not None
            and checkpoint.project_version != project_version
        ):
            raise RunStoreError(
                "RUN_STORE_INCOMPATIBLE_PROJECT_VERSION: Checkpoint Training Project Version differs"
            )
        if (
            ordering_fingerprint is not None
            and checkpoint.dataset_cursor.ordering_fingerprint != ordering_fingerprint
        ):
            raise RunStoreError(
                "RUN_STORE_INCOMPATIBLE_ORDERING: Dataset ordering fingerprint differs"
            )
        return checkpoint

    def resolve_latest_valid(
        self,
        *,
        project_version: str,
        ordering_fingerprint: str,
    ) -> CheckpointResolution:
        rejected: list[CheckpointRejectionEvidence] = []
        for candidate in reversed(self.list_checkpoints()):
            try:
                checkpoint = self.read_exact(
                    candidate.reference,
                    project_version=project_version,
                    ordering_fingerprint=ordering_fingerprint,
                )
            except (
                RunStoreIntegrityError,
                ValueError,
                KeyError,
                TypeError,
                OverflowError,
                IndexError,
            ) as failure:
                code = _integrity_code(failure)
                if code is None:
                    code = "RUN_STORE_MALFORMED_SAFETENSORS"
                rejected.append(
                    CheckpointRejectionEvidence(
                        candidate.step,
                        candidate.reference,
                        code,
                        "Checkpoint failed content or container integrity validation",
                    )
                )
                continue
            return CheckpointResolution(checkpoint, tuple(rejected))
        raise RunStoreIntegrityError(
            "RUN_STORE_NO_VALID_CHECKPOINT: every discovered Checkpoint is corrupt or missing"
        )

    def prune_checkpoints(
        self,
        *,
        retention: int,
        keep_every_nth: int | None = None,
        final_reference: str | None = None,
    ) -> None:
        if retention < 1 or (keep_every_nth is not None and keep_every_nth < 1):
            raise ValueError("retention and keep_every_nth must be positive")
        checkpoints = list(self.list_checkpoints())
        protected = {item.reference for item in checkpoints[-retention:]}
        if keep_every_nth is not None:
            protected.update(
                item.reference
                for item in checkpoints
                if item.step % keep_every_nth == 0
            )
        if final_reference is not None:
            protected.add(str(CheckpointReference.parse(final_reference)))
        verified: set[str] = set()
        for candidate in checkpoints:
            if candidate.reference in protected:
                continue
            newer = [
                item
                for item in checkpoints
                if item.step > candidate.step and item.reference in protected
            ]
            if not newer:
                continue
            recovery = newer[-1]
            if recovery.reference not in verified:
                self.read_exact(recovery.reference)
                verified.add(recovery.reference)
            self._client.delete_object(Bucket=self.target.bucket, Key=candidate.key)

    def presign_download(self, key: str, *, expires_in: int = 900) -> str:
        immutable_prefixes = (
            f"{self.protocol.run_prefix}checkpoints/",
            f"{self.protocol.run_prefix}artifacts/",
            f"{self.protocol.run_prefix}samples/",
        )
        if not key.startswith(immutable_prefixes) or not 1 <= expires_in <= 3600:
            raise ValueError(
                "only exact immutable outputs can be presigned for 1..3600 seconds"
            )
        _, response = self._read_verified_object(key)
        media_type = response.get("Metadata", {}).get(
            "skywright-media-type",
            response.get("ContentType", "application/octet-stream"),
        )
        encoded_component = key.rsplit("/", 1)[-1]
        filename = unquote(encoded_component).rsplit("/", 1)[-1]
        if not filename or any(ord(character) < 32 for character in filename):
            filename = "skywright-output"
        return self._client.generate_presigned_url(
            ClientMethod="get_object",
            Params={
                "Bucket": self.target.bucket,
                "Key": key,
                "ResponseContentType": media_type,
                "ResponseContentDisposition": (
                    "attachment; filename*=UTF-8''" + quote(filename, safe="-._~")
                ),
            },
            ExpiresIn=expires_in,
        )

    def list_incomplete_uploads(self) -> tuple[MultipartUpload, ...]:
        result: list[MultipartUpload] = []
        key_marker: str | None = None
        upload_marker: str | None = None
        stable_prefix = self.protocol.run_prefix.rsplit("v1/", 1)[0]
        while True:
            request: dict[str, object] = {
                "Bucket": self.target.bucket,
                "Prefix": stable_prefix,
            }
            if key_marker is not None:
                request["KeyMarker"] = key_marker
            if upload_marker is not None:
                request["UploadIdMarker"] = upload_marker
            response = self._client.list_multipart_uploads(**request)
            for upload in response.get("Uploads", ()):
                part_numbers: list[int] = []
                part_marker: int | None = None
                while True:
                    part_request: dict[str, object] = {
                        "Bucket": self.target.bucket,
                        "Key": upload["Key"],
                        "UploadId": upload["UploadId"],
                    }
                    if part_marker is not None:
                        part_request["PartNumberMarker"] = part_marker
                    parts = self._client.list_parts(**part_request)
                    part_numbers.extend(
                        part["PartNumber"] for part in parts.get("Parts", ())
                    )
                    if not parts.get("IsTruncated"):
                        break
                    part_marker = parts["NextPartNumberMarker"]
                result.append(
                    MultipartUpload(
                        upload["Key"],
                        upload["UploadId"],
                        tuple(part_numbers),
                    )
                )
            if not response.get("IsTruncated"):
                return tuple(result)
            key_marker = response["NextKeyMarker"]
            upload_marker = response["NextUploadIdMarker"]

    def abort_incomplete_upload(self, upload: MultipartUpload) -> None:
        stable_prefix = self.protocol.run_prefix.rsplit("v1/", 1)[0]
        if not upload.key.startswith(stable_prefix):
            raise ValueError("multipart upload is outside this Run Store")
        try:
            self._client.abort_multipart_upload(
                Bucket=self.target.bucket,
                Key=upload.key,
                UploadId=upload.upload_id,
            )
        except Exception as failure:
            if not _is_missing_upload(failure):
                raise

    def _read_verified(self, key: str, digest: str | None = None) -> bytes:
        return self._read_verified_object(key, digest)[0]

    def _read_verified_object(
        self, key: str, digest: str | None = None
    ) -> tuple[bytes, Mapping[str, Any]]:
        try:
            response = self._client.get_object(Bucket=self.target.bucket, Key=key)
        except KeyError as failure:
            raise RunStoreIntegrityError(
                f"RUN_STORE_MISSING_OBJECT: {key}"
            ) from failure
        body = response["Body"].read()
        metadata = response.get("Metadata", {})
        actual = hashlib.sha256(body).hexdigest()
        if (
            response.get("ContentLength") != len(body)
            or metadata.get("skywright-size") != str(len(body))
            or metadata.get("skywright-sha256") != actual
            or (digest is not None and digest != actual)
        ):
            raise RunStoreIntegrityError(f"RUN_STORE_DIGEST_MISMATCH: {key}")
        return body, response

    def _list(self, prefix: str) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        continuation: str | None = None
        while True:
            request: dict[str, object] = {
                "Bucket": self.target.bucket,
                "Prefix": prefix,
            }
            if continuation is not None:
                request["ContinuationToken"] = continuation
            response = self._client.list_objects_v2(**request)
            result.extend(response.get("Contents", ()))
            if not response.get("IsTruncated"):
                return result
            continuation = response["NextContinuationToken"]


def _integrity_code(failure: Exception) -> str | None:
    message = str(failure)
    for code in (
        "RUN_STORE_DIGEST_MISMATCH",
        "RUN_STORE_MALFORMED_SAFETENSORS",
        "RUN_STORE_MISSING_OBJECT",
    ):
        if code in message:
            return code
    return None
