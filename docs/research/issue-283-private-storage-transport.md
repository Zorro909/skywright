# Issue 283: private storage transport and rental ownership

Research date: September 13, 2026. Sources are the installed, unmodified
SkyPilot 0.13.0 package, its official release sources, Vast documentation and
OpenSSH manuals. No credentials were read and no provider requests were made.

An API-server-owned reverse SSH tunnel can carry rental traffic to the existing
private S3 service without publishing that service. Use Managed Jobs
consolidation so the controller and tunnel supervisor share the API server's
key custody. The design still requires a real connection check on the selected
image, remote loopback binding verification, and an explicit host-key trust
choice. SSH support alone does not prove those runtime properties.

## Controller placement and SSH keys

The supported server configuration is:

```yaml
jobs:
  controller:
    consolidation_mode: true
    resources:
      infra: kubernetes/EXISTING_CONTEXT
      cpus: "2"
      memory: "4"
```

The pinned schema accepts the boolean and the Kubernetes resource pin together.
This was validated with `jsonschema.validate` against the actual 0.13.0
`schemas.get_config_schema()`. Keeping the resource pin preserves a local
placement constraint; consolidation runs controller processes in the API server
instead of provisioning that separate controller.
[Configuration schema](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/schemas.py),
[consolidated launch](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/server/core.py)

Effective mode is latched at server startup. The deployment default enables it
only when no separate controller already exists; explicit `true` removes that
default ambiguity. Read `sky.jobs.utils.is_consolidation_mode()` in the API
server context and check the marker
`~/.sky/.jobs_controller_consolidation_reloaded_signal`. Merely reading the
configuration is insufficient after an update without restart. Ensure
`IS_SKYPILOT_JOB_CONTROLLER` is absent in this verification process: that
override forces a true result. Check for existing controller records before
switching; the pinned implementation warns about them.
[Startup decision](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/utils.py),
[effective-mode reader](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/controller_utils.py),
[environment constant](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/skylet/constants.py)

Keys are per SkyPilot user hash, not per rental and not necessarily at
`~/.ssh/sky-key`. The actual paths are
`~/.sky/clients/<user_hash>/ssh/sky-key` and `sky-key.pub`. The private key is also
stored in SkyPilot's state database and may be recreated from there. Directories
are tightened to 0700 and the private file to 0600. A supervisor must use the
job owner's key or the handle's `auth.ssh_private_key`, rather than choosing the
first key on disk. Separate controllers can have a separate database and key
pair even when their user hash matches. Consolidation avoids that extra custody
boundary. Never copy the private key to the rental or expose it through logs.
[Key generation and persistence](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/auth_utils.py),
[handle credentials](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/backends/backend_utils.py)

Correction to the earlier provisioning-method inventory: Vast authentication
calls `show_ssh_keys()` and calls `create_ssh_key()` if this public key is absent.
It skips registration only for the specific `team_ssh_keys_not_supported`
response. The subsequent create request also injects the public key into
`authorized_keys` through `onstart_cmd`. Injection does not bypass the earlier
registration attempt. Verify that the enrolled key's effective `api.ssh.keys`
rights cover this operation; broad billing or key-administration permission is
not required for that purpose.
[Vast authentication](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/authentication.py),
[onstart injection](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py)

## Tunnel direction and admission checks

The proposed topology is:

```text
rental application -> rental 127.0.0.1:8333
                   -> existing outbound SSH connection from API server
                   -> skywright-storage.skywright.svc:8333
```

The supervisor runs the equivalent of this argument vector, with validated
endpoint values and a protected key path:

```text
ssh -N -T -a -i KEY_PATH -p SSH_PORT
    -o BatchMode=yes -o IdentitiesOnly=yes
    -o ExitOnForwardFailure=yes
    -o ServerAliveInterval=5 -o ServerAliveCountMax=3
    -o ControlMaster=no -o ControlPath=none
    -R 127.0.0.1:8333:skywright-storage.skywright.svc:8333
    root@SSH_HOST
```

