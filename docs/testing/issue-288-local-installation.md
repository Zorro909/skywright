# Local installation qualification

Work for [#288](https://github.com/Zorro909/skywright/issues/288). Qualification
is in progress. No installed package version or completed installation smoke
is claimed by this record yet.

## Host inventory

Read-only inspection on 2026-09-11 found Ubuntu 24.04.4 LTS, kernel
6.8.0-139-generic, Docker 29.7.1, about 62 GiB RAM and 612 GiB free on the root
filesystem. SSH uses the operator's existing agent identity.

The retained `skywright-onprem` kind cluster has one Ready node,
`skywright-onprem-control-plane`, running Kubernetes 1.36.1 and containerd 2.3.1.
The AMD device plugin advertises two `amd.com/gpu` resources and the accelerator
label `rx7800xt`. Both PCI devices report AMD vendor `1002`, device `747e`,
and 17,163,091,968 bytes of VRAM each.

At the initial inspection, both GPUs were 96–97% busy outside Kubernetes.
The cluster had no training Pods. Kubernetes allocatable capacity therefore
does not establish that a GPU is idle. Existing Ollama and other host services
have not been stopped or reconfigured.

## Credential inputs

The operator authorized copying the two distinct registry credentials from the
originating development Vault. The resolver and image-pull credentials were
read from their separate, pinned Vault entries, checked for distinctness and
saved as mode-0600 files. Both successfully read the qualified private project
image manifest from GHCR. This verifies registry access, not a target runtime pull.

Operator-owned input locations on the destination host are:

- `~/.local/share/skywright/issue288/operator-secrets/ghcr-resolver.json`
- `~/.local/share/skywright/issue288/operator-secrets/ghcr-pull.json`

The same protected input files remain on the originating host. Values were not
printed or committed. Installation must enroll these inputs into its own Vault;
these files do not establish that the new instance has a Credential Authority.

## Application verification so far

- The existing deployment contract suite passed 68 tests, with two root-only
  custody checks skipped by that host invocation.
- `LocalRunAssemblyIT` passed against PostgreSQL and S3 with output listing,
  verified HTTP download, cross-Run rejection and corrupt-content rejection.
- The browser output-viewer test passed for an Artifact download through the
  application.

Install, retained-state update, control-plane restart, GPU smoke, private
reachability and backup/recovery qualification remain pending.

## Packaged prerequisites prepared

A separate `kind-skywright-local` cluster was created for this qualification.
The earlier `skywright-onprem` cluster remains untouched. The new node uses
Calico, advertises both AMD GPUs, and has a 12-CPU / 32-GiB Docker ceiling.
Persistent TLS Vault and SeaweedFS were prepared there. No backend or SkyPilot release
has been installed and no training workload has been submitted yet.

The generated operator inputs, Vault recovery material and TLS files live under
`~/.local/share/skywright/issue288/operator-secrets/`. The dedicated state root is
`~/.local/share/skywright/issue288/instance/`. These are protected directories;
no secret values are recorded here.

Live SeaweedFS authorization was checked after applying project-scoped roles.
The Training Process credential could write and read its project's prefix, but
received HTTP 403 for another project's reads, writes and listings. The
uninstalled Metric View identity received HTTP 403 for object access. Probe
objects were deleted afterward. Prefix permissions follow the pinned provider's
[authorization implementation](https://github.com/seaweedfs/seaweedfs/blob/4.42/weed/s3api/auth_credentials.go).

The SDK verification passed 468 unit tests and 20 installed-wheel system tests.
The deployment suite passed 75 tests with three skips: two root-only checks and
the opt-in signed-release AMD system qualification. The maintenance HTTP test
passed new-submission rejection, accepted-intent replay across restart and the
GUI form's maintenance response. The full reactor is still being verified.

After the operator rebooted the host, both GPUs resumed through a brief render
device open and settled at 0% utilization with about 175 MiB allocated each.
Vault was unsealed using its retained recovery input. No training was launched.
The driver's utilization counter returns `EBUSY` during normal runtime suspension.
An opt-in deployment CLI regression failed on the suspended host before the fix
and passed afterward. Inventory now accepts that response only when the kernel
reports `suspended` both before and after the counter read; other errors still
block admission. This check does not wake the GPUs or change their power policy.

Reactor verification subsequently passed in stages, including all 20 backend,
Dataset-publication and SkyPilot container-image tests. Repository Quality run
`34651202834` passed for source `682c78a`. Release publication then exposed a
missing `uv` prerequisite in the release runner; the workflow now uses the same
pinned Python setup as image CI. No release was published by those failed runs.

The retained Vault now contains the installation's version-one credential inputs
and exact consumer policies. Live checks allowed each consumer's selected reads
and returned HTTP 403 for the other consumer's paths. A maintenance CLI probe
failed to advance the host observation with `vault token renew -self`, which the
pinned CLI rejects. It passed after using the supported `vault token renew`
self-renewal command. The fresh-installation test also requires the private GUI
endpoint to serve a ready Managed Run form, covering maintenance startup.

A temporary CPU-only Pod exercised the packaged SkyPilot credential init sequence
as UID 10002. It verified a readable, mode-0400 kubeconfig and removal of the
bootstrap token. The Pod was deleted afterward. The backend, SkyPilot service,
Dataset and writer are still awaiting the first complete signed installation.

The preparatory cluster was stopped after the credential, storage, PostgreSQL
bootstrap and private-image pull probes. The fresh installation will use
`kind-skywright-private`, state root
`~/.local/share/skywright/issue288/private-instance/`, and operator input root
`~/.local/share/skywright/issue288/private-secrets/`. At 2026-09-11 23:21 UTC,
neither that cluster nor its state directory existed. The input directory
contained only the two protected GHCR files, and loopback port 8080 was free.

Repository Quality run `34656792170` passed for source `b030110` after one
production-image SDK process exceeded its two-minute wait and passed on retry.
Release run `34660304626` built and published the control-plane images, but
bundle creation exposed two release-contract gaps. Newly built Skaffold image
references included tags before their digests, and the bundle validator still
expected the older SkyPilot container layout. The workflow now validates and
publishes the same canonical digest references. The local-bundle CLI test now
uses the actual production manifests and covers their helper/sidecar layout and
Kustomize's sequence indentation.

Signed prerelease `v0.1.0-issue288.1`, source `d2cc31f`, was subsequently published
by run `34664959165` and installed on `kind-skywright-private`. Its bundle digest is
`sha256:65072093c89fbb641029332a09c5af5e1663be43b683b8410d18aba119f4021b`.
The first installation attempt stopped during control-plane setup. All service
Pods became healthy, and retrying the retained setup passed the installation,
preflight and private GUI readiness test in 919.5 seconds. The CPU-only Dataset
job spent 13 minutes 20 seconds pulling the private image before publication.

The first GUI Run, `3fc82c62-4add-4100-bf40-96c4d37d727f`, exposed missing
provisioning permissions. Its controller log was readable through the API, and
GUI cancellation reached the confirmed `cancelled` state. SkyPilot attempted to
bootstrap a wildcard autoscaler Role. The local task now explicitly selects the
default service account with token mounting disabled. The provisioner also needs
namespace service collection deletion and read-only RuntimeClass discovery.

Applying those settings to this disposable diagnostic instance allowed Run
`27d0351d-570c-424e-b283-9a551a364fe3` to finish on exactly one GPU. The GUI
qualification verified committed Step 12, nonempty task/controller archives and
the checksum of its downloaded `predictions.json` Artifact. This was a diagnostic
configuration change; fresh and retained qualification from the corrected signed
package remains pending.

Repeated release-runner SDK image checks exceeded their 120-second process wait.
Successful runs took about 103–105 seconds; a local thread dump at 93 seconds
showed active GraalPy execution rather than a blocked thread. The check now allows
180 seconds inside its existing 240-second test limit and passed locally.

Source `1aa036c` passed Repository Quality `34694938638` and signed release
`v0.1.0-issue288.3` passed publication on its first attempt. Installing it into
another empty instance exposed the first-use credential failure: SkyPilot logs
JWT signing-key initialization to stdout before the credential JSON. The helper
now disables logging inside its own process before importing SkyPilot. An actual
SDK probe using separate temporary databases reproduced mixed stdout with the
original helper and valid credential JSON with the fix. Both processes exited
successfully; no credential values were printed. A new signed fresh installation
is still required to qualify this correction.

Source `95bd57d` passed Repository Quality `34698764686` and published signed
`v0.1.0-issue288.5` in run `34700647181`. Its bundle digest is
`sha256:a5693b6b0a42ccf8552437ccc5c030aec996948fd83d01516b9d7470515de6b6`.
A fresh attempt reached project enrollment but failed there; replaying the
project import succeeded, so the original failure's cause remains unproven.
That diagnostic cluster was stopped. An independent empty `kind-skywright-instance`
installation then passed on its first attempt in 1062.5 seconds. HTTP status-only
observations confirmed storage qualification, activation and project import.
The private GUI reported readiness and the deployment preflight found both GPUs.
Its state and protected operator inputs are under
`~/.local/share/skywright/issue288/installed-instance/` and `installed-secrets/`.

The signed-package GUI Run `f49a72c0-0c4e-4804-95c0-d628aa0dee44` finished all
12 steps on exactly one GPU. Its Pod used the default service account without a
mounted API token. The GUI check read nonempty task/controller archives and
verified the downloaded `predictions.json` checksum. A subsequent HTTP Run also
finished, but the retained restart exposed Kubernetes API startup failures and
stale pre-restart Vault Pod readiness. The lifecycle now retries node readiness
and requires a live, valid Vault status response before initialization or unseal.
The public CLI start/restart reproduction passed with these changes. Both probes
have a five-minute budget; the deployment suite passed 78 tests with five skips.

The retained update to `v0.1.0-issue288.6` completed with a full checkpoint and
preserved completed Runs. Its immediate smoke submission returned HTTP 503;
a subsequent HTTP smoke finished and verified its Artifact checksum. Stop/start
then passed with all preflight checks ready and no training Pods remaining.
Source `d21f1fa` passed Repository Quality `34703851977` and published signed
`v0.1.0-issue288.7` in run `34705708685`.

With five completed Runs, the next maintenance inspection exposed another budget
mismatch: the Run-list API permits a 30-second page, but the installer abandoned
it after 20 seconds. The installer now gives each page the remaining portion of
its existing one-minute inspection budget. The same public update command then
passed from `.6` to `.7`, including a checkpoint, retained setup and health checks.
The deployment suite passed 78 tests with five skips. Final full-suite retained
qualification from the signed correction remains pending.
