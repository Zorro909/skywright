"""Private operational entry point for the Skywright runtime."""

import argparse
from importlib.metadata import version

from skywright._build_info import SOURCE_REVISION


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="skywright-runtime",
        description="Execute a Skywright Training Project.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--version",
        action="version",
        version=(
            f"skywright-runtime {version('skywright')}\n"
            f"source revision: {SOURCE_REVISION}"
        ),
    )
    parser.parse_args()
    parser.error(
        "training execution is unavailable until the Training Process Boundary "
        "is implemented"
    )
