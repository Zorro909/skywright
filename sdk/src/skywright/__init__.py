"""Public bootstrap surface for the Skywright runtime SDK."""

from importlib.metadata import version as _distribution_version

__all__ = ("__version__", "version")

__version__: str = _distribution_version("skywright")
version: str = __version__
