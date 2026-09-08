from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from skywright_project_action.environment import project_environment_script

pytestmark = pytest.mark.system


def test_nested_project_retains_profile_packages_and_processes_its_pth(
    tmp_path: Path,
) -> None:
    profile = tmp_path / "profile"
    project = tmp_path / "project"
    subprocess.run([sys.executable, "-m", "venv", str(profile)], check=True)
    interpreter = profile / "bin/python"
    packages = Path(
        subprocess.check_output(
            [str(interpreter), "-c", "import site; print(site.getsitepackages()[0])"],
            text=True,
        ).strip()
    )
    (packages / "qualified_sdk.py").write_text("IDENTITY = 'profile'\n")
    accelerator = tmp_path / "accelerator"
    accelerator.mkdir()
    (accelerator / "qualified_accelerator.py").write_text("IDENTITY = 'accelerator'\n")
    (packages / "accelerator.pth").write_text(str(accelerator) + "\n")

    subprocess.run(
        [str(interpreter), "-c", project_environment_script(str(project))], check=True
    )
    result = subprocess.check_output(
        [
            str(project / "bin/python"),
            "-c",
            "import json, qualified_sdk, qualified_accelerator, sys; "
            "print(json.dumps([qualified_sdk.IDENTITY, qualified_accelerator.IDENTITY, "
            "qualified_sdk.__file__, sys.prefix]))",
        ],
        text=True,
    )

    assert json.loads(result) == [
        "profile",
        "accelerator",
        str(packages / "qualified_sdk.py"),
        str(project),
    ]
