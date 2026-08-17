# Environment Profile definitions

These two definitions are the library-owned CUDA and ROCm bases for Training Project Images. Both
carry the same Skywright source and PyTorch release, while their mutually exclusive accelerator
runtimes remain in separate digest-pinned images. `manifest.json` is the machine-readable source
of their compatibility and base-image provenance.

Profile images are not Training Project Versions. The coordinated qualification, release version,
attestations, SBOMs, and public GHCR publication are intentionally owned by issue #119. Live
accelerator behavior and private-pull qualification remain owned by issue #57.

Training Projects consume released profile references by digest in `skywright-project.json`. Their
own CI invokes the repository's reusable `publish-training-project` GitHub Action to build thin
images without replacing the Skywright installation supplied here.
