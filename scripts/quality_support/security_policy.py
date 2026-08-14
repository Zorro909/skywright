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


@dataclass(frozen=True)
class DismissalEvidence:
    issue: str
    owner: str
    decision: str
    expires: date
    scope: str


def _request_json(url: str) -> object:
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


def _validate_issue_evidence(
    *,
    identifier: str,
    issue_url: str,
    owner: str,
    decision: str,
    issue_cache: dict[str, dict[str, object]],
) -> None:
    issue = issue_cache.get(issue_url)
    if issue is None:
        issue_number = issue_url.rsplit("/", maxsplit=1)[-1]
        issue = _request_json(
            f"https://api.github.com/repos/Zorro909/skywright/issues/{issue_number}"
        )
        if not isinstance(issue, dict):
            raise SecurityPolicyError(f"{identifier} issue response is invalid")
    body = str(issue.get("body") or "")
    if issue.get("state") != "open":
        raise SecurityPolicyError(f"{identifier} issue must exist and remain open")
    fields = {
        match.group("key").lower(): match.group("value").strip()
        for match in re.finditer(
            r"^(?P<key>Owner|Decision):\s*(?P<value>.+)$", body, re.MULTILINE
        )
    }
    if fields.get("owner") != owner or fields.get("decision") != decision:
        raise SecurityPolicyError(
            f"{identifier} issue must contain exact Owner: and Decision: fields"
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
            _validate_issue_evidence(
                identifier=f"suppression {suppression.identifier}",
                issue_url=suppression.issue,
                owner=suppression.owner,
                decision=suppression.decision,
                issue_cache=issue_cache or {},
            )
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
            if not isinstance(advisory, dict):
                raise SecurityPolicyError(f"advisory {identifier} response is invalid")
            if dependency_has_fix(
                advisory, change.get("ecosystem", ""), change.get("name", "")
            ):
                blocked = True
                print(f"BLOCKED {identifier} ({severity}, available fix)")
            else:
                print(f"VISIBLE {identifier} (no available fix)")
    return 1 if blocked else 0


def _load_alerts(path: Path | None, endpoint: str) -> list[dict[str, object]]:
    if path:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, list):
            raise SecurityPolicyError(f"alert fixture must be a JSON array: {path}")
        return value
    separator = "&" if "?" in endpoint else "?"
    value = _request_json(f"https://api.github.com{endpoint}{separator}per_page=100")
    if not isinstance(value, list):
        raise SecurityPolicyError(f"alert response must be a JSON array: {endpoint}")
    if len(value) == 100:
        raise SecurityPolicyError(
            f"alert response reached the pagination limit and cannot be audited: {endpoint}"
        )
    return value


def _dismissal_evidence(comment: object, identifier: str) -> DismissalEvidence:
    if not isinstance(comment, str):
        raise SecurityPolicyError(
            f"{identifier} dismissal requires structured evidence"
        )
    evidence = {
        match.group("key").lower(): match.group("value").strip()
        for match in re.finditer(
            r"^(?P<key>Issue|Owner|Decision|Expires|Scope):\s*(?P<value>.+)$",
            comment,
            re.MULTILINE,
        )
    }
    missing = {"issue", "owner", "decision", "expires", "scope"}.difference(evidence)
    if missing:
        raise SecurityPolicyError(
            f"{identifier} dismissal is missing: {', '.join(sorted(missing))}"
        )
    if not re.fullmatch(
        r"https://github\.com/Zorro909/skywright/issues/[1-9][0-9]*",
        evidence["issue"],
    ):
        raise SecurityPolicyError(f"{identifier} dismissal issue is invalid")
    if any(character in evidence["scope"] for character in "*?"):
        raise SecurityPolicyError(f"{identifier} dismissal scope must be exact")
    try:
        expiry = date.fromisoformat(evidence["expires"])
    except ValueError as error:
        raise SecurityPolicyError(
            f"{identifier} dismissal expiry is invalid"
        ) from error
    today = datetime.now(tz=timezone.utc).date()
    if expiry <= today or expiry > today + timedelta(days=90):
        raise SecurityPolicyError(
            f"{identifier} dismissal expiry must be within the next 90 days"
        )
    return DismissalEvidence(
        issue=evidence["issue"],
        owner=evidence["owner"],
        decision=evidence["decision"],
        expires=expiry,
        scope=evidence["scope"],
    )


def _audit_dismissal(
    alert: dict[str, object],
    *,
    identifier: str,
    comment_field: str,
    expected_scope: object,
    scope_description: str,
    issue_cache: dict[str, dict[str, object]],
) -> None:
    evidence = _dismissal_evidence(alert.get(comment_field), identifier)
    if evidence.scope != expected_scope:
        raise SecurityPolicyError(
            f"{identifier} dismissal scope does not match its {scope_description}"
        )
    _validate_issue_evidence(
        identifier=identifier,
        issue_url=evidence.issue,
        owner=evidence.owner,
        decision=evidence.decision,
        issue_cache=issue_cache,
    )


def enforce_github_dismissal_policy(
    *,
    scanner: str = "all",
    code_alerts_path: Path | None = None,
    secret_alerts_path: Path | None = None,
    issues_path: Path | None = None,
) -> None:
    code_alerts = (
        _load_alerts(
            code_alerts_path,
            "/repos/Zorro909/skywright/code-scanning/alerts?state=dismissed",
        )
        if scanner in {"all", "codeql"}
        else []
    )
    secret_alerts = (
        _load_alerts(
            secret_alerts_path,
            "/repos/Zorro909/skywright/secret-scanning/alerts?state=resolved",
        )
        if scanner in {"all", "secret-scanning"}
        else []
    )
    issue_cache = (
        json.loads(issues_path.read_text(encoding="utf-8")) if issues_path else {}
    )
    for alert in code_alerts:
        identifier = f"CodeQL alert {alert.get('number', 'unknown')}"
        expected_scope = (
            alert.get("most_recent_instance", {}).get("location", {}).get("path")
        )
        _audit_dismissal(
            alert,
            identifier=identifier,
            comment_field="dismissed_comment",
            expected_scope=expected_scope,
            scope_description="path",
            issue_cache=issue_cache,
        )
    for alert in secret_alerts:
        if alert.get("resolution") == "revoked":
            continue
        identifier = f"secret-scanning alert {alert.get('number', 'unknown')}"
        _audit_dismissal(
            alert,
            identifier=identifier,
            comment_field="resolution_comment",
            expected_scope=alert.get("locations_url"),
            scope_description="locations",
            issue_cache=issue_cache,
        )
