# Reference workload — a contract exerciser

**Throwaway prototype for [issue #20](https://github.com/Zorro909/skywright/issues/20). Nothing here is proposed for the product.**

## The question

No consumer code exists, so the Training Contract in
[ADR 0001](../../docs/adr/0001-enforce-training-contract-without-owning-loop.md)
has nothing to prove itself against and every judgement about it is abstract.
This is the smallest concrete thing that can be judged instead: a deliberately
trivial DDPM on 8×8 synthetic images, wired through a candidate Run Context.

Three things to react to:

1. **Does the contract feel right in the hand?** Read [`project.py`](project.py) —
   that is everything a Training Project would write. Is the split honest?
2. **Does `B3` hold?** `python train.py` is the whole story. No orchestrator, no
   SkyPilot, no backend, breakpoints work.
3. **Does `B4` bite early and clearly?** `python drive.py`, keys `1`–`8`.
   If a message would not tell you what to fix at 2am, that is a finding.

## Run it

Needs Python with `torch` (CPU is fine and intended — this runs in seconds).

```
cd spikes/reference-workload

python train.py                  # B3: run standalone
python train.py --steps 400      # ...and again: it resumes where it stopped (C1)
python train.py --set-lr 5e-4    # a Run Submission override (B2)

python drive.py                  # the hand-driven exerciser (see keys on screen)
```

Kill `train.py` however you like:

| how | what happens |
|---|---|
| `Ctrl-C` / `SIGTERM` | graceful: stops at the next safe point, checkpoints, exits **42** |
| `kill -9` | no safe point: recovery falls back to the last cadence checkpoint |

Run state lands in `runs/<run-id>/` — checkpoints, samples, artifacts, metric
catalog and events, kept separate the way ADR 0001 asks. Delete the directory
to start over.

## Layout

| file | what it is |
|---|---|
| [`project.py`](project.py) | **the Training Project.** Model, loss, hyperparameters, data semantics. Owns no loop. |
| [`train.py`](train.py) | entry point 1: a plain script with a project-owned loop. The `B3` check. |
| [`drive.py`](drive.py) | entry point 2: a TUI that drives the same project one Step at a time. The `B4`/`C1` check. |
| [`skywright_proto/contract.py`](skywright_proto/contract.py) | the candidate Run Context — the bit worth arguing about |
| [`skywright_proto/runstore.py`](skywright_proto/runstore.py) | local Run Store: atomic checkpoints, retention, samples, artifacts, metric events |
| [`skywright_proto/dataset.py`](skywright_proto/dataset.py) | the one backend-neutral Dataset path, with a synthetic backend |
| [`skywright_proto/config.py`](skywright_proto/config.py) | resolved Run Configuration and its validation |

Both entry points import the *same* `project.py`. That the TUI can drive a
project which knows nothing about it is the clearest evidence that the loop
really does belong to the project.

## What it does on the local box

CPU only, on purpose. `rocminfo` sees the RX 7900 XTX, but there is no ROCm
userspace and the installed torch is a CUDA build — that is
[issue #21](https://github.com/Zorro909/skywright/issues/21)'s job. A contract
exerciser should not need an accelerator, and this one does not.

## Seams left deliberately visible

These are *not* answered here; the prototype shows them rather than hiding them.

- **Merge semantics** for configuration overrides are a placeholder — that is
  [issue #22](https://github.com/Zorro909/skywright/issues/22).
- **`seed` sits in the project's schema**, which is probably wrong: ADR 0001
  splits Run Configuration into library-defined common options and
  project-defined ones, and `D5` makes the seed a library concern.
- **The Dataset backend is synthetic.** ADR 0003's MosaicML Streaming over
  S3-compatible storage is absent; only the surface above it is real.
- **Metric events are JSONL**, standing in for `SummaryWriter.add_scalar`
  ([research](../../docs/research/tensorboard-metric-contract.md)). The shape is
  the same; the dependency is not worth a prototype.
- **A clean finish writes no final checkpoint** — only the cadence does. Whether
  finishing is itself a safe point is an open contract question.
- **Constructing a Run Context seeds the global RNG**, so a second one in the
  same process silently perturbs the first. `drive.py` saves and restores around
  its scratch context. Whether one process may host more than one run is
  downstream of the bridge decision ([issue #19](https://github.com/Zorro909/skywright/issues/19)).
- **Some contract rules are only reachable before `start()`.** The lifecycle
  check correctly shadows them afterwards, so a driver that exercises them after
  the run began shows the wrong error — `drive.py` key `8` did exactly that
  until it was routed through a pre-start context.
- **Where the Run Store actually lives** is [issue #17](https://github.com/Zorro909/skywright/issues/17).
