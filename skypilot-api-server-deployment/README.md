# SkyPilot API server image

This module builds the separate SkyPilot API server used by the private Skywright control plane.
It is not a backend sidecar. The image runs SkyPilot's supported server entry point directly as
PID 1 on port 46580.

## Version pairing

The root Maven property `skypilot.version` is the only SkyPilot version pin. The image installs the
exact dependency set in `graalpy-environment/graalpy.lock`, which is also the backend GraalPy
client's resolved environment. Maven validation stops the build if the lock does not contain the
root pin exactly.

The runtime is CPython 3.12.11 on the immutable
`python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7`
base. The build accepts binary distributions only, so a locked native dependency without a wheel
for that runtime fails packaging instead of compiling against an unqualified toolchain.

## Build and inspect

Run these commands from the repository root with a Docker-compatible daemon:

```bash
./mvnw -pl skypilot-api-server-deployment package

docker image inspect skywright-skypilot-api-server:0.1.0-SNAPSHOT \
  --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}}'

docker image inspect skywright-skypilot-api-server:0.1.0-SNAPSHOT \
  --format '{{json .Config.Labels}}'
```

The OCI labels record the source revision, Skywright image version, Python runtime, and SkyPilot
version. They contain no runtime configuration.

Use `verify` to build the production image and run its packaged-process tests:

```bash
./mvnw -pl skypilot-api-server-deployment verify
```

The tests use the repository-pinned PostgreSQL image. They run the server with a read-only root
filesystem, check `/api/health`, verify fixed non-root PID 1 execution and safe failure output,
perform a bounded SIGTERM shutdown, replace the container, and read PostgreSQL-backed user state
and an uploaded file through supported SkyPilot endpoints.

## External state

Create a dedicated PostgreSQL database and role for SkyPilot. The role must own that database so
SkyPilot can run its schema migrations. Supply the connection URI only through the runtime
environment variable `SKYPILOT_DB_CONNECTION_URI`. The image has no SQLite fallback: missing or
malformed external database configuration stops startup with exit code 78, and diagnostics never
print the URI.

The image uses these writable paths:

| Path | Contents | Retention |
| --- | --- | --- |
| `/tmp` | Process locks and bounded temporary files | tmpfs, discarded on replacement |
| `/var/lib/skypilot/.sky` | SkyPilot runtime files and local locks | External volume |
| `/var/lib/skypilot/sky_logs` | Retained operation and controller logs | External volume |
| `/var/lib/skypilot/.sky/api_server/clients` | Submitted-file staging and retained content-addressed blobs | External volume |

The three retained paths are nested under `/var/lib/skypilot`, so one volume mounts them without
hiding SkyPilot's expected home layout. PostgreSQL remains the authority for database state. The
volume holds only the named file state. The API server writes its process logs to standard output;
operation artifacts remain under the mounted paths above.

## Run, health-check, and stop

Write the database URI to an untracked, mode-0600 environment file. Replace the host, database,
user, and password below with the dedicated SkyPilot values:

```bash
umask 077
printf '%s\n' \
  'SKYPILOT_DB_CONNECTION_URI=postgresql://<user>:<password>@<database-host>:5432/<database>' \
  > skypilot-api-server.env

docker volume create skywright-skypilot-state

docker run --detach \
  --name skywright-skypilot-api-server \
  --read-only \
  --tmpfs /tmp:rw,exec,nosuid,size=256m \
  --mount type=volume,source=skywright-skypilot-state,target=/var/lib/skypilot \
  --env-file skypilot-api-server.env \
  --publish 127.0.0.1:46580:46580 \
  skywright-skypilot-api-server:0.1.0-SNAPSHOT

curl --fail --silent --show-error http://127.0.0.1:46580/api/health | jq .

docker stop --time 25 skywright-skypilot-api-server
```

Successful health output has `status` set to `healthy` and both `version` and `version_on_disk` set
to the root `skypilot.version` pin. The image sets SkyPilot's request-drain period to 10 seconds.
The extra container timeout covers its fixed five-second propagation wait and worker cleanup. The
packaged test requires the process to exit before 25 seconds and verifies that the container has no
remaining PID.

Delete `skypilot-api-server.env` when it is no longer needed. Keep the PostgreSQL database and
`skywright-skypilot-state` volume across image replacement. Kubernetes ownership and release
bundle integration belong to later deployment tickets.
