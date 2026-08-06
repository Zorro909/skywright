---
status: accepted
---

# Persist metrics as TensorBoard events in the Run Store

Skywright owns metric persistence and binds to no experiment tracker. A run's metrics are TensorBoard event files written by the Run Context into that run's own Run Store, under a `metrics/` prefix beside its checkpoints, samples, and artifacts. ADR 0001 already fixed the event encoding and put the metric catalog in the Run Store; this decision fixes the durable home, the write protocol, the read path, and the visualization surface. Skywright stores no metric value anywhere else — not in its database, not in a tracker — so the Run Store is the source `Q5` demands be *determined* for the metrics of completed runs rather than casually persisted in the backend, and `Q1` is satisfied structurally rather than by argument.

## Durability and resume

`O2` is served best-effort. A bounded tail of metric points is lost when an instance dies, and no protocol couples metric durability to the checkpoint write. Strict gap-freedom was available — commit metrics before the checkpoint that covers them, then truncate above the checkpoint step on resume — but it constrains the write path permanently to protect points describing work the run is about to redo anyway, and metrics do not carry enough value to buy that.

The replay artifact is still removed, because TensorBoard gives it away. On resume from checkpoint step `T`, the writer is constructed with `purge_step=T`; that emits a `SessionLog START`, and TensorBoard discards every event above step `T` when reading. Stored bytes are never rewritten and no ordering constraint enters the write path, but a resumed run's curves do not double-draw across the rewind.

## Write protocol

No portable append exists: S3 `PutObject` replaces whole objects, multipart parts are unreadable until completion, and the append extensions that do exist are not something an S3-compatible bucket generally offers. Neither extreme is taken. The open event segment is re-`PUT` on each flush and sealed on a size or age threshold, after which a new segment starts in the same directory. File count stays proportional to run length over the roll threshold rather than over the flush interval, and each upload is bounded by the threshold rather than by the run's whole history. Defaults are a 30-second flush and a 4 MB roll, both configurable; scalar events are tens of bytes, so a run emitting ten metrics per step at one step per second seals a segment roughly every two hours.

Correctness rests on two properties: `PutObject` is atomic, so a reader sees the old object or the new one and never a torn one; and because the writer only appends, each replacement is a byte-identical prefix-extension of what it replaces, so an offset-based incremental reader continues across it without re-parsing.

## Reading, and the visualization surface

`O3` is pull over push-to-storage. The training process writes only to its Run Store; every reader reads the Run Store. Nothing reaches into the training machine, no component must be permanently reachable while a run executes, and there is no held connection to lose at preemption. The acceptable delay `O3` asks for is the flush interval — a configuration number, not an architectural property.

Visualization is stock TensorBoard and nothing else; Skywright builds no charting. One instance serves exactly one run, launched against `<run-store>/<run-id>/metrics` with the metric location's endpoint configured. Standalone TensorBoard implements an S3 filesystem over boto3 and honors `S3_ENDPOINT`, so no filesystem mount, no `--logdir_spec`, and no symlink tree is required. Auto-reload has been off by default since TensorBoard 2.3.0 and segmented directories need `--reload_multifile`; both are launch flags Skywright owns rather than knowledge a user needs. The same link serves a running run, so `O3` needs no separate live-metric transport.

The backend owns instance lifecycle and proxies to it, so no TensorBoard port is ever directly exposed. Accessing the link for an instance that is not running spins one up; an idle timeout stops it unconditionally, without consulting whether the run is still active, since keeping one warm for a live run nobody is watching is exactly the idle cost `U5` forbids. An instance holds no state beyond a logdir pointer, so stopping it loses nothing and respawning is transparent.

## Metric content

System metrics (`O4`) share the run's event stream under a reserved, library-owned namespace that a Training Project can neither declare into nor write to. Attribution of bottlenecks is `O4`'s stated purpose and requires the same step and time axis as the training metrics; a separate channel would force correlation after the fact. Two mechanically different kinds sit there: throughput and data-loading wait are step-indexed and derived inside the Run Context from the Step reports it already receives, while machine counters are sampled by a background sampler on a wall-clock cadence.

The resolved Run Configuration is exported into the event directory as TensorBoard HParams metadata at run start. It is written once, never read back, and the Run Definition remains authoritative — the export exists so that a metric directory is self-describing when detached from the backend, whether during a post-mortem or when several runs' metrics are downloaded to be compared by hand.

`U1` needs a run's progress without a metric index existing anywhere. The training process therefore writes a small Progress Record into its Run Store, overwritten each flush, holding the current step, the target step, and the time it was written. It is Skywright-originated with no other source, and its stamp makes it the aged intermediate result `Q2` requires. It is one scalar pair per run rather than a series, so no second copy of metric data is created.

## Considered options

Hosted trackers were rejected on `K5`: W&B or Comet as the metric sink makes every local run depend on a cloud service at runtime, free tier or not. Self-hosted trackers were rejected on operating cost and `Q6` — MLflow adds a server, database, and artifact store; ClearML adds MongoDB, Elasticsearch, and Redis with coordinated backups — each a new stateful component whose loss loses metrics, to store data the training process already writes durably. `docs/research/metric-persistence-options.md` established that no surveyed tracker guarantees gap-free `O2` on its own, so binding to one would not have bought the guarantee either. An optional tracker mirror was rejected because it would be a second project-facing metric contract through which undeclared-metric rejection could be bypassed. A single Skywright-owned metric namespace shared by all runs was rejected because, with visualization confined to one endpoint per instance, it would drag every Run Store behind one endpoint, forcing either local runs to push checkpoints to a cloud bucket or cloud instances to push them back over a home uplink.

## Consequences

`O6` is satisfied as a property of the data, not as a rendered view: uniform capture under `O1`, metric semantics declared before the run, and run selection metadata queryable from the database. Comparing two runs today means opening two TensorBoard tabs, or downloading both metric directories. Nothing forecloses a real comparison surface later — a multi-run view is reachable by mounting each endpoint and composing a symlink tree, which TensorBoard's walk follows — but this map does not build one.

`C6` bounds retained checkpoints and does not reach metrics, which persist for the life of the Run Record. The Run Store's retention policy is therefore not uniform across what it holds. Because the backend proxies, `U5` depends on backend availability even though the metric data does not. The metric location's credentials must reach a spun-up instance, which is a case of the still-open credentials transport question, and where that instance runs remains open with the backend deployment shape. Full AMD ROCm parity for machine counters was not established from primary sources and needs verifying on the local target; application-derived throughput and data-loading wait are unaffected.
