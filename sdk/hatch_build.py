"""Hatch build hook for immutable SDK source identity."""

import ast
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

from hatchling.builders.hooks.plugin.interface import BuildHookInterface


class CustomBuildHook(BuildHookInterface):
    def initialize(self, version: str, build_data: dict[str, Any]) -> None:
        del version
        frozen_source_revision = self._frozen_source_revision()
        source_revision = (
            frozen_source_revision
            if self._building_from_sdist()
            or frozen_source_revision not in {None, "unknown"}
            else os.environ.get("SKYWRIGHT_SOURCE_REVISION") or frozen_source_revision
        )
        if os.environ.get("SKYWRIGHT_BUILD_MODE") == "release" and source_revision in {
            None,
            "unknown",
        }:
            raise ValueError(
                "SKYWRIGHT_SOURCE_REVISION is required when "
                "SKYWRIGHT_BUILD_MODE=release"
            )

        self._temporary_directory = TemporaryDirectory(
            prefix="skywright-build-information-"
        )
        generated_file = Path(self._temporary_directory.name) / "_build_info.py"
        generated_file.write_text(
            f"PACKAGE_VERSION = {json.dumps(self.metadata.version)}\n"
            f"SOURCE_REVISION = {json.dumps(source_revision or 'unknown')}\n",
            encoding="utf-8",
        )
        destination = (
            "skywright/_build_info.py"
            if self.target_name == "wheel"
            else "src/skywright/_build_info.py"
        )
        build_data["force_include"][str(generated_file)] = destination

    def finalize(
        self, version: str, build_data: dict[str, Any], artifact_path: str
    ) -> None:
        del version, build_data, artifact_path
        self._temporary_directory.cleanup()

    def _frozen_source_revision(self) -> str | None:
        build_information = Path(self.root) / "src/skywright/_build_info.py"
        if not build_information.is_file():
            return None

        for line in build_information.read_text(encoding="utf-8").splitlines():
            if line.startswith("SOURCE_REVISION = "):
                value = ast.literal_eval(line.removeprefix("SOURCE_REVISION = "))
                return value if isinstance(value, str) else None
        return None

    def _building_from_sdist(self) -> bool:
        return (Path(self.root) / "PKG-INFO").is_file()
