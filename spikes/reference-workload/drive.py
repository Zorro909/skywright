#!/usr/bin/env python3
"""PROTOTYPE — throwaway. The hand-driven contract exerciser.

    python drive.py

train.py answers "does it run and resume". This answers the two questions you
cannot answer by watching a script succeed:

  - Does the contract feel right in the hand? Every piece of Run Context state
    is on screen after every action, so you can see exactly what the library is
    holding on the project's behalf.
  - Does B4 bite early and clearly? Keys 1-6 misuse the contract deliberately.
    Read the messages: if one of them would not tell you what to fix at 2am,
    that is a finding.

Kill/resume is `k` then `r`, which is the SIGKILL case — no safe point, so
recovery falls back to whatever the checkpoint cadence last wrote.
"""

from __future__ import annotations

import random
import sys
import termios
import tty
from contextlib import ExitStack
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import torch  # noqa: E402

import project  # noqa: E402
from skywright_proto import ContractError, MetricSpec, local_run_context  # noqa: E402

B, D, R = "\x1b[1m", "\x1b[2m", "\x1b[0m"
STORE = Path("runs/proto-driven")
SCRATCH = Path("runs/_misuse-scratch")


class Driver:
    def __init__(self):
        self.message = "fresh Run Context"
        self.error: ContractError | None = None
        self.stack = ExitStack()
        self._build(fresh=True)

    # --- lifecycle --------------------------------------------------------

    def _build(self, fresh: bool = False):
        self.preempt = False
        self.ctx = local_run_context(
            run_id="proto-driven",
            project_version=project.PROJECT_VERSION,
            schema=project.SCHEMA,
            defaults=project.DEFAULTS,
            store_root=STORE,
            preempt=lambda: self.preempt,
            max_steps=10_000,
            checkpoint_every=10,
        )
        if fresh:
            self.ctx.store.wipe()
        self.diffusion = project.Diffusion(self.ctx)
        self.diffusion.register(self.ctx)
        self.diffusion.declare(self.ctx)
        self.run = self.stack.enter_context(self.ctx.start())
        self.batches = self.run.batches(self.ctx.config["data"]["batch_size"])

    def hard_kill(self):
        """SIGKILL: the process vanishes. Nothing in memory survives; only the Run Store does."""
        self.stack = ExitStack()  # drop the old context entirely, no __exit__, no checkpoint
        self.ctx = self.run = self.batches = self.diffusion = None
        self.message = "process killed — only the Run Store survives. Press [r] to resume."

    def resume(self):
        self.stack = ExitStack()
        self._build(fresh=False)
        self.message = (f"resumed from Step {self.run.resumed_from}"
                        if self.run.resumed_from is not None
                        else "no checkpoint in the Run Store — started fresh")

    # --- actions ----------------------------------------------------------

    def step(self, n: int = 1):
        for _ in range(n):
            batch = next(self.batches, None)
            if batch is None:
                self.message = f"iteration stopped (stop_reason={self.run.stop_reason})"
                return
            for name, value in self.diffusion.train_step(batch).items():
                self.run.record(name, value)
            self.run.commit_step()
        self.message = f"committed {n} Step(s)"

    def pre_start_ctx(self):
        """A fresh Run Context that has NOT started.

        Some contract rules are only reachable before start(): the lifecycle
        check in register_state() fires first and — correctly — shadows them,
        because registering after the run began is a violation whatever the
        object is. Demonstrating those rules needs a context still in its
        registration phase, not a different rule ordering.

        Note the wart this exposes: constructing a Run Context seeds the global
        RNG, so a second one in the same process silently perturbs the run being
        driven. Saved and restored here. See the README.
        """
        rng, pyrng = torch.get_rng_state(), random.getstate()
        try:
            return local_run_context(
                run_id="misuse-scratch",
                project_version=project.PROJECT_VERSION,
                schema=project.SCHEMA,
                defaults=project.DEFAULTS,
                store_root=SCRATCH,
            )
        finally:
            torch.set_rng_state(rng)
            random.setstate(pyrng)

    MISUSE = {
        "1": ("record an undeclared metric",
              lambda d: d.run.record("train/accuracy", 0.5)),
        "2": ("register Checkpoint State after start()",
              lambda d: d.ctx.register_state("ema", torch.optim.swa_utils.AveragedModel(
                  d.diffusion.model))),
        "3": ("record the same metric twice in one Step",
              lambda d: [d.run.record("train/loss", 1.0), d.run.record("train/loss", 2.0)]),
        "4": ("record a NaN",
              lambda d: d.run.record("train/loss", float("nan"))),
        "5": ("read a config key the contract does not declare",
              lambda d: d.ctx.config["optim"]["momentum"]),
        "6": ("commit a Step that did no work",
              lambda d: d.run.commit_step()),
        "7": ("declare a metric after start()",
              lambda d: d.ctx.declare_metric(MetricSpec("train/fid"))),
        # Pre-start, or the lifecycle check in [2] shadows it and both keys show
        # the same error — which is exactly what this prototype did at first.
        "8": ("register a non-resumable object (pre-start)",
              lambda d: d.pre_start_ctx().register_state("notes", {"a": 1})),
    }

    def misuse(self, key: str):
        label, fn = self.MISUSE[key]
        self.error = None
        try:
            fn(self)
            self.message = f"!! {label} — WAS ACCEPTED (B4 did not bite)"
        except ContractError as exc:
            self.error = exc
            self.message = f"{label} -> rejected"
        finally:
            self.run._pending.pop("train/loss", None)  # prototype: un-wedge after 3/4

    def corrupt_latest(self):
        paths = self.ctx.store.checkpoints()
        if not paths:
            self.message = "no checkpoint to corrupt"
            return
        paths[-1].write_bytes(b"not a checkpoint")
        self.message = f"corrupted {paths[-1].name} — press [k] then [r] to test C5 fallback"

    # --- rendering --------------------------------------------------------

    def frame(self) -> str:
        out = ["\x1b[2J\x1b[H", f"{B}SKYWRIGHT CONTRACT EXERCISER{R} {D}(prototype, issue #20){R}\n"]
        if self.ctx is None:
            out.append(f"{D}  <no Run Context — the process is gone>{R}\n")
        else:
            s = self.ctx.snapshot()
            out.append(f"{B}Run{R}        {s['run_id']}  {D}{s['project_version']}{R}")
            out.append(f"{B}Phase{R}      {s['phase']}   step {B}{s['step']}{R}"
                       f"   stop_reason={s['stop_reason']}"
                       f"   resumed_from={s['resumed_from']}")
            out.append(f"{B}Dataset{R}    {s['dataset']}   {D}{s['location']}{R}")
            c = s["cursor"]
            out.append(f"{B}Cursor{R}     epoch {c['epoch']}  offset {c['offset']}  "
                       f"samples_seen {c['samples_seen']}")
            out.append(f"{B}Ckpt state{R} {', '.join(s['state_keys'])}")
            out.append(f"{B}Metrics{R}    " + "  ".join(
                f"{k}={v:.4g}" for k, v in s["last_values"].items()) or "(none yet)")
            if s["pending"]:
                out.append(f"{D}  pending this Step: {s['pending']}{R}")
            out.append(f"{B}Run Store{R}  {', '.join(s['checkpoints']) or '(no checkpoints)'}")
            if s["resume_notes"]:
                out.append(f"{B}Resume{R}     " + "; ".join(s["resume_notes"]))
        out.append("")
        out.append(f"{B}>{R} {self.message}")
        if self.error:
            out.append(f"\n{B}ContractError{R}")
            for line in str(self.error).splitlines():
                out.append(f"  {line}")
        out.append("")
        out.append(f"{D}[s]{R} step   {D}[S]{R} step x10   {D}[c]{R} checkpoint   "
                   f"{D}[d]{R} draw Sample   {D}[p]{R} preempt")
        out.append(f"{D}[k]{R} kill (no safe point)   {D}[r]{R} resume   "
                   f"{D}[9]{R} corrupt newest ckpt   {D}[w]{R} wipe   {D}[q]{R} quit")
        out.append(f"{D}misuse:{R} " + "  ".join(f"{D}[{k}]{R} {v[0]}" for k, v in
                                                 list(self.MISUSE.items())[:4]))
        out.append(f"         " + "  ".join(f"{D}[{k}]{R} {v[0]}" for k, v in
                                            list(self.MISUSE.items())[4:]))
        return "\n".join(out)


