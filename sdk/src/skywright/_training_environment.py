"""One-process ownership, component loading, and determinism setup."""

import importlib
import importlib.util
import random
import threading
from typing import cast

from skywright._training_errors import TrainingContractViolation
from skywright._training_protocols import TrainingProject

_process_claim_lock = threading.Lock()
_process_claimed = False


def load_training_project(reference: str) -> TrainingProject:
    module_name, separator, attribute_name = reference.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError("entry point must use MODULE:CALLABLE form")
    project = getattr(importlib.import_module(module_name), attribute_name)
    if not callable(project):
        raise TypeError(f"entry point {reference!r} is not callable")
    return cast(TrainingProject, project)


def resolve_component(component: object, kind: str) -> object:
    if not isinstance(component, str):
        return component
    module_name, separator, attribute_name = component.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError(f"{kind} factory must use MODULE:CALLABLE form")
    factory = getattr(importlib.import_module(module_name), attribute_name)
    if not callable(factory):
        raise TypeError(f"{kind} factory {component!r} is not callable")
    return factory()


def claim_process() -> None:
    global _process_claimed
    with _process_claim_lock:
        if _process_claimed:
            raise TrainingContractViolation(
                "run-context/one-per-process",
                "this process already attempted to construct a Run Context",
                "start every direct or managed invocation in a fresh process",
            )
        _process_claimed = True


def establish_determinism(seed: int) -> None:
    random.seed(seed)
    if importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        numpy.random.seed(seed % (2**32))
    if importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)
        torch.use_deterministic_algorithms(True)
        if hasattr(torch.backends, "cudnn"):
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True
