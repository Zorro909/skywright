# Test project receives the dynamically shaped checkpoint and Dataset payloads.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false
"""CPU contract workload using only the managed project's fixed entry point."""

import json
import os
import signal
import time
from pathlib import Path


class State:
    def __init__(self):
        self.ordinals = []

    def state_dict(self):
        return {"ordinals": self.ordinals}

    def load_state_dict(self, value):
        self.ordinals = list(value["ordinals"])


def train(context):
    state = State()
    context.register_checkpoint_state("state", state)
    context.start()
    Path("started.json").write_text(
        json.dumps(
            {
                "step": context.step,
                "epoch": context.dataset_cursor.epoch,
                "offset": context.dataset_cursor.item_offset,
                "ordinals": state.ordinals,
            }
        )
    )
    while context.step < int(os.environ.get("FIXTURE_TOTAL_STEPS", "12")):
        batches = iter(context.dataset.batches(context.dataset_cursor))
        try:
            for batch in batches:
                state.ordinals.extend(item.ordinal for item in batch.items)
                if context.step + 1 == int(
                    os.environ.get("FIXTURE_INTERRUPT_STEP", "-1")
                ):
                    os.kill(os.getpid(), signal.SIGTERM)
                if context.step + 1 == int(os.environ.get("FIXTURE_STOP_STEP", "-1")):
                    Path("stop-ready").touch()
                    deadline = time.monotonic() + 20
                    while not Path("stop-delivered").exists():
                        if time.monotonic() >= deadline:
                            raise RuntimeError("stop fixture delivery timed out")
                        time.sleep(0.02)
                context.commit_step(batch)
                Path("committed.json").write_text(json.dumps(state.ordinals))
                if context.step >= int(os.environ.get("FIXTURE_TOTAL_STEPS", "12")):
                    return
        finally:
            batches.close()
