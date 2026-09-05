"""Copying, freezing, and restoring Training Process state."""

import importlib
import importlib.util
import random
from collections.abc import Callable, Mapping
from types import MappingProxyType
from typing import NoReturn, cast


def capture_runtime_state() -> dict[str, object]:
    state: dict[str, object] = {"python_random": random.getstate()}
    if importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        state["numpy_random"] = numpy.random.get_state()
    if importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        state["torch_cpu_random"] = torch.get_rng_state()
        if torch.cuda.is_available():
            state["torch_accelerator_random"] = torch.cuda.get_rng_state_all()
    return state


def restore_runtime_state(state: Mapping[str, object]) -> None:
    python_state = state.get("python_random")
    if python_state is not None:
        random.setstate(cast(tuple[object, ...], python_state))
    if "numpy_random" in state and importlib.util.find_spec("numpy") is not None:
        numpy = importlib.import_module("numpy")
        numpy.random.set_state(state["numpy_random"])
    if "torch_cpu_random" in state and importlib.util.find_spec("torch") is not None:
        torch = importlib.import_module("torch")
        torch.set_rng_state(state["torch_cpu_random"])
        if "torch_accelerator_random" in state and torch.cuda.is_available():
            # Restore surviving local device indices. Additional devices retain
            # their library-established seed; numerical equivalence is not promised.
            saved = cast(list[object], state["torch_accelerator_random"])
            for index, device_state in enumerate(saved[: torch.cuda.device_count()]):
                torch.cuda.set_rng_state(device_state, index)


def freeze(value: object) -> object:
    if isinstance(value, Mapping):
        mapping = cast(Mapping[object, object], value)
        return MappingProxyType(
            {str(key): freeze(item) for key, item in mapping.items()}
        )
    if isinstance(value, list | tuple):
        sequence = cast(list[object] | tuple[object, ...], value)
        return tuple(freeze(item) for item in sequence)
    if isinstance(value, set | frozenset):
        values = cast(set[object] | frozenset[object], value)
        return frozenset(freeze(item) for item in values)
    return value


def validate_output(
    name: str,
    data: object,
    kind: str,
    violate: Callable[[str, str, str], NoReturn],
) -> bytes:
    if not name or name.startswith("/") or ".." in name.split("/"):
        violate(
            "run-output/name",
            f"{kind} name {name!r} is not a safe relative name",
            "use a non-empty relative name without parent traversal",
        )
    if not isinstance(data, bytes):
        violate(
            "run-output/data",
            f"{kind} {name!r} received {type(data).__name__}",
            "persist immutable bytes",
        )
    return data