def read_key() -> str:
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        return sys.stdin.read(1)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


def main() -> None:
    d = Driver()
    while True:
        print(d.frame(), flush=True)
        key = read_key()
        d.error = None
        try:
            if key in ("q", "\x03"):
                print("\x1b[2J\x1b[H", end="")
                return
            if key in d.MISUSE:
                d.misuse(key)
            elif key == "s":
                d.step(1)
            elif key == "S":
                d.step(10)
            elif key == "c":
                d.message = f"checkpoint written: {d.run.checkpoint().name}"
            elif key == "d":
                d.message = f"Sample written: {d.run.save_sample('draw', d.diffusion.draw()).name}"
            elif key == "p":
                d.preempt = True
                d.message = "preemption signalled — the next commit_step() is the safe point"
            elif key == "k":
                d.hard_kill()
            elif key == "r":
                d.resume()
            elif key == "9":
                d.corrupt_latest()
            elif key == "w":
                d.stack = ExitStack()
                d._build(fresh=True)
                d.message = "Run Store wiped, fresh Run Context"
            else:
                d.message = f"unknown key {key!r}"
        except ContractError as exc:
            d.error = exc
            d.message = "ContractError (see below)"
        except AttributeError:
            d.message = "the process is gone — press [r] to resume or [w] to wipe"


if __name__ == "__main__":
    main()