Remote forwarding opens the listener at the SSH server and connects to the
destination from the SSH client. Thus the Kubernetes service name only needs
to resolve in the API-server pod. No new Service, Ingress or public storage port
is needed. The command must receive an explicit host-key policy as described
below before execution.
[OpenSSH remote forwarding](https://man.openbsd.org/ssh.1#R)

The existing storage NetworkPolicy permits same-namespace pods and the training
namespace on TCP 8333. The API-server pod is therefore an appropriate origin
under the current installation configuration. Still check DNS and an
authenticated S3 request from that pod before launching.
[Installed storage policy](../../deployment/skywright_deployment/local_resources.py),
[storage endpoint](../../deployment/skywright_deployment/local_catalog.py)

Require remote TCP forwarding to be enabled and verify the effective
`GatewayPorts` setting. `no` forces loopback; `clientspecified` can honor the
explicit loopback address. `yes` forces a wildcard listener even when the
client asks for loopback. Verify the actual listening address with `ss` before
releasing storage access to the workload. Do not add port 8333 to the Vast
container's published ports.
[OpenSSH server forwarding controls](https://man.openbsd.org/sshd_config.5#GatewayPorts)

`ExitOnForwardFailure` detects failure to establish the forward, not subsequent
failure to reach S3. Keepalives detect a broken SSH session, not failed storage
authentication. Require an authenticated loopback S3 read in the rental before
starting dataset access, and monitor tunnel and application health separately.
Keep credential scope limited to the necessary dataset and run-artifact paths;
loopback transport does not replace S3 authorization.
[OpenSSH client options](https://man.openbsd.org/ssh_config.5#ExitOnForwardFailure)

SkyPilot's default SSH arguments disable host-key checking and discard known
hosts. Do not describe that as verified server identity. For the tunnel, use a
per-rental known-hosts file and pin a trusted fingerprint when one is available.
An explicit trust-on-first-use policy such as `accept-new` rejects later changes
but retains a first-connection interception risk. The reviewed Vast sources do
not provide an authenticated per-rental fingerprint API. This is a trust choice
to record, not evidence that all cloud SSH transport is impossible.
[Pinned SSH defaults](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/command_runner.py),
[host-key policy](https://man.openbsd.org/ssh_config.5#StrictHostKeyChecking)

Use a coherent SSH host/port pair. Vast documents `ssh_host` and `ssh_port` for
the proxy endpoint. Direct SSH uses the public IP and the published 22/tcp port.
Do not combine a proxy hostname with a direct-port mapping. The pinned adapter
prefers `ssh_host` but can take the port from `ports['22/tcp']`; verify the actual
selected pair before trusting it for the tunnel. Also verify the remote shell
belongs to the rental rather than an intermediary where loopback would have a
different meaning.
[Vast connection methods](https://docs.vast.ai/guides/instances/connect/ssh),
[instance response fields](https://docs.vast.ai/api-reference/instances/show-instance),
[adapter endpoint selection](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/instance.py)

## Match and clean up only this demonstration's rentals

For an ordinary managed task outside a pool, SkyPilot constructs the logical
cluster name from the task name and numeric Managed Job ID. It normalizes and
truncates the task prefix using `JOBS_CLUSTER_NAME_PREFIX_LENGTH`, whose actual
value is 25, then appends `-<job_id>`. A stale nearby comment mentions 30; use the
constant. Cloud naming then appends the submitting user hash and applies Vast's
120-character limit. Prefer the returned `cluster_name_on_cloud` from the
managed queue/handle rather than independently reimplementing these rules.
[Name generation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/utils.py),
[prefix constant](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/constants.py),
[cloud normalization](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/common_utils.py),
[Vast limit](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/clouds/vast.py)

Vast rental labels are exactly `<cluster_name_on_cloud>-head` or `-worker`.
The provider adapter filters by exact label and uses the resulting contract IDs
for stop/destroy. A caller-supplied `create_instance_kwargs.label` overrides
the expected label, so the finite projection must prohibit that override.
[Rental naming](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py),
[provider filtering and destruction](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/instance.py)

Proposed supervisor ownership record:

- Skywright Run ID, provider account binding, SkyPilot user/workspace, Managed
  Job ID, exact task name and cloud cluster name.
- Contract IDs observed under those exact labels after dispatch, with creation
  time and assessed host/offer attributes where available.
- Dispatch deadline, tunnel process identity, endpoint and public host-key
  fingerprint for each contract generation.

Labels establish a lookup relationship, not cryptographic ownership. Reject
pre-existing collisions and ambiguous matches. Never select a rental by a
generic prefix, GPU type, account-wide first result, or `endswith('-head')`.
Persist observed contract IDs before taking corrective action. Confirm each
contract's binding and label again before emergency provider deletion.

## Recovery and shutdown

Preemption can replace the contract while preserving the logical cluster name.
A tunnel is therefore owned by a contract generation, not just a Managed Job
ID. Close the old tunnel, re-resolve the exact owned replacement, establish a
new connection, and repeat storage readiness. Gate dataset access on readiness
with a finite timeout that includes provisioning time. A disappeared handle
can be a normal recovery interval, not proof that cleanup finished.
[Controller recovery](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/controller.py)

At the deadline, request cancellation of the specific Managed Job before
emergency deletion; otherwise its controller may recreate a deleted rental.
Track every observed contract through confirmed provider absence, including
replacements racing with cancellation. Preserve the tunnel long enough for a
bounded final artifact upload, then close it. The supervisor must survive the
interactive session and retain its ownership record across API-server restarts.

Pinned recovery has no general finite replacement limit. Termination retries
and network failures can exceed the normal polling interval. Reserve time and
credit for cleanup and a racing replacement, as established in the price-guard
research; do not claim a strict cloud-side cancellation deadline. If a paid
rehearsal shows SSH or storage incompatibility, cancel and verify destruction
instead of widening public network access automatically.
[Recovery strategy](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/jobs/recovery_strategy.py),
[price and runtime findings](issue-283-vast-launch-price-guard.md)
