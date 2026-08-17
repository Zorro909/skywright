from __future__ import annotations

from pathlib import Path

ACTION_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ACTION_ROOT.parents[2]
SDK_ROOT = REPOSITORY / "sdk"


def test_composite_action_is_the_single_publication_interface() -> None:
    action = (ACTION_ROOT / "action.yml").read_text(encoding="utf-8")

    assert "definition:" in action
    assert "registry-username:" in action
    assert "registry-password:" in action
    assert "version-label:" in action
    assert "manifest-digest:" in action
    assert "artifact-digest:" in action
    assert "python" in action
    assert "skywright_project_action" in action


def test_runtime_sdk_contains_no_project_publication_interface_or_implementation() -> (
    None
):
    sdk_project = (SDK_ROOT / "pyproject.toml").read_text(encoding="utf-8")

    assert "skywright-project" not in sdk_project
    assert not (SDK_ROOT / "src/skywright/project.py").exists()
    assert not list((SDK_ROOT / "src/skywright").glob("_project_*.py"))
    assert not list((SDK_ROOT / "tests").glob("test_project_*.py"))


def test_action_uses_only_public_sdk_contract_modules() -> None:
    action_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ACTION_ROOT / "src/skywright_project_action").glob("*.py")
    )

    assert "skywright._configuration" not in action_sources
    assert "skywright._metric" not in action_sources
    assert "from skywright.configuration import" in action_sources
    assert "from skywright.metrics import" in action_sources
