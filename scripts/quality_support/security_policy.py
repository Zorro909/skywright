"""Validated security suppressions and dependency-finding enforcement."""

from __future__ import annotations

import json
import os
import re
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


class SecurityPolicyError(RuntimeError):
    """Security policy input or external evidence is invalid or unavailable."""


@dataclass(frozen=True)
class Suppression:
    identifier: str
    manifest: str
    package_url: str
    issue: str
    expires: date
    reason: str
    owner: str
    decision: str

    def applies_to(self, change: dict[str, object], identifier: str) -> bool:
        package_url = str(change.get("package_url", ""))
        return (
            self.identifier.lower() == identifier.lower()
            and self.manifest == change.get("manifest")
            and (
                package_url == self.package_url
                or package_url.startswith(f"{self.package_url}@")
            )
        )


def _request_json(url: str) -> dict[str, object]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "skywright-quality-gate",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except (OSError, json.JSONDecodeError) as error:
        raise SecurityPolicyError(
            f"could not resolve security evidence: {url}"
        ) from error


def _validate_issue(
    suppression: Suppression, issue_cache: dict[str, dict[str, object]]
) -> None:
    issue = issue_cache.get(suppression.issue)
    if issue is None:
        issue_number = suppression.issue.rsplit("/", maxsplit=1)[-1]
        issue = _request_json(
            f"https://api.github.com/repos/Zorro909/skywright/issues/{issue_number}"
        )
    body = str(issue.get("body") or "")
    if issue.get("state") != "open":
        raise SecurityPolicyError(
            f"suppression {suppression.identifier} issue must exist and remain open"
        )
    if suppression.owner not in body or suppression.decision not in body:
        raise SecurityPolicyError(
            f"suppression {suppression.identifier} issue must contain its owner and decision"
        )


def load_suppressions(
    path: Path,
    *,
    verify_issues: bool = False,
    issue_cache: dict[str, dict[str, object]] | None = None,
) -> list[Suppression]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        if document["version"] != 1 or not isinstance(document["suppressions"], list):
            raise ValueError
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        raise SecurityPolicyError(
            f"invalid security suppression document: {path}"
        ) from error

    today = datetime.now(tz=timezone.utc).date()
    latest_expiry = today + timedelta(days=90)
    validated: list[Suppression] = []
    fields = (
        "id",
        "manifest",
        "package_url",
        "issue",
        "expires",
        "reason",
        "owner",
        "decision",
    )
    for index, candidate in enumerate(document["suppressions"]):
        if not isinstance(candidate, dict) or any(
            not isinstance(candidate.get(field), str) or not candidate[field].strip()
            for field in fields
        ):
            raise SecurityPolicyError(
                f"suppression {index} requires non-empty {', '.join(fields)} fields"
            )
        values = {field: candidate[field].strip() for field in fields}
        if not re.fullmatch(
            r"GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}",
            values["id"],
            re.IGNORECASE,
        ):
            raise SecurityPolicyError(
                f"suppression {index} id must be one GHSA identifier"
            )
        if any(character in values["manifest"] for character in "*?"):
            raise SecurityPolicyError(
                f"suppression {index} manifest must be one exact path"
            )
        if not values["package_url"].startswith("pkg:") or any(
            character in values["package_url"] for character in "*?"
        ):
            raise SecurityPolicyError(
                f"suppression {index} package_url must be one exact unversioned package URL"
            )
        if not re.fullmatch(
            r"https://github\.com/Zorro909/skywright/issues/[1-9][0-9]*",
            values["issue"],
        ):
            raise SecurityPolicyError(
                f"suppression {index} issue must link one skywright GitHub issue"
            )
        try:
            expiry = date.fromisoformat(values["expires"])
        except ValueError as error:
            raise SecurityPolicyError(
                f"suppression {index} expires must be an ISO date"
            ) from error
        if expiry <= today or expiry > latest_expiry:
            raise SecurityPolicyError(
                f"suppression {index} expires must be within the next 90 days"
            )
        suppression = Suppression(
            identifier=values["id"],
            manifest=values["manifest"],
            package_url=values["package_url"],
            issue=values["issue"],
            expires=expiry,
            reason=values["reason"],
            owner=values["owner"],
            decision=values["decision"],
        )
        if verify_issues:
            _validate_issue(suppression, issue_cache or {})
        validated.append(suppression)
    return validated


def dependency_has_fix(
    advisory: dict[str, object], ecosystem: str, package_name: str
) -> bool:
    return any(
        vulnerability.get("package", {}).get("ecosystem", "").lower()
        == ecosystem.lower()
        and vulnerability.get("package", {}).get("name") == package_name
        and vulnerability.get("first_patched_version") is not None
        for vulnerability in advisory.get("vulnerabilities", [])
    )


def enforce_dependency_policy(
    changes_json: str,
    policy_path: Path,
    *,
    advisories_path: Path | None = None,
    report_path: Path | None = None,
) -> int:
    try:
        changes = json.loads(changes_json or "[]")
        if not isinstance(changes, list):
            raise TypeError
    except (json.JSONDecodeError, TypeError) as error:
        raise SecurityPolicyError("dependency changes must be a JSON array") from error
    suppressions = load_suppressions(policy_path)
    advisory_cache = (
        json.loads(advisories_path.read_text(encoding="utf-8"))
        if advisories_path
        else {}
    )
    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            f"{json.dumps(changes, indent=2, sort_keys=True)}\n", encoding="utf-8"
        )

    blocked = False
    for change in changes:
        for vulnerability in change.get("vulnerabilities", []):
            severity = vulnerability.get("severity", "").lower()
            if severity not in {"high", "critical"}:
                continue
            identifier = vulnerability.get("advisory_ghsa_id", "")
            if any(
                suppression.applies_to(change, identifier)
                for suppression in suppressions
            ):
                print(f"SUPPRESSED {identifier} ({change.get('name')})")
                continue
            advisory = advisory_cache.get(identifier) or _request_json(
                f"https://api.github.com/advisories/{identifier}"
            )
            if dependency_has_fix(
                advisory, change.get("ecosystem", ""), change.get("name", "")
            ):
                blocked = True
                print(f"BLOCKED {identifier} ({severity}, available fix)")
            else:
                print(f"VISIBLE {identifier} (no available fix)")
    return 1 if blocked else 0
