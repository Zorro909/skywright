# SkyPilot's programmatic surfaces for a non-Python caller

## Answer

For a Java 25 / Spring Boot caller, SkyPilot 0.13.0 exposes two practical
out-of-process surfaces:

1. **Spawn the `sky` CLI.** This is the documented and supported non-Python
   surface. It delegates to the same API server as the Python SDK, but only a
   subset of read commands has JSON output and most mutating commands stream
   human-oriented output.
2. **Call the API server's HTTP endpoints directly.** This is technically
   possible and has broad feature coverage. The FastAPI application serves an
   automatically generated OpenAPI document, Swagger UI, and ReDoc. However,
   SkyPilot does not document raw REST/OpenAPI as a public client contract, does
   not ship a non-Python client, and leaves important protocol behavior outside
   the schema: API-version headers, two-phase request completion, log control
   messages, local-file upload, and Python-pickled values/errors.

The second option is therefore **an internal transport that can be integrated
against with deliberate version pinning**, not a clean language-neutral SDK.
The CLI is more supportable but is not uniformly machine-readable. SkyPilot's
cluster-side gRPC services are also not a third public control-plane option:
they are an insecure internal Skylet protocol used by the API server/backend to
talk to a provisioned cluster.

For Skywright, a remote API server should be treated as a permanently available
control-plane dependency. The docs guarantee that requests survive *client*
disconnect, not API-server loss. A plain process already executing on a worker
may continue at the infrastructure level if the server disappears, but control,
status, request logs, and new operations do not; in the default remote Managed
Jobs mode, the API server itself manages provisioning, monitoring, recovery,
and cleanup. Persistent storage/external database and an HA deployment are
therefore operational parts of a direct-REST or CLI bridge, not optional
details.

No official source promises that a held log HTTP connection remains continuous
through worker preemption. The safe contract is: keep the request/job ID, expect
to reconnect, and poll status. Managed Jobs recover the workload, while the
Python client retries transient stream failures and skips replayed lines. A raw
Java client would have to reproduce that behavior.

## Surface inventory

| Surface | What a non-Python caller gets | Contract assessment |
|---|---|---|
| `sky` CLI subprocess | All documented CLI operations; selected JSON reads; streaming logs; process exit codes | Public/documented, but stdout is mostly presentation and JSON is not universal |
| Raw HTTP to API server | Broad cluster, job, managed-job, serve, storage, volume, user, workspace, SSH-pool, recipe, request-management, upload, and WebSocket routes | Real transport, generated OpenAPI, but no documented public REST guide or generated non-Python SDK |
| Dashboard HTTP client | Evidence that browser JavaScript can call the same HTTP routes | Product UI implementation, not a caller contract |
| Skylet gRPC | Job/status/log/autostop/serve RPCs on each provisioned cluster | Internal, insecure backend-to-cluster protocol; not an orchestration entry point |
| User-built Python sidecar/service | Any Python SDK behavior behind a project-owned RPC contract | Not supplied by SkyPilot; the wrapper and its stability become Skywright's responsibility |

## Evidence

### 1. API-server shape and OpenAPI

