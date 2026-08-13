# skywright

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `Zorro909/skywright`, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

When creating a pull request, use `.github/pull_request_template.md` for its description.

### Triage labels

The five canonical triage roles map to labels of the same name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Testing

Test actual application code with unit tests and system tests.

## Specification authority

Treat issue acceptance criteria and accepted ADRs as canonical for implementation.

When later user direction or PR feedback contradicts them, first make the decision canonical:
update the issue and, when the decision is architectural, its ADR. Ask one focused question when
the authority or intended replacement is unclear.

Implement and review against the updated sources. Do not close an issue while knowingly leaving
its recorded acceptance criteria unmet.
