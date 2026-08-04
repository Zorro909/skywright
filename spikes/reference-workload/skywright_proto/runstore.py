"""PROTOTYPE — throwaway. The mandatory Run Store (ADR 0001; C3, C5, C6).

Every run has one. Its *backend* may differ between local and external
execution — issue #17 decides that — but its project-facing surface must not.
This prototype implements the local-directory backend so the surface can be
exercised; the three separate operations ADR 0001 calls for (validated
checkpoints, library-understood Samples, arbitrary Artifacts) are kept
genuinely separate rather than collapsed into one write().
"""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path

import torch


class RunStore:
    def __init__(self, root: Path, keep_checkpoints: int = 3):
        self.root = Path(root)
        self.keep = keep_checkpoints
        for sub in ("checkpoints", "samples", "artifacts", "metrics"):
            (self.root / sub).mkdir(parents=True, exist_ok=True)

    # --- checkpoints -----------------------------------------------------

    def _ckpt_path(self, step: int) -> Path:
        return self.root / "checkpoints" / f"step-{step:08d}.pt"

    def write_checkpoint(self, step: int, payload: dict) -> Path:
        """C5: write to a temp name and rename, so a killed process cannot publish a torn file."""
        final = self._ckpt_path(step)
        tmp = final.with_suffix(".pt.partial")
        torch.save(payload, tmp)
        os.replace(tmp, final)
        self._apply_retention()
        return final

    def _apply_retention(self) -> None:
        """C6: bounded history, oldest first."""
        existing = sorted((self.root / "checkpoints").glob("step-*.pt"))
        for stale in existing[: max(0, len(existing) - self.keep)]:
            stale.unlink()

    def checkpoints(self) -> list[Path]:
        return sorted((self.root / "checkpoints").glob("step-*.pt"))

    def load_latest_checkpoint(self) -> tuple[dict | None, list[str]]:
        """C5: a corrupt newest checkpoint must not make the run unusable — fall back."""
        notes: list[str] = []
        for path in reversed(self.checkpoints()):
            try:
                return torch.load(path, weights_only=False), notes
            except Exception as exc:  # noqa: BLE001 - prototype
                notes.append(f"rejected {path.name}: {type(exc).__name__}")
        return None, notes

    # --- samples and artifacts -------------------------------------------

    def write_sample(self, step: int, name: str, image: torch.Tensor) -> Path:
        """A Sample is library-understood media. PGM keeps the prototype dependency-free."""
        pixels = ((image.squeeze().clamp(-1, 1) + 1) * 127.5).to(torch.uint8)
        h, w = pixels.shape
        path = self.root / "samples" / f"step-{step:08d}-{name}.pgm"
        path.write_bytes(b"P5\n%d %d\n255\n" % (w, h) + pixels.numpy().tobytes())
        return path

    def write_artifact(self, name: str, data: bytes) -> Path:
        """An Artifact is opaque to the library."""
        path = self.root / "artifacts" / name
        path.write_bytes(data)
        return path

    # --- metric catalog and events ---------------------------------------

    def write_metric_catalog(self, catalog: list[dict]) -> None:
        (self.root / "metrics" / "catalog.json").write_text(json.dumps(catalog, indent=2))

    def append_metric_events(self, events: list[dict]) -> None:
        """Stands in for SummaryWriter.add_scalar — see docs/research/tensorboard-metric-contract.md.

        Real events go to TensorBoard; JSONL here keeps the prototype dependency-free
        while preserving the shape (tag, step, wall time, value).
        """
        with (self.root / "metrics" / "events.jsonl").open("a") as fh:
            for event in events:
                fh.write(json.dumps(event) + "\n")

    def read_metric_events(self) -> list[dict]:
        path = self.root / "metrics" / "events.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    # --- prototype conveniences ------------------------------------------

    def wipe(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)
        self.__init__(self.root, self.keep)