- SkyPilot documents a client-server architecture: every CLI/SDK call sends a
  request to the API server and streams logs back. The first local call starts a
  local server automatically; teams can deploy a shared remote server.
  Interrupting the client leaves the request running on the server. (✓ VERIFIED
  — [Asynchronous Execution](https://docs.skypilot.ai/en/stable/reference/async.html))
- The 0.13.0 server is a FastAPI application. It registers direct cluster,
  request, upload, storage, and WebSocket endpoints and includes routers for
  managed jobs, serving, users, workspaces, volumes, SSH node pools, and recipes.
  (✓ VERIFIED — [server construction and routers](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L1013-L1089),
  [core route declarations](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L1246-L2077))
- SkyPilot does not override FastAPI's `openapi_url`, `docs_url`, or
  `redoc_url`. FastAPI's defaults are `/openapi.json`, `/docs`, and `/redoc`.
  Thus a running SkyPilot API server serves an OpenAPI schema and interactive
  docs at those paths. The `prefix='/api/v1'` argument in SkyPilot's `FastAPI`
  constructor is an extra keyword, not FastAPI's route-prefix mechanism; most
  route declarations are consequently unversioned paths such as `/launch` and
  `/api/get`. (✓ VERIFIED — [SkyPilot app construction](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L1013),
  [FastAPI defaults](https://fastapi.tiangolo.com/reference/fastapi/#fastapi.FastAPI))
- Core operation endpoints are asynchronous submission endpoints. For example,
  `POST /launch` schedules work and has a `None` response; the request ID is
  returned in `X-Skypilot-Request-ID`. `GET /api/get?request_id=...` then waits
  for terminal request state and returns the encoded `RequestPayload` (or an
  encoded error under HTTP 500). (✓ VERIFIED —
  [`/launch`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L1842-L1857),
  [request-ID client handling](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/common.py#L622-L631),
  [`/api/get`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L2350-L2386))
- Request bodies are mostly expressible as ordinary JSON. Tasks/DAGs are sent
  as YAML strings (`LaunchBody.task`, `ExecBody.task`, `DagRequestBody.dag`), so
  a Java caller can submit core cluster and managed-job operations without
  constructing Python objects. Local workdirs/file mounts additionally require
  SkyPilot's blob-upload protocol and task rewriting. (✓ VERIFIED —
  [request payloads](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/payloads.py#L240-L365),
  [upload routes](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L1634-L1839))

### 2. Why generated OpenAPI is not equivalent to the Python SDK

- The OpenAPI operation response for many mutations only says that submission
  returns no body; it cannot describe the second `/api/get` result by originating
  operation. `RequestPayload` declares `entrypoint`, `request_body`,
  `return_value`, and `error` merely as strings. (✓ VERIFIED —
  [`RequestPayload`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/payloads.py#L1005-L1025))
- The actual wire values are not wholly language neutral. `entrypoint` and
  `request_body` are base64-encoded Python pickles. Errors contain a pickled
  serialized exception. Return values are JSON-serialized by operation, but
  resource handles and some resource values can still be base64 Python pickles;
  the Python SDK has operation-specific decoders that reconstruct enums,
  Pydantic models, resources, and handles. (✓ VERIFIED —
  [request encoding](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/requests.py#L209-L253),
  [wire payload encoding](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/requests.py#L309-L324),
  [pickle encoder](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/serializers/encoders.py#L25-L48),
  [Python decoders](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/serializers/decoders.py#L25-L141))
- A caller can avoid some pickles on read paths (for example, cluster status has
  `summary_response` and `include_handle` flags), but there is no documented
  non-Python response profile that guarantees pickle-free results across all
  endpoints. (✓ VERIFIED mechanism; ? INFERRED absence —
  [`StatusBody`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/payloads.py#L377-L388))
- The HTTP surface is broad because the Python SDK itself is an HTTP client, but
  the SDK performs material client-side work: turns `Task`/`Dag` objects into
  YAML, resolves and uploads local paths, applies compatibility branches,
  decodes results/errors, manages authentication cookies/tokens, writes local
  SSH configuration, renders rich-status control messages, and retries streams.
  A raw client must duplicate the subset it uses. (✓ VERIFIED —
  [Python REST client](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/sdk.py),
  [authentication request preparation](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/common.py#L305-L346))
- Python-only *high-level abstractions* remain even where their eventual work is
  lowered onto ordinary endpoints. The clearest 0.13.0 example is Sky Batch:
  `Dataset.map()` accepts a Python function decorated with
  `@sky.batch.remote_function`, serializes its source and I/O implementations,
  creates task metadata, and drives Managed Jobs/pools. There is no corresponding
  public REST operation that accepts a Java function. Custom Python cloud,
  policy, serializer, and plugin extension points are similarly not a raw-REST
  feature. (✓ VERIFIED — [Sky Batch release description](https://github.com/skypilot-org/skypilot/releases/tag/v0.13.0),
  [`Dataset.map` implementation](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/batch/dataset.py))

**Completeness conclusion:** for Skywright's single-node training needs, core
launch/exec/status/log/cancel/Managed Jobs operations exist in HTTP form. There
is no official feature-completeness matrix, no official Java client, and no
promise that every Python abstraction has a direct language-neutral equivalent.

### 3. Authentication

- A local API server is started and used by the local client. A production
  remote server supports basic authentication/user management, OAuth2/SSO or an
  external auth proxy, and service-account tokens. RBAC is tied to the
  authenticated server deployment rather than encoded in individual request
  bodies. (✓ VERIFIED — [Authentication and RBAC](https://docs.skypilot.ai/en/stable/reference/api-server/examples/api-server-auth-proxy.html),
  [Helm authentication settings](https://docs.skypilot.co/en/stable/reference/api-server/helm-values-spec.html#auth))
- Service accounts are the intended automation mechanism. Their `sky_...`
  bearer tokens can be supplied by the CLI or
  `SKYPILOT_SERVICE_ACCOUNT_TOKEN`; server middleware accepts
  `Authorization: Bearer sky_...`. OAuth/auth-proxy flows use cookies. (✓
  VERIFIED — [CLI login reference](https://docs.skypilot.co/en/latest/reference/cli.html#sky-api-login),
  [bearer middleware](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L394-L446),
  [client authentication selection](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/common.py#L305-L325))
- The Helm chart says service-account JWT secrets are persisted in the database
  across restarts. Persistent storage is enabled by default for managed-job logs
  and uploaded files; disabling it is described as prone to data loss. (✓
  VERIFIED — [Helm chart reference](https://docs.skypilot.co/en/stable/reference/api-server/helm-values-spec.html))

### 4. API-server lifetime

- All CLI/SDK requests execute on the API server, and client interruption only
  detaches from them. Therefore the server must remain alive for submitted
  SkyPilot request execution and for request status/logs/cancellation. (✓
  VERIFIED — [Asynchronous Execution](https://docs.skypilot.ai/en/stable/reference/async.html))
- Managed Jobs own the full workload lifecycle: provision, monitor, recover,
  and clean up. In the default mode with a remote API server, the API server
  manages jobs directly. This makes server availability load-bearing for
  preemption recovery, not merely for accepting the initial launch. (✓ VERIFIED
  — [Managed Jobs: How it works](https://docs.skypilot.co/en/stable/examples/managed-jobs.html#how-it-works))
- SkyPilot's deployment docs recommend Kubernetes/Helm for reliability, offer
  persistent/external database options, rolling-upgrade controls, and HA
  guidance. Those mechanisms imply restart/recovery is designed, but do not
  turn the server into an optional component while operations are in flight.
  (✓ VERIFIED — [API server deployment](https://docs.skypilot.co/en/latest/reference/api-server/api-server-admin-deploy.html),
  [Helm chart reference](https://docs.skypilot.co/en/stable/reference/api-server/helm-values-spec.html))
- No source examined promises that terminating the API server terminates an
  already-running plain cluster job. The cautious distinction is that the
  worker process may continue, while the SkyPilot control plane is unavailable.
  (? INFERRED; see **Could not establish**)

### 5. CLI as a machine interface

- The CLI is the only documented non-Python automation surface, and a Java
  service can run it as a subprocess. It still requires a compatible SkyPilot
  Python distribution on the service host. Its default output includes tables,
  spinners, hints, ANSI styling, and streamed logs; that presentation should not
  be parsed. (✓ VERIFIED — [CLI reference](https://docs.skypilot.co/en/latest/reference/cli.html),
  [async CLI example](https://docs.skypilot.ai/en/stable/reference/async.html#clis))
- In 0.13.0, `-o/--output json` is explicitly available on `sky status`,
  `cost-report`, `queue`, `check`, `gpus list`, `jobs queue`, `api status`,
  `api info`, and `workspace info`. Mutating commands such as `launch`, `exec`,
  `jobs launch`, `stop`, and `down` do not have a general JSON-result mode. (✓
  VERIFIED — [CLI command definitions](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/cli/command.py),
  [shared output option](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/cli/flags.py#L473-L489))
- Job-related commands have deliberate exit status values: `0` succeeded,
  `100` failed, `101` not finished, `102` not found, and `103` cancelled.
  `sky logs --status` documents those values, and log-tail commands propagate
  the job result. Usage/transport/other command failures otherwise follow
  Click/Python's ordinary nonzero process behavior; there is no published
  global typed exit-code table for every CLI command. (✓ VERIFIED —
  [job exit-code enum](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/exceptions.py#L638-L705),
  [`sky logs --status`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/cli/command.py#L2739-L2794))
- The repository tests JSON output, which makes accidental breakage less likely,
  but the user/developer docs do not publish a versioned JSON schema or a
  separate compatibility guarantee for CLI JSON fields. Consumers should pin
  the CLI version and contract-test the commands/fields they use. (✓ VERIFIED
  tests; ? INFERRED recommendation —
  [JSON-output tests](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/tests/unit_tests/test_sky/test_cli_json_output.py))

### 6. Logs, status, disconnect, and preemption

There are two different log streams:

- **API-request logs:** `GET /api/stream?request_id=...` is a chunked text
  response; the CLI exposes it as `sky api logs <request-id>`. It reports the
  launch/status/etc. request's server-side progress, not necessarily the full
  stdout of the workload. (✓ VERIFIED —
  [`/api/stream`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L2389-L2620),
  [request CLI](https://docs.skypilot.ai/en/stable/reference/async.html#managing-requests))
- **Workload logs:** `POST /logs` streams a cluster job, and managed-job/serve
  routers have corresponding log routes. The CLI exposes `sky logs`,
  `sky jobs logs`, and `sky serve logs`. Disconnecting a log viewer does not
  cancel the workload; the server configures log-tail requests with
  `kill_request_on_disconnect=False`. (✓ VERIFIED —
  [`/logs`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L2023-L2051),
  [stream disconnect behavior](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/stream_utils.py#L401-L445))
- Status is request/response polling, not a documented event stream: use
  `/status`, `/jobs/queue`, `/api/status`, or the corresponding CLI JSON
  commands. The Managed Jobs CLI docs recommend `watch -n60 sky jobs queue`.
  No status SSE/WebSocket subscription was found. (✓ VERIFIED endpoints and
  docs; ? VERIFIED absence in examined routes —
  [Managed Jobs CLI reference](https://docs.skypilot.co/en/latest/reference/cli.html#sky-jobs-queue))
- The Python SDK wraps stream calls in transient-error retries. Resumable
  streams track processed lines, reconnect, suppress already printed lines,
  and count heartbeats as progress. A generated OpenAPI client will not acquire
  any of this behavior automatically. (✓ VERIFIED —
  [stream resume logic](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/sdk.py#L142-L215),
  [`stream_and_get`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/sdk.py#L2450-L2529))
- Managed Jobs explicitly recover from node failure/preemption by tearing down
  the old temporary cluster, provisioning another, and restarting the job.
  User logs and controller/recovery logs remain addressable by job ID. The docs
  do **not** say the same TCP/HTTP log connection remains open across that
  transition. (✓ VERIFIED recovery; ? UNDOCUMENTED connection continuity —
  [Managed Jobs recovery](https://docs.skypilot.co/en/stable/examples/managed-jobs.html#when-will-my-job-be-recovered))

**Operational consequence:** a bridge should model log delivery as an
at-least-once/reconnectable stream and status as polling. Deduplicate/reconcile
using stable request/job identity rather than treating one socket as the run.

### 7. Versioning and stability

- SkyPilot has a real client/server compatibility protocol. In 0.13.0 the wire
  API version is `56`, minimum compatible API version `24` / semantic version
  `0.11.0`, exchanged in `X-SkyPilot-API-Version` and `X-SkyPilot-Version`.
  Missing headers are temporarily accepted for old-client compatibility; an
  incompatible declared version gets HTTP 400. A non-Python client should send
  and validate these headers. (✓ VERIFIED —
  [version constants](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/constants.py#L7-L31),
  [compatibility check](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/versions.py#L90-L143),
  [middleware](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/server.py#L983-L1010))
- The developer policy guarantees compatibility between adjacent minor
  client/server versions starting with 0.10.0. Payload additions need defaults;
  incompatible payload changes require parallel old/new body types until the
  compatibility floor advances. Old compatibility code may then be removed.
  (✓ VERIFIED — [backward-compatibility guidelines](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/developers/CONTRIBUTING.md#backward-compatibility-guidelines),
  [payload compatibility rules](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/server/requests/payloads.py#L1-L27))
- That guarantee is aimed at official SkyPilot clients, whose compatibility
  branches know which fields to omit, how to decode each result, and how to
  fall back. It is not a promise that an arbitrary OpenAPI-generated client will
  remain source- or behavior-compatible. No official REST versioning guide,
  OpenAPI compatibility policy, Java SDK, or stable CLI JSON schema was found.

## Could not establish

- A documented guarantee that `/openapi.json` itself is a supported public
  artifact, rather than the default by-product of using FastAPI.
- A documented, exhaustive REST-vs-Python feature matrix.
- Any official non-Python SDK or generated client.
- A documented status push/SSE feed. Status endpoints and CLI examples are
  polling based.
- A guarantee that one held API/log HTTP connection remains open when a worker
  is preempted. Managed-job recovery and client stream retry are documented or
  implemented; socket continuity is not.
- A documented guarantee about an already-running plain cluster job when the
  API server process is terminated. Client disconnect is explicitly safe;
  server disappearance is a different failure.
- A versioned stability promise for the field set of each CLI JSON response or
  a universal exit-code taxonomy beyond job statuses.

## Source version

- SkyPilot **v0.13.0**, commit
  `b1431e52d97c22e9bb8fa8b67f162543754ddaf5`, released 2026-07-22 and marked
  latest when queried 2026-08-04. ([release](https://github.com/skypilot-org/skypilot/releases/tag/v0.13.0))
- Official SkyPilot stable/latest documentation and FastAPI reference queried
  2026-08-04.
