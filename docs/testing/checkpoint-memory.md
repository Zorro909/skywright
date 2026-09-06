# Checkpoint capture memory

Issue [#215](https://github.com/Zorro909/skywright/issues/215) bounds the payloads owned by checkpoint capture and publication. A capture makes one defensive copy of registered project state. The internal codec borrows that owned state, and `with_reference` shares it without copying leaves. Public snapshot accessors still make defensive copies.

The coordinator owns one active publication and one replaceable pending capture. A replacement is captured synchronously before admission, so three captures can temporarily coexist. Successful confirmation retains only `CheckpointConfirmation(step, reference)`. Superseded, cancelled and failed captures are released; failure tracebacks keep their locations and causes while completed frame locals are cleared. Rejection after a latched failure also drops the rejected capture before raising.

The maintainer chose the same metadata-only contract for `TrainingProcessResult.final_checkpoint`. Code needing full state loads it from the Run Store:

```python
confirmation = result.final_checkpoint
if confirmation is not None:
    snapshot = reader.read_exact(confirmation.reference)
```

## Workload and budget

`sdk/tests/support/checkpoint_memory_scenario.py` drives the actual coordinator, portable codec and Run Store publisher against the pinned SeaweedFS S3 fixture. It trains a four-layer, width-2048 MLP with 16,785,408 parameters. SGD with momentum has a 128.1 MiB checkpoint payload; AdamW has a 192.1 MiB payload, including scalar optimizer counters.

The first multipart upload is held at the S3 client call. Training continues, a second capture becomes pending, and a third replaces it. The fixture verifies that no Durable Safe Point is reported before publication. It then releases the upload, waits for Step 3, stops the coordinator and loads the durable checkpoint. Whole model and optimizer restoration must reproduce the pre-capture state fingerprint. The memory window ends before this independent read/restore validation.

For payload size `P`, the qualification asserts these overhead limits above the warmed process baseline:

| Measurement | Budget |
| --- | --- |
| CPU workload host RSS | `5P + 128 MiB` |
| ROCm workload host RSS | `2P + 128 MiB` |
| ROCm device allocation peak | `3P + 64 MiB` |
| ROCm device allocation after confirmation | baseline plus at most 1 MiB |

These budgets cover this workload and its training workspace. The library's general bound is the number of owned captures, not a universal model-size or allocator limit. Staging uses host-backed temporary storage and 8 MiB multipart pieces. This does not qualify the separate 64 MiB managed-runtime staging constraint in #218.

## Measurements

Measured on 2026-09-06 using an AMD Radeon RX 7900 XTX, PyTorch 2.12.0, and the repository's ROCm 7.14 profile. CPU runs use the locked CPU PyTorch environment. Each measurement starts in a fresh process. The baseline is unchanged commit `6d89d368b94ad7c5c89bee01f837ee5ae2bc77c8`.

| Workload | Peak host RSS | Host overhead | Peak device allocation | Device overhead | Captured device allocation retained |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline CPU / SGD | 1513.9 MiB | 1069.5 MiB | n/a | n/a | n/a |
| Fixed CPU / SGD | 987.3 MiB | 542.8 MiB | n/a | n/a | n/a |
| Fixed CPU / AdamW | 1307.0 MiB | 797.2 MiB | n/a | n/a | n/a |
| Baseline ROCm / SGD | 1409.7 MiB | 217.5 MiB | 792.3 MiB | 512.3 MiB | 128.1 MiB |
| Fixed ROCm / SGD | 1394.5 MiB | 201.1 MiB | 664.3 MiB | 384.2 MiB | 0.0 MiB |
| Fixed ROCm / AdamW | 1492.8 MiB | 293.2 MiB | 920.4 MiB | 576.3 MiB | 0.0 MiB |

Both baseline SGD runs fail the budget assertions. All fixed workloads pass. The unchanged codec cannot serialize AdamW's zero-dimensional step tensors; the scalar round-trip regression and fix are included in #215, so the baseline comparison uses SGD.

The three SGD captures took 23.1–32.1 ms on the baseline CPU and 18.0–23.0 ms after the fix. ROCm captures took 1.3–1.9 ms before and 0.9–1.4 ms after. The fixed AdamW captures took 20.8–22.5 ms on CPU and 1.1–1.7 ms on ROCm. Training and replacement capture overlapped the held upload in every run.

Host RSS is sampled every 5 ms; the process's kernel high-water RSS is also recorded in the raw evidence. PyTorch device allocation peaks are reset after warmup and read through its allocator statistics. Reserved allocator memory is recorded separately. Releasing tensor ownership makes that memory reusable and does not force the CPU or GPU allocator to return every cached allocation to the operating system. Weak-reference regressions check ownership release directly.

These are workload measurements, not latency guarantees or GPU OOM qualification for arbitrary projects. Raw evidence, image identity and the measured SDK commit are in [checkpoint-memory.json](checkpoint-memory.json).

## Reproduce

The automated CPU scenario is part of the real S3 suite:

```sh
uv run --project sdk --locked --group ml-test pytest \
  sdk/tests/test_run_store_system.py \
  -k model_optimizer_checkpoint_memory_scenario_uses_real_s3
```

For the representative CPU workload, supply the local fixture endpoint and bucket:

```sh
uv run --project sdk --locked --group ml-test python \
  sdk/tests/support/checkpoint_memory_scenario.py \
  --endpoint http://127.0.0.1:8333 --bucket checkpoint-memory \
  --device cpu --optimizer adamw --expect-bounded
```

The fixture uses `test-access-key` and `test-secret-key`; it is intended for a disposable local S3 service. `seaweedfs()` in `sdk/tests/test_run_store_system.py` starts and cleans up the pinned service.

ROCm qualification used the locally built `localhost/skywright-environment-profile:rocm-check` image, with the current SDK source mounted read-only. That image is built by `environment-profiles/scripts/check` from the pinned profile Containerfile. Run from the repository root with the disposable service already available on the host:

```sh
podman run --rm --device /dev/kfd --device /dev/dri \
  --security-opt label=disable --group-add keep-groups --network host \
  --env ROCR_VISIBLE_DEVICES=0 --env PYTHONPATH=/source \
  --volume "$PWD/sdk/src:/source:ro" \
  --volume "$PWD/sdk/tests/support/checkpoint_memory_scenario.py:/scenario.py:ro" \
  --entrypoint python localhost/skywright-environment-profile:rocm-check \
  /scenario.py --endpoint http://127.0.0.1:8333 --bucket checkpoint-memory \
  --device rocm --optimizer adamw --expect-bounded
```

The run fails if the selected device is not ROCm. Select the intended GPU explicitly when the host also exposes an integrated GPU. Use `--optimizer sgd` for the paired baseline workload; `--width 32 --layers 2` is the small CI fixture, not the representative memory measurement.
