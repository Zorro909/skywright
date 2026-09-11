# Private AMD installation

This package targets one Linux x86_64 host with two RX 7800 XT GPUs, each
reporting 17,163,091,968 bytes of VRAM, `/dev/kfd`, and `/dev/dri`. The operator
needs Python 3.12+, OpenSSL, Docker Engine access, a systemd user session with
lingering enabled, at least 48 GiB RAM, and 120 GiB free disk. Package downloads
need HTTPS access to GitHub, GHCR, the pinned Kubernetes registries and the
University of Toronto CIFAR archive. Port 8080 on loopback must be available.

The installer creates a dedicated Docker kind cluster with Calico policies.
It refuses an existing cluster with another network implementation. It does
not adopt another application's GPU jobs. Finish external GPU work before
submitting a demonstration. Both devices must be idle, with less than 512 MiB
VRAM occupied and less than 5% activity. The operator service samples this every
10 seconds; admission rejects an observation older than 30 seconds. This is
readiness evidence, not a reservation against unrelated host processes.

## Inputs and install

Copy `deployment/local-instance.example.json` to an operator-owned location.
Replace the digest with a published deployment bundle digest and replace the
paths with absolute paths on the host. State and secret directories must be
separate, non-nested directories. The JSON file is the single versioned
non-secret installation input. Updates change its release digest only.

Supply these two distinct mode-0600 JSON files inside the mode-0700
`secretDirectory`. Each contains `username` and `token` fields:

- `ghcr-resolver.json`, a classic GitHub token with only `read:packages`.
- `ghcr-pull.json`, a different classic token with only `read:packages`.

Both must read the private supplied project image and deployment images.
Use an editor or protected file transfer to populate them. Never put token
values in shell arguments, environment examples, generated manifests or Git.
The installer verifies scopes and account identity and creates exact Vault
bindings. It generates database and S3 credentials once and retains them.
Registry rotation is deliberately rejected by this workflow; changing a file
does not silently replace a retained binding revision.

From the unpacked release source, run:

```sh
scripts/deploy install --configuration /absolute/path/instance.json
scripts/deploy preflight --configuration /absolute/path/instance.json
```

Installation verifies the OCI digest and GitHub Actions provenance for the
bundle and each application image. It installs pinned tools, PostgreSQL,
Vault with TLS, SeaweedFS, SkyPilot, the backend, the AMD device plugin and
writer authority. It imports the supplied private Training Project Version
and publishes the CIFAR Dataset through the application's publication API.
The Dataset preparation job uses CPUs and has a fifteen-minute deadline.
Run storage identities are restricted to the supplied Training Project prefix.
The Metric View role has no object permissions because no Metric View is installed;
enrolling one requires granting only its selected Run metric prefix.
Initial downloads and image pulls can take 15–30 minutes. Individual setup
steps have deadlines; failures report the current step without provider
output or secret values. Retry the same command after correcting the cause.

The operator's `kind-skywright-local.service` user service renews the two Vault
consumer tokens and exposes the GUI at `http://127.0.0.1:8080`. To reach it from
another machine:

```sh
ssh -N -L 8080:127.0.0.1:8080 OPERATOR@HOST
```

Open `http://127.0.0.1:8080/runs/new`. Choose the installed CIFAR demonstration
and local target. Wait for readiness, submit, and follow the accepted Run.
The demonstration requests one GPU and twelve Steps. The Run page provides
lifecycle, committed progress, task/controller logs, cancellation and output
downloads. Downloads pass through the application and verify the recorded
checksum; no bucket or Pod access is needed. Finish or cancel every acceptance
Run, then confirm that preflight reports idle host GPUs again.

## Retained lifecycle

```sh
scripts/deploy backup --configuration /absolute/path/instance.json
scripts/deploy restart --configuration /absolute/path/instance.json
scripts/deploy stop --configuration /absolute/path/instance.json
scripts/deploy start --configuration /absolute/path/instance.json
scripts/deploy update --configuration /absolute/path/next-instance.json
```

Stop, restart and backup first restart the backend in maintenance mode. New
submissions receive `MANAGED_RUN_MAINTENANCE`; retries of accepted submissions
still return the original Run. The command refuses to continue if any Run has
an unknown or nonterminal lifecycle, or any GPU Pod remains active. If that
check fails, admission resumes so the operator can finish or cancel the Run.
Stop preserves the node, volumes and operator files. Start unseals retained
Vault and resumes the same control plane.

