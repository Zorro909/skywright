"""Public bootstrap surface for the Skywright runtime SDK."""

from importlib.metadata import version as _distribution_version

__all__ = ("__version__",)

__version__ = _distribution_version("skywright")
