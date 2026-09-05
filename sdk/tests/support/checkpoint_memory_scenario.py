"""Measure real model/optimizer capture while an S3 publication is held."""

# The optional PyTorch and boto3 integrations expose runtime-shaped values.
# pyright: reportMissingParameterType=false, reportMissingTypeStubs=false
# pyright: reportMissingImports=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import resource
import threading
import time
import uuid
from collections.abc import Mapping
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import boto3
import torch
from botocore.config import Config

from skywright import DatasetCursor, ExecutionAttemptRecord
from skywright._training_checkpoint_coordinator import CheckpointCoordinator
from skywright._training_checkpoints import capture_checkpoint, restore_checkpoint
from skywright.run_store import (
    CheckpointCodec,
    RunStoreReader,
    RunStoreRecorder,
    TargetStorage,
)

MIB = 1024 * 1024


def tensor_bytes(value) -> int:
    if isinstance(value, torch.Tensor):
        return value.numel() * value.element_size()
    if isinstance(value, Mapping):
        return sum(tensor_bytes(item) for item in value.values())
    if isinstance(value, (list, tuple)):
        return sum(tensor_bytes(item) for item in value)
    return 0


def fingerprint(states) -> str:
    digest = hashlib.sha256()

    def visit(value) -> None:
        if isinstance(value, torch.Tensor):
            digest.update(str((value.dtype, tuple(value.shape))).encode())
            digest.update(
                value.detach()
                .cpu()
                .contiguous()
                .reshape(-1)
                .view(torch.uint8)
                .numpy()
                .tobytes()
            )
        elif isinstance(value, Mapping):
            for key, item in value.items():
                digest.update(str(key).encode())
                visit(item)
        elif isinstance(value, (list, tuple)):
            for item in value:
                visit(item)
        else:
            digest.update(repr(value).encode())

    for name, state in states.items():
        digest.update(name.encode())
        visit(state.state_dict())
    return digest.hexdigest()


