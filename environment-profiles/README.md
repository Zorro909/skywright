# Environment Profile definitions

These two definitions are the library-owned CUDA and ROCm bases for Training Project Images. Both
carry the same Skywright source and PyTorch release, while their mutually exclusive accelerator
runtimes remain in separate digest-pinned images. `manifest.json` is the machine-readable source
of their compatibility and base-image provenance.

Profile images are not Training Project Versions. The coordinated qualification, release version,
attestations, SBOMs, and public GHCR publication are intentionally owned by issue #119. Live
accelerator behavior and private-pull qualification remain owned by issue #57.

## Qualification

`scripts/check` builds both definitions on Linux/amd64 without passing through an accelerator
device. It exercises `skywright-runtime --help` and `--version`, then verifies the installed SDK
and PyTorch facts together with the image's accelerator runtime, base image, architecture, and
source-revision labels. The repository quality plan exposes this as the first-class `profile`
check; profile changes and SDK source changes select it.

## Coordinated releases

`VERSION` is the independent Environment Profile Semantic Version and `CHANGELOG.md` carries its
release notes. Before 1.0, an incompatible profile-contract change increments the minor version;
compatible dependency refreshes and qualification fixes increment the patch. After 1.0, Semantic
Versioning's normal major/minor/patch compatibility rules apply.

Only a stable `profile-vMAJOR.MINOR.PATCH` tag reachable from `main` starts publication. The
protected `environment-profile-release` environment must restrict deployment to those tags. The
workflow uses only its short-lived GitHub identity and publishes these two tags—no mutable aliases
and no combined index:

- `ghcr.io/zorro909/skywright-environment:<version>-cuda`
- `ghcr.io/zorro909/skywright-environment:<version>-rocm`

The GHCR package must be public and retain published versions indefinitely; the workflow verifies
anonymous digest reads before making a release discoverable. It rejects a tag whose existing
digest differs and repairs a missing destination whose other content still agrees. Qualified,
unpublished workflow artifacts are retained for 30 days and are never publication authority.

GitHub creates a new personal-account container package as private and does not expose a
`GITHUB_TOKEN` API for changing that visibility. On the first publication only, the workflow stops
after pushing the qualified immutable images if the package is still private. A package
administrator makes the package public in GitHub's package settings and reruns the same workflow;
the rerun pulls and requalifies the already-published digests before completing the release. This
bootstrap needs no long-lived publishing credential.

The authoritative discovery document is the GitHub Release asset
`environment-profile-release-manifest.json` with media type
`application/vnd.skywright.environment-profile.release.v1+json`. It binds both image digests to
compatibility, source, workflow, SPDX JSON SBOM, and provenance facts. `SHA256SUMS` covers the
retained release evidence. Consumers must select one backend and pin its
`ghcr.io/zorro909/skywright-environment@sha256:...` reference from that manifest; version tags are
human-facing publication coordinates, not deployment pins.

Profile releases publish neither Training Project Versions nor backend deployment artifacts.

Training Projects consume released profile references by digest in `skywright-project.json`. Their
own CI invokes the repository's reusable `publish-training-project` GitHub Action to build thin
images without replacing the Skywright installation supplied here.
