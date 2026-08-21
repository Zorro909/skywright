"""Checked monotonic time for Training Process instrumentation."""

import math
from collections.abc import Callable

from skywright._training_errors import SkywrightFailure


def read_monotonic(clock: Callable[[], float]) -> float:
    try:
        moment = clock()
        if isinstance(moment, bool) or not math.isfinite(moment):
            raise ValueError("the monotonic clock produced a non-finite value")
    except Exception as failure:
        raise SkywrightFailure(failure, "project") from failure
    return float(moment)
