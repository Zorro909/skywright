---
status: accepted
---

# Pin runs to CI-built project image digests

A Run Definition references the code it executes as an immutable container image digest, one per accelerator backend, produced only by the Training Project's CI. Skywright owns a base image per backend — the Environment Profile — and a project's CI builds a thin image on top of it carrying the project's source, its locked dependencies, and its Project Configuration Contract. Neither of the obvious single answers works: a git commit says nothing about the environment and can be rewritten or unpushed, while a single image digest cannot name the code at all, because `T4` demands identical training code on ROCm and CUDA and `docs/research/pytorch-rocm-cuda-image.md` established those are two mutually exclusive images.

## Identity

A Training Project has a Skywright-owned identity; its registry repository is an attribute of that identity, so a change of registry does not create a new project. A Training Project Version is identified by that project plus a `<commit-sha>-<pipeline>` label and resolves to one Training Project Image digest per accelerator backend it declares. The label is provenance and the digest identifies — a tag can be repointed, a digest cannot. Because a project commits the base image reference it builds against, changing the base is itself a source change; the pipeline number therefore only distinguishes rebuilds of one commit. The base image digest is recorded as provenance on the version, so two builds of one commit can be told apart without archaeology.

## Build and publication

CI is the only permitted builder. There is no local build path, so every Run traces to a commit and an uncommitted working tree cannot reach the Run system at all — running the training script directly (`B3`) stays outside it and never acquires a run identity. The base image is library-owned and shared, which keeps the multi-gigabyte layers cached and the per-commit layer small; the project's lock file may not replace the Skywright library that base carries, and the CI build fails if it would. The Project Configuration Contract is published as an OCI artifact in the same registry, addressed deterministically from the image digest it describes. A version is runnable only when every backend image it declares and its contract both resolve; anything less is reported as not-runnable with the missing piece named.

## Resolution and failure

A Run Definition pins the whole digest map rather than one image, because `T1` and `T3` express a target as capabilities and the accelerator vendor is not known until dispatch. SkyPilot selects `image_id` per candidate, and the digest actually used derives from the infrastructure it selected (ADR 0005). A version may declare a subset of backends; submission fails validation when the requested capabilities could only be met by a backend that version was not built for, which raises `T6`'s explicit failure before anything provisions. The backend enumerates registry tags on demand for display and resolves the digest and contract only for the version selected, caching both with their age and reporting an unreachable registry as unreachable rather than serving stale tags. Cloning verifies resolvability first and fails with a reason rather than dying on the instance.

## Considered options

Registering each version with Skywright from CI was rejected because Skywright is not reachable from CI servers, which forces a pull model. A Maven repository or release assets for the Project Configuration Contract were rejected because the tie from their coordinate to an image digest is convention only, and a contract that can drift from the image it describes defeats the version binding ADR 0002 required. Building a full per-project image without a shared base was rejected on cost: the base layers are 3–13 GB (CUDA) and 10–19 GB (ROCm) compressed. Installing project dependencies at run start instead of at build time was rejected because it moves a failure that belongs in CI onto a paid GPU instance. Allowing local builds beside CI was considered and rejected: the digest keeps `R1` and `R5` intact however an image was built, but the traceability to source does not survive it.

## Consequences

A green pipeline becomes the precondition for every Run, including on the local box, so the fast inner loop is direct script execution (`B3`) and smoke mode (`T5`) rather than a shortened Run. Local targets pull from a remote registry; this is a start-time dependency satisfied from cache on repeat, not the runtime cloud coupling `K5` rules out. Skywright cannot enforce registry retention — a pruned digest breaks a clone — so retention is a documented registry policy covering any version a Run Record references, and Skywright's guarantee is only that the failure is explicit.