def rss() -> int:
    return int(Path("/proc/self/statm").read_text().split()[1]) * os.sysconf(
        "SC_PAGE_SIZE"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--device", choices=("cpu", "rocm"), default="cpu")
    parser.add_argument("--width", type=int, default=2048)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--expect-bounded", action="store_true")
    parser.add_argument("--optimizer", choices=("adamw", "sgd"), default="adamw")
    args = parser.parse_args()
    if args.device == "rocm":
        assert torch.version.hip and torch.cuda.is_available(), (
            "ROCm device is required"
        )
    device = "cuda:0" if args.device == "rocm" else "cpu"
    torch.set_num_threads(4)
    torch.manual_seed(17)

    def model():
        return torch.nn.Sequential(
            *[
                layer
                for _ in range(args.layers)
                for layer in (torch.nn.Linear(args.width, args.width), torch.nn.ReLU())
            ]
        )

    project_model = model().to(device)

    def make_optimizer(parameters):
        if args.optimizer == "sgd":
            return torch.optim.SGD(parameters, lr=0.001, momentum=0.9)
        return torch.optim.AdamW(parameters, lr=0.001)

    optimizer = make_optimizer(project_model.parameters())
    states: dict[str, Any] = {"model": project_model, "optimizer": optimizer}

    def step():
        optimizer.zero_grad(set_to_none=True)
        project_model(
            torch.ones(16, args.width, device=device)
        ).square().mean().backward()
        optimizer.step()
        optimizer.zero_grad(set_to_none=True)
        if args.device == "rocm":
            torch.cuda.synchronize()

    step()
    payload_bytes = sum(tensor_bytes(state.state_dict()) for state in states.values())
    # Warm library imports and RNG capture before measuring checkpoint overhead.
    capture_checkpoint(0, {}, DatasetCursor(), "warmup", "model-v1")
    if args.device == "rocm":
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
    baseline_device = torch.cuda.memory_allocated() if args.device == "rocm" else 0
    baseline_host = rss()
    samples = [baseline_host]
    stop_sampling = threading.Event()

    def sample():
        while not stop_sampling.wait(0.005):
            samples.append(rss())

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    entered = threading.Event()
    release = threading.Event()
    client = boto3.client(
        "s3",
        endpoint_url=args.endpoint,
        region_name="us-east-1",
        aws_access_key_id="test-access-key",
        aws_secret_access_key="test-secret-key",
        config=Config(s3={"addressing_style": "path"}),
    )

    class HeldUpload:
        def __getattr__(self, name):
            return getattr(client, name)

        def upload_part(self, **request):
            entered.set()
            assert release.wait(60), "publication was not released"
            return client.upload_part(**request)

    run_id = str(uuid.uuid4())
    target = TargetStorage(
        "memory-fixture",
        args.endpoint,
        args.bucket,
        "us-east-1",
        "checkpoint-memory",
        run_id,
    )
    evidence: dict[str, Any] = {
        "device": args.device,
        "optimizer": args.optimizer,
        "torch": torch.__version__,
        "hip": torch.version.hip,
        "device_name": torch.cuda.get_device_name(0)
        if args.device == "rocm"
        else "CPU",
        "parameters": sum(p.numel() for p in project_model.parameters()),
        "payload_bytes": payload_bytes,
        "baseline_host_rss": baseline_host,
        "baseline_device_allocated": baseline_device,
    }
    with TemporaryDirectory(prefix="checkpoint-memory-") as temporary:
        store = RunStoreRecorder(
            target,
            client=HeldUpload(),
            checkpoint_codec=CheckpointCodec(staging_directory=Path(temporary)),
            multipart_threshold=1,
            multipart_part_size=8 * MIB,
        )
        store.publish_attempt(
            ExecutionAttemptRecord(str(uuid.uuid4()), run_id, "model-v1", None)
        )
        coordinator = CheckpointCoordinator(store, None, 10)
        captures = []

        def capture(number):
            started = time.monotonic()
            snapshot = capture_checkpoint(
                number, states, DatasetCursor(), run_id, "model-v1"
            )
            if args.device == "rocm":
                torch.cuda.synchronize()
            captures.append((time.monotonic() - started) * 1000)
            return snapshot

        try:
            coordinator.schedule(capture(1))
            deadline = time.monotonic() + 30
            while not entered.wait(0.01):
                coordinator.raise_if_failed()
                assert time.monotonic() < deadline, (
                    "actual publisher did not reach multipart upload"
                )
            first_upload_held_at = time.monotonic()
            step()
            coordinator.schedule(capture(2))
            step()
            expected = fingerprint(states)
            coordinator.schedule(capture(3))
            assert coordinator.durable_state() == (None, None)
            assert not release.is_set()
            evidence["capture_ms"] = captures
            evidence["upload_held_during_training_and_capture_ms"] = (
                time.monotonic() - first_upload_held_at
            ) * 1000
            release.set()
            deadline = time.monotonic() + 45
            while coordinator.durable_state()[0] != 3:
                coordinator.raise_if_failed()
                assert time.monotonic() < deadline, "publication did not finish"
                time.sleep(0.01)
            stopped = coordinator.stop()
            assert stopped.stopped and stopped.failure is None
            gc.collect()
            if args.device == "rocm":
                torch.cuda.synchronize()
            samples.append(rss())
            stop_sampling.set()
            sampler.join(1)
            evidence.update(
                {
                    "peak_host_rss": max(samples),
                    "process_peak_host_rss": resource.getrusage(
                        resource.RUSAGE_SELF
                    ).ru_maxrss
                    * 1024,
                    "host_rss_after": rss(),
                    "peak_device_allocated": torch.cuda.max_memory_allocated()
                    if args.device == "rocm"
                    else 0,
                    "peak_device_reserved": torch.cuda.max_memory_reserved()
                    if args.device == "rocm"
                    else 0,
                    "device_allocated_after": torch.cuda.memory_allocated()
                    if args.device == "rocm"
                    else 0,
                }
            )
            evidence["host_overhead_budget_bytes"] = (
                5 if args.device == "cpu" else 2
            ) * payload_bytes + 128 * MIB
            evidence["device_overhead_budget_bytes"] = 3 * payload_bytes + 64 * MIB
            reference = coordinator.durable_state()[1]
            assert reference is not None
            loaded = RunStoreReader(target, client=client).read_exact(reference)
            restored_model = model()
            restored_optimizer = make_optimizer(restored_model.parameters())
            restored_states: dict[str, Any] = {
                "model": restored_model,
                "optimizer": restored_optimizer,
            }

            def violate(rule, problem, advice):
                raise AssertionError((rule, problem, advice))

            restore_checkpoint(loaded, restored_states, run_id, "model-v1", violate)
            assert fingerprint(restored_states) == expected, (
                "whole model and optimizer state differ"
            )
            assert not tuple(Path(temporary).iterdir()), (
                "staged checkpoint survived publication"
            )
            evidence["whole_state_resume_verified"] = True
            print(json.dumps(evidence), flush=True)
            if args.expect_bounded:
                assert (
                    evidence["peak_host_rss"] - baseline_host
                    <= evidence["host_overhead_budget_bytes"]
                )
                if args.device == "rocm":
                    assert (
                        evidence["peak_device_allocated"] - baseline_device
                        <= evidence["device_overhead_budget_bytes"]
                    )
                    assert (
                        evidence["device_allocated_after"] <= baseline_device + MIB
                    ), "confirmed tensor payload retained"
        finally:
            release.set()
            coordinator.stop()
            stop_sampling.set()
            sampler.join(1)


if __name__ == "__main__":
    main()
