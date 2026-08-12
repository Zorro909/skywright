# pyright: strict

import skywright
from skywright import __version__, version


def accepts_string(value: str) -> None:
    del value


accepts_string(__version__)
accepts_string(version)
public_names: tuple[str, ...] = skywright.__all__
