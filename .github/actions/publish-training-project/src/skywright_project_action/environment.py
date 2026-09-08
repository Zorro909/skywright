"""Create the project environment while retaining its qualified profile packages."""

from __future__ import annotations


def project_environment_script(destination: str) -> str:
    """Return setup code executed by the Environment Profile's interpreter."""
    return "; ".join(
        (
            "import pathlib, site, subprocess, venv",
            "profile_packages = site.getsitepackages()[0]",
            f"destination = pathlib.Path({destination!r})",
            "venv.EnvBuilder(system_site_packages=True, with_pip=True).create(destination)",
            "project_packages = subprocess.check_output([str(destination / 'bin/python'), "
            "'-c', 'import site; print(site.getsitepackages()[0])'], text=True).strip()",
            "(pathlib.Path(project_packages) / 'skywright-profile.pth').write_text("
            "'import site; site.addsitedir(' + repr(profile_packages) + ')\\n')",
        )
    )
