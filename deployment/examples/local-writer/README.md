# Local writer authority

This optional authority supplies production previous-writer proof for the first
local AMD qualification. It is limited to the enrolled node, Linux peer pidfds,
containerd 2.3.1 and private container PID namespaces. It does not fence storage
credentials or qualify cloud recovery. [ADR 0016](../../../docs/adr/0016-bound-infrastructure-recovery-with-progress-decayed-debt.md)
retains refusal whenever death cannot be established.

The authority is a trusted node service with `SYS_ADMIN`, `SYS_PTRACE` and
`DAC_READ_SEARCH` capabilities. It reads node proc/cgroups, the local
containerd API, the enrolled Node and training Pod metadata. A read-only socket
mount still permits runtime RPCs, so this service belongs to the trusted control
plane. Training containers receive only its separate Unix protocol socket. They
receive neither its Kubernetes identity nor containerd access.

## Install on the qualified node

Build from the reviewed checkout, load the image into the local runtime, and use
an immutable image reference for operated deployments. The Containerfile pins its
Ubuntu and kind tool sources. Its crictl is 1.36.0.

```sh
podman build -f deployment/local_writer/Containerfile -t localhost/skywright-local-writer:qualification .
KIND_EXPERIMENTAL_PROVIDER=podman kind load docker-image localhost/skywright-local-writer:qualification --name YOUR_CLUSTER
kubectl --kubeconfig YOUR_KUBECONFIG get node YOUR_NODE -o jsonpath='{.metadata.uid}'
deployment/scripts/render-local-writer --node YOUR_NODE --node-uid OBSERVED_NODE_UID \
  --image localhost/skywright-local-writer:qualification --image-pull-policy Never \
  > /tmp/skywright-local-writer.json
kubectl --kubeconfig YOUR_KUBECONFIG apply -f /tmp/skywright-local-writer.json
```

The node UID is an enrollment pin. A replacement node cannot inherit the old
node's proof merely by reusing its name. The initializer creates a fresh authority
only in empty custody, and never reconstructs a missing identity over retained
records. Missing or corrupt custody refuses service. Keep
`/var/lib/skywright-writer/custody` root-owned with mode 0700 and persist it for at
least every dependent Run's recovery-history lifetime. Back it up with the
operator's deployment custody. Do not delete it as a temporary cache.

Enable `skywright.local-run.writer-authority-enabled=true` on the backend only
for the qualified target. This option adds a fixed read-only client mount and
Run label to its SkyPilot Pod configuration and pins `EAGER_NEXT_REGION` recovery.
It does not modify SkyPilot's server/client SDK. The project image must contain
the matching Skywright SDK client. An import preflight rejects older images before
runtime delivery. A missing authority refuses initial writer
registration before attempt publication or project entry.

This retained qualification host needs the ancillary AMD render node described in
[#233's device evidence](../../../docs/research/issue-233-local-gpu-qualification.md).
Only for a target with that measured requirement, add
`--ancillary-render-device /dev/dri/renderD129` to the renderer. The option accepts
one explicitly enrolled AMD DRM render character device, checks its major/minor
and sysfs vendor on the node and peer, and permits only the matching `CharDevice`
volume and exact bind destination. It does not add the training mount; the existing
qualified SkyPilot context owns that projection. Other hostPath devices and runtime
sockets remain refused. Kernel and GPU driver correctness remain trusted, as they
already do for the allocated accelerator.

## Evidence and failure behavior

The daemon authenticates the socket's exact peer with credentials and a pidfd,
checks its kernel process/container identity against trusted Pod metadata, and
acknowledges registration only after an immutable record is durable. A different
process cannot replace that binding, and a container cannot register another
attempt. Registration carries no storage credentials.

A same-boot `CONTAINER_EXITED` observation for the exact registered container,
together with verified recursive cgroup emptiness or removal, can create a death
proof. The qualified runtime cannot restart that exited container ID. Private PID
namespace and mount/capability checks prevent supported writers from escaping
that custody. The daemon never stops containers to obtain evidence. It can prove
death while Kubernetes still shows the old Pod as Running; Pod phase is not its
authority.

Proofs name the registration digest and remain available after daemon restart or
Pod/container garbage collection. Missing runtime state before proof, an unknown
writer, a changed kernel boot, a replaced cgroup root, unsupported mounts, runtime
outages and malformed custody do not prove death. This first implementation
conservatively refuses proof across kernel reboot; it does not infer physical
node destruction or exclude process resurrection from a changed boot ID alone.

The SDK waits up to 60 seconds for explicit pending teardown. Each local call has
a maximum 10-second deadline. Invalid evidence and unavailable authority fail
closed; no recovery exhaustion or termination cause is fabricated. The existing
Run Store gate rechecks durable history after verification.

Requests are limited to 4096 bytes, runtime inspection to 2 MiB and three seconds,
and each custody record to 32 KiB. The service retains at most 1000 registrations
per node and observes one pending registration between requests. Reaching the
limit refuses new registration. Custody is not automatically pruned: release and
retention policy must account for all dependent Run histories before removal.

Readiness requires a bounded health acknowledgement from the initialized Unix
listener. A stale socket pathname or a listener that no longer serves requests
does not pass. This health request neither registers a writer nor creates proof;
each writer admission still performs its own identity and custody checks.

## Qualification

The CPU/container check uses the real SDK Unix client, a suspended detached child,
actual container teardown, retained Pod objects, and an authority restart. It
creates temporary CPU Pods and a ConfigMap, then removes them. It restarts the
selected authority, so run it in the isolated qualification deployment.

```sh
deployment/scripts/qualify-local-writer --kubeconfig YOUR_KUBECONFIG --node YOUR_NODE \
  --image localhost/skywright-local-writer:qualification --authority skywright-local-writer \
  --output /tmp/local-writer-evidence.json
podman run --rm --entrypoint python3 -v "$PWD:/workspace:ro" -w /workspace \
  localhost/skywright-local-writer:qualification -m unittest tests.deployment.test_local_writer
```

GPU recovery additionally requires the actual managed project, immutable Dataset,
Run Store and UI submission. Track that evidence in
[the #235 qualification record](../../../docs/testing/issue-235-local-amd-workflow.md).
The [source investigation](../../../docs/research/issue-235-production-writer-proof.md)
explains the pinned runtime and Linux guarantees and their limits.
