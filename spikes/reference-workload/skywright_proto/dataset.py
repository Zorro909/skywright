"""PROTOTYPE — throwaway. The one backend-neutral Dataset path (ADR 0003, D3/D4/D5).

The real first implementation is MosaicML Streaming over S3-compatible storage.
That backend is deliberately *not* here: this prototype answers "does the
contract feel right in the hand", and a synthetic in-memory backend exercises
exactly the same project-facing surface without needing a bucket.

What is real here is the part ADR 0003 places *above* storage: stable Sample
identity, a seeded per-epoch order, and a cursor that survives a resume. How
strong that resume guarantee must be is issue #24 — this prototype implements
the strong end (bit-exact continuation) so the weaker option can be judged
against something concrete.
"""

from __future__ import annotations

import hashlib
import random
from dataclasses import dataclass

import torch


@dataclass
class Cursor:
    """The data position half of C2."""

    epoch: int = 0
    offset: int = 0
    samples_seen: int = 0

    def state_dict(self) -> dict:
        return {"epoch": self.epoch, "offset": self.offset, "samples_seen": self.samples_seen}

    def load_state_dict(self, state: dict) -> None:
        self.epoch = state["epoch"]
        self.offset = state["offset"]
        self.samples_seen = state["samples_seen"]


@dataclass
class Batch:
    sample_ids: list[str]
    x: torch.Tensor


class Dataset:
    """A resolved Dataset Definition version, handed to the project by the Run Context.

    The project never sees a Dataset Location, a protocol, or a cache — per
    ADR 0003 those are selected per execution and recorded on the Run Record.
    """

    def __init__(self, definition_id: str, version: str, size: int, seed: int, image: int = 8):
        self.definition_id = definition_id
        self.version = version
        self.size = size
        self.seed = seed
        self.image = image
        # Stand-in for the location Skywright would select and record.
        self.resolved_location = f"s3://prototype-not-a-real-bucket/{definition_id}/{version}"

    def _order(self, epoch: int) -> list[int]:
        rng = random.Random(f"{self.seed}:{self.definition_id}:{self.version}:{epoch}")
        idx = list(range(self.size))
        rng.shuffle(idx)
        return idx

    def sample_id(self, index: int) -> str:
        return f"{self.definition_id}#{index:05d}"

    def _payload(self, index: int) -> torch.Tensor:
        """Content-addressed pixels, so a resumed run demonstrably sees the same bytes."""
        digest = hashlib.sha256(self.sample_id(index).encode()).digest()
        gen = torch.Generator().manual_seed(int.from_bytes(digest[:8], "big"))
        base = torch.randn(1, self.image, self.image, generator=gen) * 0.3
        # A weak 2-mode structure so the loss visibly falls in a few hundred steps.
        base += 1.0 if index % 2 else -1.0
        return base.clamp(-1.0, 1.0)

    def batches(self, cursor: Cursor, batch_size: int):
        """Resume-aware iteration. Continues mid-epoch from the cursor, then wraps forever."""
        while True:
            order = self._order(cursor.epoch)
            while cursor.offset + batch_size <= self.size:
                picked = order[cursor.offset : cursor.offset + batch_size]
                # Advance BEFORE yielding: a checkpoint taken at the safe point after
                # this batch must point at the next *unseen* batch, not replay this one.
                cursor.offset += batch_size
                cursor.samples_seen += batch_size
                yield Batch(
                    sample_ids=[self.sample_id(i) for i in picked],
                    x=torch.stack([self._payload(i) for i in picked]),
                )
            cursor.epoch += 1
            cursor.offset = 0
