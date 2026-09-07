# Issue 233 local GPU qualification

Qualification date: 2026-09-07. This records the isolated environment prepared for
[#233](https://github.com/Zorro909/skywright/issues/233). The managed UI GPU check
is still pending. The device smoke test below does not satisfy that check.

## Published inputs

The owner authorized creation of the public
[qualification repository](https://github.com/Zorro909/skywright-ui-qualification)
and publication of its synthetic project and ROCm profile. The project uses
`skywright_project.train(context)`, registers a GPU model and optimizer, and
performs forward/backward training steps against a synthetic MDS Dataset.

The [profile workflow](https://github.com/Zorro909/skywright-ui-qualification/actions/runs/34131901464)
built Skywright revision `906b84d17a20bf114db2cf6601fff30be08e974d` using the
repository's ROCm Containerfile. Its anonymous GHCR pull identity is:

```
ghcr.io/zorro909/skywright-ui-qualification-profile@sha256:e884b9a7cf6c06926cad50f97f7983dfea1e5ef040dd7dae9a686c45c068681a
```

The Dataset publisher submitted the repository's five-file, 3,478-byte synthetic
MDS fixture through the installed SDK CLI and production backend. Publication
`331b5728-667d-4ce3-bb13-1dded25d09c6` committed Definition
`277d6ea8-dd9e-4077-88b7-83e1fde5856b` with content fingerprint
`sha256:b0445d45a11a3ed0d9dc6278e3efebb7253fd0f767f67aa2770d367a905a0917`.
The UI listed this exact Definition. No catalogue rows were inserted manually.

## Isolation and measured boundaries

- Dedicated rootless Podman kind cluster `skywright-issue233`, Kubernetes 1.36.1,
  explicit private kubeconfig, independent pod/service address ranges. The
  pre-existing stopped kind cluster was untouched.
- Calico 3.32.2 enforces NetworkPolicy. An allowed client reached the test service;
  an otherwise identical denied client timed out.
- The stock AMD device plugin advertises one discrete GPU. The node reports
  `amd.com/gpu: 1` and `skypilot.co/accelerator=rx7900xtx`.
- A pod requesting that resource performed a 1024-by-1024 matrix multiplication,
  loss calculation and backward pass. PyTorch reported AMD Radeon RX 7900 XTX,
  25,753,026,560 bytes, HIP 7.14.60850 and PyTorch 2.12.0+rocm7.14.0. The loss and
  gradients were finite.
- This host also requires the integrated GPU's `/dev/dri/renderD129` for ROCr
  discovery. Mounting only the discrete render device failed initialization. The
  qualified pod includes the ancillary render device and sets
  `ROCR_VISIBLE_DEVICES=0`; PyTorch sees exactly one compute device. SkyPilot's
  existing global pod configuration carries this host-specific exception. It
  is not a general AMD deployment claim.
- Vault uses TLS and persistent storage. Distinct scoped tokens expose only the
  backend's exact binding paths or the Agent's Kubernetes path. Agent renders
  pinned revision 1 and its token is removed before SkyPilot starts. The backend
  trusts the qualification CA through a private JVM truststore.
- Dataset and Run buckets use distinct role identities. Actual checks rejected
  cross-bucket access and writes from read-only identities. Both resources passed
  backend qualification, were activated and assigned as the local defaults.
- The Kubernetes provisioner and worker identities have namespace writes in
  `skywright-training` and the required cluster reads. They cannot read Secrets
  in `skywright` or create cluster role bindings.
- SkyPilot 0.13.0 uses its supported service-account and workspace configuration
  APIs. No SkyPilot server or client SDK source was modified.

## Deployment defects reproduced and fixed

The original PostgreSQL RollingUpdate allowed two processes to use one PVC.
Stopping the old pod removed the shared postmaster PID file and caused the new
server to crash. The local overlay now uses Recreate. Replacing the live database
pod retained both Target Storage registrations; the recorded maximum number of
concurrent database pods was one. PostgreSQL readiness now waits on TCP so the
image's temporary initialization server cannot signal readiness prematurely.
The packaged browser fixture uses the same TCP readiness boundary.

A Dataset identity trigger initially failed because runtime JDBC connections had
no `skywright` search path. The integration fixture had supplied `currentSchema`
itself, hiding the deployed failure. The application now sets the Hikari schema,
and the fixture uses its ordinary JDBC URL. Resuming the original publication
then committed its verified Dataset successfully.

The first public project build also failed to import Skywright after creating a
nested project virtual environment. Python does not inherit the enclosing
virtual environment's site packages through `--system-site-packages`. The Action
now adds the qualified profile directory through `site.addsitedir`, including its
`.pth` files. A subprocess regression and the real ROCm profile both import the
profile packages from the project interpreter successfully. The public workflow
pins Action revision `1f530785e14e38c3f85fc6c0384f40342057551a`.

The published project image is anonymously readable. Live GHCR also exposed three
reader incompatibilities: public repositories require an anonymous pull bearer token,
version artifacts use the publisher's content-addressed tags and provenance
annotations, and blob downloads redirect to GitHub's CDN. The registry reader now
handles these forms, bounds requests and strips registry authorization from signed
blob redirects. The qualification project definition uses its registered Project
UUID, `e23aac30-f005-4c5b-8ea1-be6770f8c54e`.

Private initialization material and raw logs remain outside the repository.