Update prints installed and requested versions, verifies the new signed
release, and permits only a newer SemVer release using local state schema 1.
Before starting new application images or migrations, it quiesces the control
plane and stops its kind node for a consistent checkpoint. At least 80 GiB
free space is required. Checkpoints live under `stateDirectory/backups/` and
contain PostgreSQL, object storage, Vault, SkyPilot, writer state, etcd,
Kubernetes PKI, configuration, catalog identities and operator secret inputs.
Treat the entire backup as secret material. Copy a completed checkpoint to
protected independent storage before relying on it for host-loss recovery.
No automatic backup deletion occurs; monitor disk usage.

A failed update records `pending-update.json` with the previous release,
requested release and checkpoint path. Retry the exact requested release.
Other lifecycle commands refuse to proceed while that record exists. Do not
change the release to an older version against a migrated database.

Checkpoint recovery is an explicit offline operation on the same retained
node. Stop the user service and Docker node. Preserve the failed state for
diagnosis. Restore all checkpoint components together, including the original
operator inputs and installed configuration, then run the recorded release.
The tar files preserve the original directory metadata and must be restored
by an operator with permission to preserve their numeric owners. Never mix a
restored database with newer S3, SkyPilot or writer state. Replacing the kind
node changes writer identity and requires a separately qualified recovery;
this command does not claim portable writer-custody restoration. An
incompatible database downgrade is never automatic.

## Continuous operation and limits

The node has a 12-CPU, 32-GiB Docker ceiling with swap disabled beyond that
memory limit. The backend is limited to 2 CPUs and 4 GiB; PostgreSQL to 1 CPU
and 512 MiB; Vault to 1 CPU and 512 MiB; storage to 2 CPUs and 1 GiB. SkyPilot,
its helper processes and writer retain the repository's per-container limits.
The operator user service is limited to 25% CPU, 256 MiB and 32 tasks. The
system is single-node and unauthenticated. Keep all access private.

The Vault TLS certificate lasts one year. Renew its protected certificate and
key, CA projection and backend truststore before expiry during planned
maintenance. This prototype does not automate certificate rotation.
Consumer tokens are periodic and renewed every ten minutes. If the host is
offline past their 32-day period, reissue the affected exact-policy tokens and
replace their protected projections before restarting consumers. Keep the
Vault recovery inputs under operator custody; applications never receive the
root token or unseal key.

Inspect `installed.json`, `systemctl --user status kind-skywright-local.service`
and the preflight command for health. Operator inputs remain in
`secretDirectory`, including `vault-recovery.json`, generated database/S3
inputs, TLS files and consumer token projections. Backups also contain these
inputs. Do not publish their contents.

For cleanup, stop the instance first. Deleting the dedicated kind cluster,
its Docker volume, the state directory or operator inputs destroys retained
state and writer custody. The installer has no implicit destructive reset.

## System qualification

Use a matching repository checkout for the qualification tests and browser
tooling below. To check a fresh installation before an update release is
available, run this separately on the dedicated host:

```sh
SKYWRIGHT_FRESH_INSTALL_CONFIGURATION=/absolute/path/first.json \
python3 -m unittest discover -s tests/deployment -p test_installed_amd.py
```

This test requires no completed installation record, invokes the supported
installer and requires joined preflight readiness for both GPUs. It does not
submit a Run. Retained lifecycle and GPU checks follow below.

Run the opt-in system test on the dedicated host with two increasing signed
release configurations and idle GPUs:

```sh
SKYWRIGHT_INSTALL_CONFIGURATION=/absolute/path/first.json \
SKYWRIGHT_UPDATE_CONFIGURATION=/absolute/path/second.json \
python3 -m unittest discover -s tests/deployment -p test_installed_amd.py
```

It exercises install, preflight, a one-GPU Run and Artifact download, restart,
update, a second Run, stop and start. It checks retained Run observations after
each transition. A failing smoke requests cancellation. Follow any failure by
checking the Run page and preflight to confirm cleanup.

The packaged GUI can also be qualified through the private SSH tunnel using
the pinned frontend browser tooling. This creates one twelve-Step Run and
downloads an Artifact through the GUI:

```sh
pnpm --dir frontend exec node tools/qualify-installed-instance.mjs
```

A failed GUI smoke requests cancellation through the Managed Run API. Inspect
the accepted Run and confirm GPU cleanup before another qualification.
