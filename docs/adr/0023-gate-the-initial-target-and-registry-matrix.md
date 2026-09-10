---
status: accepted
---

# Gate the initial target and registry matrix by evidence

Skywright initially supports a deliberately finite deployment matrix rather than inheriting every adapter present in SkyPilot. Support attaches to one target and purchase-mode pairing, so a broken spot path can be demoted without making a working on-demand path unavailable.

## Initial target matrix

| Target | First-class scope | Compatible scope |
|---|---|---|
| Local AMD on-prem Kubernetes | Local capacity | — |
| Nebius | On-demand, spot | — |
| RunPod | On-demand, spot | — |
| Vast.ai | On-demand, spot | — |
| Verda | — | On-demand, spot |
| Lambda Cloud | — | On-demand |

This table defines the intended support scope. Entry in the table is not evidence
that a mode has passed its gates; admission requires its applicable qualification
evidence. Unqualified modes remain unavailable, and a failed gate demotes the
affected mode under the policy below.

On 2026-09-10, [#281](https://github.com/Zorro909/skywright/issues/281) found that
the unchanged Vast adapter sends its provider key to the remote container in both
purchase modes. The API-server-only rule initially caused a credential-based
demotion during review. The owner then corrected that rule in ADR 0025: official
SkyPilot adapters may deliver provider credentials wherever their supported path
requires them. That correction supersedes the credential-copy-only demotion;
it does not establish that Vast has passed its live qualification gates.
The original on-demand/spot scope remains required by
[#57](https://github.com/Zorro909/skywright/issues/57), with interruptible selected
first. [#283](https://github.com/Zorro909/skywright/issues/283) owns that
implementation and qualification, including actual launch cost, previous-writer
proof, recovery and cleanup. The [preflight evidence](../research/issue-281-vast-interruptible-preflight.md)
uses synthetic credentials and makes no live cloud qualification claim.

Every other researched provider is deferred. CoreWeave and Together AI remain operator-supplied Kubernetes routes rather than provider-specific Skywright targets. Prime Intellect spot remains deferred because SkyPilot's launch behavior is unverified, and adapters present only in source remain deferred until they meet the same evidence gates as documented adapters.

A **First-class Target** has documented credentials and is release-gated by a real private-image pull, workload launch, terminal cleanup, usage and pricing capture, and — for spot — Managed Jobs recovery under actual interruption. A **Compatible Target** is a finite, operator-configured allowlist entry with documented credentials and purchase modes, an explicit Price Source, and a successful private-image pull plus launch/cleanup smoke test, but carries no release guarantee. A **Deferred Target** is rejected at submission. A failed gate immediately demotes only the affected target/mode; existing Runs remain readable and new submissions fail explicitly rather than silently selecting another target.

SkyPilot's object-storage `MOUNT` support is not an admission criterion because ADR 0008 requires Skywright's own S3 path on every target. Spot correctness assumes no advance warning even where a provider currently offers one. Catalog presence is not price evidence: ADR 0017 still requires an explicit, sufficiently fresh Price Source for every Eligible GPU Offering. A provider whose credentials must reach a new architectural role cannot enter through a matrix edit alone. Adapter-required delivery within SkyPilot's orchestration role is governed by ADR 0025, including its explicit remote-environment trust; it is not by itself a failed credential gate.

## Initial container registry

GitHub Container Registry is the initial OCI registry. Skywright Environment Profiles are public; Training Project Images and their Project Configuration and Metric Contracts are private by default, with public visibility an explicit project choice. CI publishes them, the backend resolves them, and execution targets pull them using the standard Docker-registry authentication shape.

Every artifact referenced by an undeleted Run Record must remain available. Submission resolves every selected image and contract before provisioning and reports registry unavailability directly; a cached target image never substitutes for registry validation. Geographic mirrors and pull-performance tuning are implementation concerns, not architectural requirements.

A replacement registry must support OCI images and arbitrary OCI artifacts by digest, public and private repositories through standard Docker-registry authentication, CI publication, backend resolution, private pulls on every First-class Target, and enforceable retention. Migration copies every live image and contract, verifies identical digests, and only then atomically changes the Training Project's registry binding. The old binding remains authoritative until verification succeeds and the old registry remains available until no reference depends on it. A nonstandard pull protocol or a new credential-consuming role requires a new architectural decision.
