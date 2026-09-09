# Local production writer proof

Investigated 2026-09-09 for [#235](https://github.com/Zorro909/skywright/issues/235). Recommendation: a passive node authority registers each writer before its Execution Attempt is published, then retains proof that its exact container permanently stopped. It must not stop the container to obtain proof. This is a qualified single-node Kubernetes/containerd design, not a portable provider guarantee.

The isolated qualification node currently reports Kubernetes `1.36.1`, containerd `2.3.1`, and Linux `7.1.5-201.fc44.x86_64`. These were read through `kubectl get nodes`; no cluster changes were made. SkyPilot source inspection used the installed `0.13.0` package and its upstream tag. No SkyPilot SDK changes are needed.

## Existing seams

[`PreviousWriterVerifier`](../../sdk/src/skywright/recovery.py) receives the previous `ExecutionAttemptRecord` and returns authority-verified evidence or `None`. Its evidence identifies the Run, exact attempt, condition and inspectable reference. [`RecoveryJournal.prepare`](../../sdk/src/skywright/_run_store/recovery.py) rejects absent proof, rereads the conditional history head after verification, and only then evaluates debt. Preserve that ordering and its fail-closed errors.

[`ManagedRuntime.run`](../../sdk/src/skywright/_managed_runtime.py) defaulted to `uncertain_previous_writer` at the start of this investigation; the implemented production CLI now installs the optional local authority client. [`run_training_process`](../../sdk/src/skywright/_training_process.py) generates the attempt UUID before `resolved_recorder.publish_attempt(attempt)`, which admits into the recovery journal. Add the registration callback immediately before that publication, after recovery validation, and before project entry. Registration failure must prevent both publication and project entry. A durable registration without a subsequent attempt publication is harmless retained custody, not an admitted attempt.

[`RunJobAdapter.previousWriter`](../../backend/src/main/java/de/zorro909/skywright/backend/orchestration/RunJobAdapter.java) intentionally returns uncertainty, and SkyPilot correlation is only at Run/job scope. Do not convert that display information into evidence. An operator-configured local Unix client can supply the production verifier and registration callback without changing the SDK's explicit embedding/test injection seam.

## Cooperative recovery can remain passive

SkyPilot 0.13.0's default `EAGER_NEXT_REGION` strategy awaits cluster cleanup before replacement launch. Its `FAILOVER` strategy can first reuse surviving resources. The former supports whole-container proof; explicitly pin it for this qualified target and reject pools or strategies that reuse the old container. [Pinned recovery strategy source](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/recovery_strategy.py)

The controller obtains exit codes and decides whether to recover before invoking recovery. Skywright already requests recovery on 75 with zero generic user-error restarts. Let the runtime publish its cooperative report and exit 75 normally. The new gate may wait a bounded interval while ordinary SkyPilot teardown completes. A still-present Pod object does not prevent proof once the node establishes actual container death; a still-running old container does. [Pinned controller source](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/controller.py), [current task projection](../../backend/src/main/java/de/zorro909/skywright/backend/runtimeassembly/LocalRuntimeProjection.java)

Pod phase, deletion and `NotFound` are never sufficient: force deletion does not wait for node confirmation, and processes can continue running. [Kubernetes Pod lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)

## Registration and authority custody

Run one daemon per qualified node with the node's PID/proc view, read-only cgroup access, and privileged access to the local container runtime. Mount only its client socket into Training Processes. Keep its identity, registrations and proofs in a separate root-owned persistent directory, with atomic durable writes and a single writer. Neither training storage credentials nor the client mount may permit editing custody. A read-only mount of a Unix socket does not make runtime RPC methods read-only; the daemon's runtime access is privileged and belongs in the trusted computing base.

Registration should follow this sequence:

1. Authenticate the connecting process with `SO_PEERCRED`, which reports credentials captured when the socket connected. Obtain `SO_PEERPIDFD` too, retaining a reference to that peer while reading `/proc`; a numeric PID followed by `pidfd_open` alone can race PID reuse. Reject an already-dead peer, inconsistent PID/start-time observations and unsupported kernel facilities. [Unix socket contract](https://man7.org/linux/man-pages/man7/unix.7.html), [kernel socket implementation](https://code.googlesource.com/linux/torvalds/linux/+/a8934c2c6dfd72901cf3cc0de28e85eb902a61a4/net/core/sock.c), [pidfd interface](https://man7.org/linux/man-pages/man2/pidfd_open.2.html)
2. Resolve the peer's actual cgroup/container through node-owned proc and runtime information. Cross-check the full container ID, sandbox, Pod UID, container creation/start times, image digest and node against trusted launch/Pod metadata. Caller-supplied Run/attempt values are claims to bind, not proof of container identity. Authorize the Run against operator-owned launch metadata. `ContainerStatus(verbose)` debug information is runtime-specific, so qualify the exact containerd shape instead of treating arbitrary CRI implementations as compatible. [CRI contract](https://github.com/kubernetes/cri-api/blob/v0.36.1/pkg/apis/runtime/v1/api.proto), [containerd 2.3.1 status implementation](https://github.com/containerd/containerd/blob/v2.3.1/internal/cri/server/container_status.go)
3. Retain an immutable binding containing authority identity, node identity, kernel boot ID, Run and attempt UUIDs, full container/sandbox IDs, Pod UID, peer PID/start time, container-init identity, PID namespace identity, and verified cgroup root/path identity. Require the exact original binding on retries; never replace it with a new process claiming the same attempt. A container may not acquire a second admitted writer identity under this first implementation.
4. Acknowledge only after the binding is durable. Bound request size, connections and runtime inspection time. Do not emit complete runtime inspection JSON: it can include credentials. Do not accept arbitrary paths, shell commands, container IDs or proof documents from the client as authority input.

The host supports `SO_PEERPIDFD`: local temporary Unix `SOCK_STREAM` and `SOCK_SEQPACKET` checks returned the current peer credentials and an `anon_inode:[pidfd]` descriptor. That checks availability only, not the complete registration implementation.

There were no current Pods in `skywright-training` to inspect. The pinned SkyPilot template defaults to `restartPolicy: Never` outside high availability and normally leaves the main container's security context unspecified. Keep ordinary setup capabilities such as `CHOWN` and `SETUID` where required; validate the resulting Pod and OCI capabilities/mounts rather than assuming a blanket capability drop works. Reject privileged mode, escape capabilities and container-level restart overrides. [Pinned Pod template](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/templates/kubernetes-ray.yml.j2)

## What constitutes permanent death

For the same kernel boot, require a retained registration and the exact container's authoritative `EXITED` state, plus descendant-death evidence. In containerd 2.3.1, `StartContainer` accepts only `CREATED`; an `EXITED` container ID cannot restart through CRI. Its exit handler cleans up the task before persisting exit status and filters exec-process exits from container exits. Do not accept a runtime process's own exit message, an exec exit event, `UNKNOWN`, or a missing container as equivalent. [Start implementation](https://github.com/containerd/containerd/blob/v2.3.1/internal/cri/server/container_start.go), [exit handling](https://github.com/containerd/containerd/blob/v2.3.1/internal/cri/server/events.go)

Require a private container PID namespace, no host PID sharing, no shared Pod process namespace, and no permissions allowing processes to escape or migrate out of their registered custody. Linux kills the remaining namespace processes when its init exits and prohibits new processes entering that dead namespace; the exit path waits for namespace processes. This supplies an additional whole-container basis beyond a single runtime PID. [PID namespace contract](https://man7.org/linux/man-pages/man7/pid_namespaces.7.html), [kernel namespace teardown](https://github.com/torvalds/linux/blob/v6.12/kernel/pid_namespace.c)

Use `cgroup.events`' recursive `populated=0` as the straightforward descendant check. Forks inherit cgroup membership, whereas moving a parent does not move existing children. A registered cgroup's verified deletion can substitute only under the qualified no-migration rules: kernel cgroup removal requires no live processes or child cgroups. [Kernel cgroup interface](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html), [removal implementation](https://github.com/torvalds/linux/blob/v6.12/kernel/cgroup/cgroup.c)

Plain path absence is insufficient. Verify the same node/boot and actual cgroup filesystem root, traverse beneath that root without symlinks, and correlate with the exact retained container identity and `EXITED` observation. A missing mount, changed root, permission failure or unrelated reused path is uncertainty. If the cgroup still exists under a different identity, fail closed even if it is empty. Collect the registered cgroup's empty/removal observation proactively where possible: runtime cleanup can remove it before a later status query. The daemon observes; it does not call `cgroup.kill`, `StopContainer` or remove cgroups.

Do not create per-writer child cgroups as an incidental shortcut. That requires custody before any fork and a delegated subtree with migration restrictions; editing runtime-managed cgroups also conflicts with the cgroup manager's ownership rules. [systemd delegation rules](https://github.com/systemd/systemd/blob/main/docs/CGROUP_DELEGATION.md)

## Restart, partition and missing evidence

| Situation | Admission behavior |
| --- | --- |
| Authority restarts, intact custody, same boot | Reconstruct observations for exact registrations. Existing durable proofs remain usable. Never infer death from daemon downtime. |
| Container runtime restarts | Reconcile exact container identity and kernel custody. Runtime restart itself proves nothing about surviving tasks. |
| Same enrolled physical node reports a different kernel boot ID | May record death of registered old-kernel processes only with intact persistent authority custody and a qualified no-resurrection rule. Node name or Pod UID alone is insufficient. |
| Node/authority partition or stopped old writer | Return pending/unavailable within the deadline. No recovered attempt or project code starts. |
| Container/Pod GC before proof, missing registration, corrupt custody | Fail closed. `NotFound` is not a new proof. If an immutable proof already exists, GC does not erase it. |
| Node replacement or authority storage reset | Require requalification/re-enrollment. Do not recreate missing authority identity and reinterpret old registrations. |

Linux's boot ID remains unchanged during a boot. A kind node-container restart is not necessarily a kernel reboot, so it cannot automatically take the boot-change branch. VM snapshot restoration or CRIU resurrection must be excluded from this first qualification; otherwise permanent death needs a different authority. [Kernel boot-ID documentation](https://www.kernel.org/doc/html/v6.9/admin-guide/sysctl/kernel.html#random)

After positive observations, write a content-addressed immutable proof identifying the exact registration, evidence type and authority version. Return `PreviousWriterEvidence(..., condition="stopped", reference=...)` only through the trusted authority channel. Keep proofs for at least the Run's recovery-history lifetime. Use signatures if a proof is transported or copied through training-writable storage; a client-provided reference or JSON body cannot authenticate itself.

A practical initial bound is a 60-second gate deadline with short per-call deadlines, fixed connection/worker limits, and capped registration/proof JSON. Pending responses must not monopolize server workers. Expiry returns writer uncertainty, not exhaustion or a fabricated termination cause. Re-read recovery history through the existing journal logic after proof arrives.

## Qualification and remaining limits

CPU tests should exercise the real Unix client/daemon and real pinned containerd in Kubernetes, in addition to domain fixtures:

- Failed registration prevents attempt publication and project entry; retries preserve the original binding, and a different process cannot replace it.
- A runtime parent exits while a detached child keeps writing. Proof remains unavailable until the whole container stops; test nested descendants and PID/cgroup identity mismatches.
- SIGSTOP the old writer, attempt recovery, resume it, and verify there was no second admission. Repeat with authority/network loss.
- Preserve cooperative exit 75, observe default-strategy teardown, admit only after proof, and verify the next committed Dataset Item. Keep the old Pod object present during one positive node-proof test.
- Restart daemon and container runtime; test proof persistence, torn/missing custody, GC before and after proof, wrong node/boot, and bounded overload/timeouts. Exercise CRI rejection of restarting the exact stopped container ID.
- Run the same authority image, socket mount, private image and storage path on the AMD GPU target. Retain actual container IDs and proof references with checkpoint/continuation evidence.

This design proves death within qualified process/container custody. It does not revoke S3 credentials or defend against a privileged host, external services holding copied credentials, or deliberately restored process snapshots. It also does not itself prove that a storage request accepted before process death cannot finish later. The existing ADR explicitly accepts process death as an admission condition; do not describe this authority as storage fencing or claim broader in-flight-write guarantees without a separate storage-level design.

No full writer-proof implementation or GPU recovery test was executed during this research. The remaining practical qualification risk is capturing trustworthy cgroup removal before runtime GC, especially across daemon downtime. Missing observations must reduce availability rather than relax proof.

## Implementation review: mount qualification

The first authority implementation checked Pod `hostPath` volumes but allowed unexamined PVC/CSI sources and OCI bind mounts. That does not establish the intended absence of host/runtime access: a PVC can represent local storage, and OCI mounts define the actual source exposed to the container. For this first target, allow only `emptyDir`, `secret`, `configMap`, `projected` and the exact read-only authority socket directory. Reject other volume kinds, block devices, subpaths and non-private propagation. Match each actual OCI bind against the declared volume's exact source/destination, rather than allowing a broad host-directory prefix. [Persistent-volume types](https://kubernetes.io/docs/concepts/storage/persistent-volumes/), [OCI mount contract](https://github.com/opencontainers/runtime-spec/blob/v1.2.1/config.md#mounts)

A read-only inspection of a live container on the qualification node confirmed the generated mount roots below. The secret/projected equivalents still need real-fixture coverage. Here `U` is the exact Pod UID, `S` the sandbox ID, `C` the container name and `N` the volume name:

| Node source | Container destination |
| --- | --- |
| `/var/lib/kubelet/pods/U/volumes/kubernetes.io~empty-dir/N` | Declared mount path, including SkyPilot `dshm` at `/dev/shm` |
| Same volume root with `kubernetes.io~secret`, `kubernetes.io~configmap` or `kubernetes.io~projected` | Declared mount path, read-only |
| `/var/lib/kubelet/pods/U/etc-hosts` | `/etc/hosts` |
| `/var/lib/kubelet/pods/U/containers/C/<generated-file>` | Declared termination-message path, normally `/dev/termination-log` |
| `/var/lib/containerd/io.containerd.grpc.v1.cri/sandboxes/S/hostname` | `/etc/hostname`, read-only or writable within this sandbox |
| Same sandbox root with `resolv.conf` | `/etc/resolv.conf`, read-only or writable within this sandbox |
| `/run/containerd/io.containerd.grpc.v1.cri/sandboxes/S/shm` | `/dev/shm`, when no explicit shared-memory volume replaces it |

Kubelet supplies managed hosts and volume mounts; containerd also supplies standard proc, tmpfs, devpts, mqueue, sysfs and cgroup mounts. Permit only their expected destination/type pairs, retain read-only sysfs/cgroup checks, and reject unmatched binds or duplicate destinations. Do not reject ordinary generated hostname/resolver/termination mounts merely because they are host-backed. [Pinned kubelet mount assembly](https://github.com/kubernetes/kubernetes/blob/v1.36.1/pkg/kubelet/kubelet_pods.go), [termination mount assembly](https://github.com/kubernetes/kubernetes/blob/v1.36.1/pkg/kubelet/kuberuntime/kuberuntime_container.go), [containerd default mounts](https://github.com/containerd/containerd/blob/v2.3.1/pkg/oci/mounts.go)

Also reject container-level restart policies/rules outside this qualification. Kubernetes 1.36 supports overrides even when the Pod's policy is `Never`; checking only the Pod field misses them. [Container restart rules](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#container-restart-policy)

A later GPU-allocated Pod with a writable root filesystem confirmed that containerd
mounts the exact sandbox hostname and resolver files read-write in that mode. The
authority accepts either mode for those two isolated files while retaining exact
source matching. Substitution with a runtime socket is rejected. This changes no
PID namespace, cgroup, capability or authority-socket condition. The six custody
and mount regressions passed in the root container.

## Ancillary ROCr discovery device

The first managed CIFAR startup was refused before attempt publication because the
retained #233 context projects `/dev/dri/renderD129` for ROCr discovery. The device
plugin still allocates the discrete GPU, and `ROCR_VISIBLE_DEVICES=0` selects it.
This requirement was already measured in [#233's qualification](issue-233-local-gpu-qualification.md).

The authority now supports an explicit, default-disabled ancillary render-device
pin. It accepts only the named AMD DRM render character device, requires major
226 and the matching render minor from 128 through 255, verifies AMD's sysfs vendor
on the host and peer, and matches the exact `CharDevice` hostPath and OCI bind.
A regular file, symlink, directory or substituted runtime socket cannot satisfy
that check. Generic hostPath access remains unavailable.

Linux documents render nodes as the unprivileged rendering/GPGPU interface without
modesetting or privileged ioctls. Their driver and kernel remain trusted; this
exception provides no container-runtime or cgroup control access. This supports
retaining the same process-death proof condition. [Kernel DRM render-node contract](https://docs.kernel.org/gpu/drm-uapi.html#render-nodes)
