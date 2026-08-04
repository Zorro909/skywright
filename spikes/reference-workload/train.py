#!/usr/bin/env python3
"""PROTOTYPE — throwaway. B3: the training script, run standalone.

No orchestration layer, no SkyPilot, no backend. Just:

    python train.py

Kill it however you like (Ctrl-C, `kill`, `kill -9`) and run the same command
again. It must pick up where it left off with no manual intervention — that is
C1, and C2 is what makes it possible.

  Ctrl-C / SIGTERM -> a *graceful* interruption: the library stops at the next
                      safe point, writes a checkpoint, and exits 42.
  kill -9          -> no safe point at all: recovery falls back to the last
                      checkpoint the cadence happened to write.
"""

from __future__ import annotations

import argparse
import signal
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import project  # noqa: E402
from skywright_proto import ContractError, local_run_context  # noqa: E402

_interrupted = False


def _on_signal(signum, _frame):
    global _interrupted
    _interrupted = True
    print(f"\n  << signal {signal.Signals(signum).name}: stopping at the next Step >>",
          flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", default="proto-run-1")
    ap.add_argument("--steps", type=int, default=200)
    ap.add_argument("--store", default=None, help="Run Store root (default: ./runs/<run-id>)")
    ap.add_argument("--set-lr", type=float, default=None, help="a Run Submission override (B2)")
    args = ap.parse_args()

    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    overrides = {"optim": {"lr": args.set_lr}} if args.set_lr is not None else {}

    ctx = local_run_context(
        run_id=args.run_id,
        project_version=project.PROJECT_VERSION,
        schema=project.SCHEMA,
        defaults=project.DEFAULTS,
        overrides=overrides,
        store_root=Path(args.store or f"runs/{args.run_id}"),
        preempt=lambda: _interrupted,
        max_steps=args.steps,
        checkpoint_every=25,
    )

    # --- project setup: everything declared before training begins ---
    diffusion = project.Diffusion(ctx)
    diffusion.register(ctx)
    diffusion.declare(ctx)

    with ctx.start() as run:
        if run.resumed_from is not None:
            print(f"resumed at Step {run.resumed_from}, "
                  f"epoch {run.cursor.epoch} offset {run.cursor.offset}")
            for note in run.resume_notes:
                print(f"  ! {note}")
        else:
            print(f"fresh run {run.definition.run_id} ({run.definition.project_version})")
            # An Artifact: opaque to the library, kept for later inspection.
            run.save_artifact("model-architecture.txt", repr(diffusion.model).encode())

        # --- the loop belongs to the project ---
        for batch in run.batches(ctx.config["data"]["batch_size"]):
            for name, value in diffusion.train_step(batch).items():
                run.record(name, value)
            run.commit_step()

            if run.step % 25 == 0:
                print(f"  step {run.step:4d}  loss {run.last_value('train/loss'):.4f}  "
                      f"epoch {run.cursor.epoch} offset {run.cursor.offset:4d}  "
                      f"first sample {batch.sample_ids[0]}")
            if run.step % 100 == 0:
                run.save_sample("draw", diffusion.draw())

    print(f"\nphase={run.phase} step={run.step} stop_reason={run.stop_reason}")
    print(f"checkpoints: {[p.name for p in run.store.checkpoints()]}")
    return 42 if run.stop_reason == "preempted" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ContractError as exc:
        print(f"\nCONTRACT VIOLATION\n{exc}\n", file=sys.stderr)
        raise SystemExit(2) from None
