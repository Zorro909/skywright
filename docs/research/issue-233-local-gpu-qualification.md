# Issue 233 local GPU qualification

The managed UI GPU check for [#233](https://github.com/Zorro909/skywright/issues/233)
passed on 2026-09-08, including durable checkpoints and cancellation. This record
covers the isolated setup and failed attempts on September 7, recovery after a
host restart, and the successful Run recorded in the final section.

## Initial published inputs

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

The [project workflow](https://github.com/Zorro909/skywright-ui-qualification/actions/runs/34136240787)
published source `3ab143629356b64a076c209e215aaeaf4aafcfdf` with version artifact
`sha256:36b2f8b264c5a8ba286dc4e90962b587f48a568bb06b9ed79331fe0fb2a568e2`
and project image:

```
ghcr.io/zorro909/skywright-ui-qualification@sha256:b67df449b9f1adb611656a53059d46f05d5057005eafc4d357e00cb71f753651
```

An anonymous pull check succeeded. The backend assessed this exact version as
runnable with no validation failures.

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

## Initial managed UI attempts, 2026-09-07

The production UI rejected an invalid string at `/project/steps` with HTTP 422
and the backend's schema diagnostic. Editing that rejected request created a new
submission identity. The corrected request received HTTP 202 for Run
`cdef8bd0-b135-437a-b1cf-4e5ed9bbebe4`, submission
`e0df0fb6-7e86-4e39-8d46-c9e3b8820362`, at 15:15:10 UTC. Run details displayed its
accepted definition, affirmative root lineage and unavailable execution evidence.
The browser reported no script errors. This attempt did not reach a GPU Training Process.

A separate GraalPy process inside the production backend image reproduced #250:
the Fedora-built cryptography extension requires `OPENSSL_3.2.0`, absent from the
Ubuntu image. The same service-account token successfully initiated a stock SDK
status request under the SkyPilot server's CPython interpreter. The canonical image rebuilt with the unchanged `graalpy-resources` artifact from
[CI run 34136866777](https://github.com/Zorro909/skywright/actions/runs/34136866777)
imports the SDK and initiates an authenticated `sky.jobs.queue_v2` request. Its
cryptography extension requires the available `OPENSSL_3.0.0`. This does not
establish general host-built native-wheel portability or native-context shutdown.

The first Run's claimed dispatch is never relaunched. The UI accepted cancellation
request `62d98230-e759-415a-a0ef-da2c549c5ebf` and continued to distinguish intent
from terminal evidence. A subsequent GPU check uses the explicit Create another
Run action, preserving the original Run and command records.

The rootless kind node initially had a 2,048-process budget, from which systemd
assigned a 307-task default to container scopes. SkyPilot exhausted that limit
and returned `BlockingIOError` / `can't start new thread` for queue requests. The
isolated node now has 16,384 tasks and an explicit per-container default of 2,048.
After restarting the API server, a complete stock SDK queue request returned an
empty job list successfully. These settings belong to this qualification host.

The third UI Run, `ec15e0e8-9cb3-4204-84b9-fef70b7b055f`, reached `/jobs/launch`.
Request `3764aac4-57f7-4134-bb2a-b346ee533a1a` failed before pod creation because
the server image lacked `git`. The subsequent supported `sky check` also required
`socat` and `nc` for Kubernetes port-forward networking. The image now packages
Git, OpenSSH clients, rsync, socat, netcat-openbsd and checksum-pinned kubectl
1.37.0. A production image check exercises repository
creation, SSH key generation, local rsync and kubectl's client version as the
unprivileged service user. This is image packaging; the SkyPilot Python packages
are unchanged.

SkyPilot accepted managed job 2 for UI Run
`e3707b00-aa8c-4be8-8c70-8bee05a15cef` and created a pod requesting
`amd.com/gpu: 1` with the exact published project digest. Setup then failed because
the stock Kubernetes runner unconditionally chmods its installed rsync helper.
The deployment now copies only that helper from the paired image to a 1 MiB memory
volume and mounts it at the expected path. The source and mounted file both hash to
`afc91c2380cc0b2b7648ab93f07b1e62a85a6b2bf27ea7faa8b2814303b76e59`.
The copy belongs to UID/GID 10002, and the rest of the root filesystem remains
read-only. No SDK source bytes change. The same managed job retried automatically
and created another GPU pod after deployment replacement.

The retried pod entered the managed runtime, which refused startup because the
qualification project had omitted its optional Dataset dependencies. The public
project now locks that dependency set while retaining the profile's ROCm torch
and torchvision. A local image imports the MDS reader and project entry point.
The managed job failed before publishing an Execution Attempt or Training Step.

Its setup logs also exposed a journal admission conflict. ADR 0018 permits
backend-owned `v1/skypilot/logs/` objects before training begins. Both the SDK
recorder and backend lifecycle reader now exclude only that namespace when
establishing an empty training history. Unknown records still reject admission;
inventory reads stop after 64 pages of at most 256 keys, and incomplete or
nonadvancing reads remain unavailable. Regression coverage includes 600 setup
logs, orphan records after those logs, stalled cursors and oversized pages.

The deployed backend with the log-admission fix subsequently reported managed
job 2 as terminal `failed`, with live SkyPilot and Run Store reads, zero Execution
Attempts and absent committed progress. The UI retained its affirmative root
lineage and reported no browser errors.

The replacement profile is public and anonymously readable at
`ghcr.io/zorro909/skywright-ui-qualification-profile@sha256:ec1c407778ddad7f8829e978c69a5e534d758f9802a7fbdac4457a980dbfa10c`.
It builds SDK source `6df8f1ba8091e2ca09166e25210166b2e7d85d3f`; the final
qualification project build pins this digest and the locked Dataset dependencies.
The control plane runs backend image `skywright-backend:issue233-prestart-6df8f1b`
and server image `skywright-skypilot-api-server:issue233-kubectl137`.
The latter passes all seven production image checks. Its kubectl update addresses
the dependency thresholds recorded in the [security research](issue-233-kubectl-security-update.md);
the current CI scan must still pass.

## Process reaping blocker

The final public project build
[34143376509](https://github.com/Zorro909/skywright-ui-qualification/actions/runs/34143376509)
passed in 14m15s. Version artifact
`sha256:012248f1c6cbad3b0ca713974f6f5557efb43118cd8f31d7dbfbf6410c0d4eba`
is runnable; its anonymously verified ROCm image is
`sha256:b5c5e637e370dc69d99102257a2facd12d436fa285cf7fd839f4cf059a32d107`.
The UI accepted Run `9b8575bf-fdb0-4aa9-9297-428850ef5228`, submission
`86db41a5-739c-47bc-89a0-c2ce6ac6e6e2`, at 16:42:21 UTC.

Before creating a GPU pod, the server container exhausted its 2,048-task scope.
The enclosing node used only 3,177 of its 16,384 tasks. Raising just the server's
runtime scope to 8,192 allowed inspection: 1,777 processes were zombies with
parent PID 1. SkyPilot runs directly as PID 1 and does not reap those adopted
children. Increasing the task limit alone does not resolve that accumulation.
A stock SDK read found no launch request for this Run; its claimed dispatch is
not replayed. No GPU Training Step was published.

The owner approved tini as PID 1 on 2026-09-08. #71, #199 and ADR 0009 now
record that replacement. Tini reaps orphaned children and forwards SIGTERM to
the unchanged supported server entry point. Fixed non-root execution, port
46580, read-only-root operation and the bounded shutdown/descendant checks
remain required. A packaged regression creates twenty orphaned children; the
old image left all twenty unreaped.

The UI accepted cancellation `34e0f9d5-9417-4f67-a8a7-e4c13d03c616`
for that unstarted Run at 16:47:21 UTC. The receipt was `accepted`; it did not
establish terminal execution evidence.

The replacement image passed all eight packaged-process checks, including the
new orphan-reaping regression and the existing SIGTERM-only test requiring
PID 1 and recorded server descendants to exit within 25 seconds. Standards
and specification review found no blocking issues. The GPU check remains
outstanding while the isolated environment is restored after a host restart.

## Managed GPU UI qualification, 2026-09-08

The restored deployment uses the committed server image
`skywright-skypilot-api-server:issue233-tini-33c114d`. A first GPU Run
`55af4636-54d1-48a5-a6eb-b321db83bad2` executed Step 1 on the RX 7900 XTX,
then failed because the synthetic project's `time.sleep` call received an exact
Decimal value. The qualification project now converts numeric configuration
explicitly; publication of that correction is separate from the application PR.

The existing published version also accepts integer zero for its delay. Through
the UI's explicit Create another Run action, the check selected that version,
the retained Dataset Definition, 10,000 Steps, zero delay, checkpoint cadence 10
and retention 3. Invalid string-valued Steps were rejected with HTTP 422 before
the corrected request was accepted.

Run `2d20f270-05b3-473c-a2ee-5a098f144b5e`, submission
`1439e6b5-9e63-49d4-a43b-f359a163d99a`, was accepted at 12:19:29 UTC.
SkyPilot managed job 4 created a pod requesting one `amd.com/gpu`, CPU 4 and
8 GB memory with image
`ghcr.io/zorro909/skywright-ui-qualification@sha256:b5c5e637e370dc69d99102257a2facd12d436fa285cf7fd839f4cf059a32d107`.
The project reported `AMD Radeon RX 7900 XTX`, ROCm HIP `7.14.60850`, and
25,753,026,560 device-memory bytes, then executed finite-loss forward/backward
training Steps over the published Dataset.

The UI observed committed Step 1,106 and Durable Safe Point 1,100 before requesting
cancellation. Receipt `aa7584e2-23cf-48da-97e4-c292f63d7b7a` was accepted at
12:20:32 UTC. Its cooperative request and stop delivery were recorded at
12:20:39 UTC; the receipt subsequently reported `effect-observed`.

The runtime's termination report records `cancelled`, one Execution Attempt,
last committed Step 5,590 and latest Durable Safe Point 5,580. Its checkpoint is
`skywright-checkpoint:v1:5580:sha256:7ba629b0630153a89d9e8ce24a234c9311c00d0ee0e4be3ca8177ff3c16e3f8d`.
A fresh Run-detail read shows authoritative `cancelled` with live SkyPilot and
Run Store evidence, affirmative root lineage and disabled new cancellation.
SkyPilot itself reports `FAILED`; the Run lifecycle correctly uses the runtime's
cancellation evidence. The UI keeps its source-fact conflict and database-scoped
job-identity diagnostics visible. No browser script errors were reported.

The complete UI submission, GPU training, durable checkpoint and cancellation
qualification for #233 has passed. Private diagnostics, browser screenshots and
API observations are retained in the persistent qualification session directory.
