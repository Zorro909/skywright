# Private AMD installation qualification

Evidence for [#288](https://github.com/Zorro909/skywright/issues/288), recorded
2026-09-12. The retained instance is installed on `zorro@192.168.1.198`.
Fresh installation, the GUI workflow and retained restart/update/stop/start
qualification completed. Transient post-update admission and readiness failures
were retried on the same installed release, as detailed below.

## Installed instance

- Version: `v0.1.0-issue288.8`.
- Source: `9a9617df3d94a50b1bde0298485b7b812b6eaccf`.
- Bundle: `ghcr.io/zorro909/skywright-deployment@sha256:676087dcd04ce83c5f08fe79569bb752f221da5053f00d72503079a0627dd5fe`.
- Source quality: [34707297956](https://github.com/Zorro909/skywright/actions/runs/34707297956).
- Signed publication: [34709482784](https://github.com/Zorro909/skywright/actions/runs/34709482784), attempt 2. Attempt 1 passed the build/tests but GHCR rejected an image push with `unknown blob`; retry succeeded.
- Kubernetes context/node: `kind-skywright-instance` / `skywright-instance-control-plane`.
- Node UID: `78ac6ded-017b-464d-821e-852537cfc131`.
- State: `/home/zorro/.local/share/skywright/issue288/installed-instance/`.
- Current operator configuration: `/home/zorro/.local/share/skywright/issue288/installed-update.json`.
- User service: `kind-skywright-instance.service`, enabled with user lingering.

The host runs Ubuntu 24.04.4 LTS, kernel 6.8.0-139-generic and Docker 29.7.1,
with about 62 GiB RAM. Both RX 7800 XTs report 17,163,091,968 bytes VRAM.
The node is limited to 12 CPUs and 32 GiB, with no additional swap allowance.
The operator service is limited to 25% CPU, 256 MiB and 32 tasks. Backend,
SkyPilot, PostgreSQL, Vault, storage and writer containers retain their documented
individual limits. See [supported prerequisites and resource ceilings](../../deployment/LOCAL_INSTALLATION.md).

The GUI and API listen on the host's loopback port 8080. Connect using the
operator's loaded SSH agent key:

```sh
ssh -N -L 18080:127.0.0.1:8080 zorro@192.168.1.198
```

Open `http://127.0.0.1:18080/runs/new`. Choose the installed CIFAR demonstration
and local target, wait for readiness, and submit. It requests one GPU and 12 Steps.
The Run page provides lifecycle, progress, task/controller logs, cancellation,
and checksum-verified output downloads. The instance remains private and
unauthenticated as required by the single-Principal-Identity prototype.

## Operator inputs and checkpoints

The mode-0700 operator input directory is:
`/home/zorro/.local/share/skywright/issue288/installed-secrets/`.
It contains the separate mode-0600 `ghcr-resolver.json` and `ghcr-pull.json`
inputs, `generated-inputs.json`, `vault-recovery.json`, TLS material and consumer
projections. The two distinct read-only GHCR credentials were copied with the
operator's authorization from the originating development Vault. Their source
references were `skywright/local/issue235-ghcr-resolver` and
`skywright/local/issue235-ghcr-pull`. No values are recorded here.

The checkpoint preceding `.7 → .8` is:
`/home/zorro/.local/share/skywright/issue288/installed-instance/backups/20260912T182556033029Z/`.
Its completed record accompanies 768,883,200 bytes of volume, writer, etcd and
Kubernetes archives, plus retained configuration and protected operator inputs.
Earlier completed checkpoints remain under the same `backups/` directory.

Checkpoints cover PostgreSQL, object storage, Vault, SkyPilot, writer custody,
Kubernetes state and operator inputs. Recovery restores all components together
on the same retained node. It does not automatically downgrade an incompatible
database or transfer writer identity to another node. Host-loss recovery needs
a protected independent copy. See the [backup and recovery procedure](../../deployment/LOCAL_INSTALLATION.md#retained-lifecycle).

## Commands used

On the host, the qualification checkout is
`/home/zorro/.local/share/skywright/issue288/qualification-code/`.
The supported CLI accepts the single versioned configuration file:

```sh
cd /home/zorro/.local/share/skywright/issue288/qualification-code
scripts/deploy preflight --configuration /home/zorro/.local/share/skywright/issue288/installed-update.json
scripts/deploy restart --configuration /home/zorro/.local/share/skywright/issue288/installed-update.json
scripts/deploy stop --configuration /home/zorro/.local/share/skywright/issue288/installed-update.json
scripts/deploy start --configuration /home/zorro/.local/share/skywright/issue288/installed-update.json
scripts/deploy backup --configuration /home/zorro/.local/share/skywright/issue288/installed-update.json
```

For the next update, copy that configuration and change only `release` to the
new signed bundle digest. Run `scripts/deploy update --configuration` with the
new file. Updates verify provenance, checkpoint retained state, apply compatible
migrations/configuration and run health checks. Failed updates retain the exact
requested release and recovery reference. Keep checkpoints and operator inputs
under operator custody; cleanup is an explicit destructive operation.

The fresh system test used `SKYWRIGHT_FRESH_INSTALL_CONFIGURATION` pointing to
`/home/zorro/.local/share/skywright/issue288/installed-install.json`. The retained
`.7 → .8` test used:

```sh
SKYWRIGHT_INSTALL_CONFIGURATION=/home/zorro/.local/share/skywright/issue288/installed-previous-seven.json \
SKYWRIGHT_UPDATE_CONFIGURATION=/home/zorro/.local/share/skywright/issue288/installed-update.json \
python3 -m unittest discover -s tests/deployment -p test_installed_amd.py -v
```

These are recorded inputs for that completed installation/update sequence, not
instructions to downgrade the current instance. For another full update test,
use the current installed configuration and a newer signed release.

The browser qualification used the SSH tunnel above:

```sh
SKYWRIGHT_QUALIFICATION_URL=http://127.0.0.1:18080 \
pnpm --dir frontend exec node tools/qualify-installed-instance.mjs
```

## Results and limits

The empty `kind-skywright-instance` installation of `.5` passed on its first
attempt in 1062.5 seconds, including the private image pull, CPU-only Dataset
publication, both-GPU preflight and GUI readiness. The input directory initially
contained only the two GHCR files. No manually prepared database, Vault, catalog
or runtime configuration was copied into this instance. Initial downloads took
most of the time; the Dataset job stayed within its fifteen-minute limit.

Signed-package GUI Run `f49a72c0-0c4e-4804-95c0-d628aa0dee44` finished all
12 Steps on exactly one GPU. Its Pod used the default service account without a
mounted API token. The GUI check read nonempty task/controller archives and
verified the downloaded `predictions.json` checksum. GUI cancellation was also
verified on diagnostic Run `3fc82c62-4add-4100-bf40-96c4d37d727f`, which reached
the confirmed cancelled state before any GPU Pod was created.

The `.7 → .8` system test completed pre-update Run
`7a9b99d8-4fff-40f8-a3f5-67df0ec260af`, verified its Artifact, restarted the
control plane, preserved its history and completed the checkpointed update.
It then stopped on a retryable HTTP 503 while submitting the post-update Run.
The qualification client now retries only the named retryable admission response,
using the same submission identity and a two-minute limit. Other admission errors
still fail. Read-only preflight readiness is also polled for at most two minutes.

The resumed post-update smoke, Run
`d1aad384-15bd-497a-b2bd-5f0d77d5dc7d`, finished and verified its Artifact
checksum. Stop/start preserved that Run. The final preflight initially reported
temporary control-path unavailability and passed on retry. This was resumed
qualification, not one uninterrupted passing test invocation.

At 18:42 UTC, the final audit confirmed both idle GPUs, no GPU Pods, no pending
update, and unchanged node UID, all five persistent-volume identities, Dataset
identity/fingerprint, storage registrations and the five recorded operator
inputs. The private user service remains enabled and the instance is running.

Earlier runs found and drove fixes for first-use SkyPilot logging corrupting
credential JSON, Kubernetes/Vault startup races, and the maintenance client's
20-second timeout against the API's 30-second Run-page budget. Live public CLI
reproductions passed after each fix. An earlier project-import failure did not
recur in the independent fresh installation; its original cause remains unproven.
Stopped preparatory/diagnostic clusters remain separate from the retained
instance. The pre-existing `skywright-onprem` cluster and unrelated host services
were not stopped or reconfigured.

Local verification passed 78 deployment tests with five skips, 468 SDK unit tests,
20 installed-wheel tests, the frontend/backend reactor and all 20 container-image
tests. Targeted HTTP tests covered durable replay, maintenance rejection,
verified project import, output listing/downloads and cross-Run/checksum rejection.
Live storage probes allowed own-project access and denied foreign-project
read/write/list and unenrolled Metric View access. Probe objects were removed.
The accepted test boundaries were the deployment CLI, Managed Run HTTP API and GUI.
