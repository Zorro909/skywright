# Skywright

Skywright provides a portable contract for defining and running machine-learning training work across local and cloud targets without taking ownership of project-specific training control flow.

## Language

**Training Project**:
A consumer of Skywright that owns model and data semantics, training control flow, and project-specific configuration. Its identity is Skywright-owned and survives a change of registry or repository.
_Avoid_: Plugin, managed trainer

**Training Project Version**:
An immutable, identifiable version of a Training Project, labelled by the commit and pipeline that produced it and resolving to one Training Project Image per accelerator backend it declares, together with the Project Configuration Contract that its code expects. It exists only once its build has published every image it declares and that contract.
_Avoid_: Latest project, floating project version, working tree

**Training Project Image**:
The built container image carrying one Training Project Version's source and locked dependencies on top of an Environment Profile. One exists per accelerator backend that version declares, and its digest rather than its tag is what a Run Definition pins.
_Avoid_: Project container, image tag, local build

**Project Configuration Contract**:
The version-bound definition of a Training Project's configuration shape and defaults. It belongs to exactly one Training Project Version and cannot be mixed with another version's code.
_Avoid_: Project config template, arbitrary parameters

**Training Contract**:
The library-owned, validated standards a Training Project must follow for configuration, datasets, checkpointing, metrics, and resume behavior. It does not prescribe the project's training loop.
_Avoid_: Training framework, callback framework

**Run Context**:
The explicit library-provided runtime boundary through which a Training Project accesses resolved datasets, metrics, persistence, and resume state while retaining ownership of its training loop.
_Avoid_: Trainer, callback host, global context

**Target Storage**:
A pre-registered, credentialed storage destination that Skywright addresses but never creates. Many Storage Locations live within one; datasets and run outputs never share one.
_Avoid_: Bucket, provider, storage backend

**Storage Location**:
A concrete path within one Target Storage, and the single addressing concept for durable content — whether that content is a Dataset's payload or one run's Run Store. Training Project code never sees its storage protocol.
_Avoid_: Dataset Location, path, URL

**Dataset**:
A stable, versioned lineage of immutable Dataset Definitions with a mutable pointer to the definition currently preferred for catalog display and lifecycle recommendations. Run Definitions never follow this pointer; they pin an exact Dataset Definition version.
_Avoid_: Dataset Definition, latest dataset version

**Dataset Definition**:
A validated, immutable snapshot of a durable input corpus, identified by a stable dataset identity and version, with its integrity manifest and shared loading configuration; payload semantics such as image, text, or video remain with the Training Project. Its version is a human-assigned label plus a mandatory content fingerprint, or an abbreviated fingerprint when no label is supplied; any content transformation creates a new definition.
_Avoid_: Dataset object, data path, replay buffer

**Dataset Catalog Record**:
Skywright-owned mutable metadata for one Dataset Definition, designating exactly one authoritative Storage Location and identifying currently known Dataset Replicas and Dataset Caches together with their verification, storage, and usage facts. A verified replica may replace the authority without changing the Dataset Definition; changed content requires a new version.
_Avoid_: Dataset Definition, dataset manifest

**Dataset Replica**:
A verified, durable, byte-preserving, non-authoritative Storage Location with its own stable catalog identity and generations, independent of the Dataset Definition version. Refresh creates a new verified generation; deprecation prevents new leases, replacement waits for existing leases to end, and the old generation is deleted only after its replacement is published.
_Avoid_: Dataset Cache, backup, stale copy

**Dataset Lease**:
A Run Record's explicit claim on an exact Dataset Replica generation selected for a scheduled, running, or resumable execution. A generation is unused only when deprecation has prevented new leases and every existing lease has ended.
_Avoid_: Last-used timestamp, storage lock

**Dataset Cache**:
A bounded, non-authoritative local copy of Dataset content used only to accelerate access. Skywright tracks its host or run ownership, storage use, verification age, and last use, but it never replaces an authoritative Storage Location or verified Dataset Replica.
_Avoid_: Dataset replica, dataset source

**Generated Experience**:
Training data produced during a run, such as RL rollouts or replay-buffer entries. It is project-owned run state rather than a Dataset, though it may be persisted for inspection or later promoted into one.
_Avoid_: Dataset

**Run Configuration**:
The fully resolved configuration within a Run Definition, composed from library-defined common options and project-defined options. Every default is materialized before the run is accepted.
_Avoid_: Config file, parameters

**Run Submission**:
A request to create a run from a specific Training Project Version, configuration overrides, and requested target capabilities. It is intent awaiting resolution, not the repeatable run artifact.
_Avoid_: Run Definition, job

**Run Definition**:
The immutable, fully resolved description of what should run, including its Training Project Version, Run Configuration, requested target capabilities, and the Target Storage its execution writes to. Changing any of those creates a new Run Definition.
_Avoid_: Run Submission, Run Record, mutable job configuration

**Orchestrator Task Specification**:
A Run Definition projected into the orchestrator's own vocabulary, expressed against the orchestrator's documented task schema. It is a derived, throwaway description produced at submission, never a second authority on what should run.
_Avoid_: Run Definition, task YAML, job spec

**Run Record**:
The Skywright-originated durable record of one Run Definition's execution, carrying the immutable run identity that names its orchestrator job and the submission attempt that started it. It holds no orchestrator-sourced fact and no stored status: lifecycle state is derived, and the infrastructure actually selected is a Retained SkyPilot Fact. It also holds its Run Store's current Storage Location, which changes when the store moves, and — when the run was seeded from an earlier one — that predecessor and the exact checkpoint. A clone receives a new Run Record.
_Avoid_: Run Definition, Run Store, status field

**Run Lifecycle State**:
A run's waiting, running, interrupted, finished, or failed condition, computed per read from Retained SkyPilot Facts, the Run Definition, and the Run Termination Report. It is never stored, so a corrected mapping corrects every past run. Whether a source could be reached is a separate fact, not a sixth state.
_Avoid_: Run status column, unknown state, SkyPilot job status

**Retained SkyPilot Fact**:
An immutable orchestrator-sourced fact Skywright appends to outlive SkyPilot's retention policy, kept in storage of that provenance alone and joined to a run only by run identity. The source wins while it still answers; retained rows supplement only what it has purged.
_Avoid_: Mirrored state, run status cache, Skywright-originated fact

**Orchestrator Operation**:
One control action the backend has handed to the orchestrator and is waiting on. It exists only while the process that started it lives: it is never persisted, so losing it loses the view of an action, never the action itself.
_Avoid_: Request id, job handle, pending action, command

**Run Termination Report**:
The record a Training Project's process writes to its Run Store when it terminates of its own accord, naming the cause SkyPilot cannot supply. Its absence is not a diagnosis: it means the process did not get to speak.
_Avoid_: Exit code, crash log, preemption signal

**Environment Profile**:
The library-owned base image for one accelerator backend, carrying the Skywright library and the accelerator-compatible runtime dependencies a Training Project must not choose between or replace. It is the base a Training Project Image is built on, not something a Run Definition pins.
_Avoid_: Project environment, device configuration, run-time dependency install

**Metric Definition**:
A metric's declared identity and comparison semantics. Every recorded metric must have a definition established before the run begins.
_Avoid_: Dynamic metric, log field

**System Metric**:
A machine or runtime measurement recorded under a reserved, library-owned namespace that a Training Project can neither declare into nor write to. Step-indexed kinds are derived in the Run Context from reported Steps; time-sampled kinds come from a background sampler on a wall-clock cadence.
_Avoid_: Project metric, separate monitoring channel

**Metric Segment**:
An immutable TensorBoard event file holding part of one run's metric history. The open segment is replaced whole on each flush and sealed once it crosses a size or age threshold; every replacement extends its predecessor byte-for-byte, so a reader tracking an offset continues across it.
_Avoid_: Metric row, appendable log, tracker run

**Metric View**:
The ephemeral, stateless TensorBoard instance serving exactly one run's Metric Segments, spun up on access through the backend's proxy and stopped when idle. It holds no data of its own, so stopping it loses nothing and respawning is transparent.
_Avoid_: Metric store, dashboard service, retained instance

**Run Store**:
The mandatory durable home for a run's checkpoints, samples, artifacts, and metrics: one Storage Location per run. Its Target Storage may differ between local and external execution and may change once the run is terminal, without changing the Training Project's contract — so readers resolve it through the Run Record rather than the Run Definition. It is the determined source for a completed run's metrics, never a location something else copies them out of.
_Avoid_: Bucket, output directory

**Repatriation**:
The move of a terminal run's Run Store to a configured destination Target Storage, copying and verifying before anything is deleted. It is a no-op when the run already executed against that destination, and a failure leaves the store where it is rather than degrading the run.
_Avoid_: Backup, archive, sync

**Transfer Worker**:
The role that copies content between two Storage Locations, verifies it, publishes it, and deletes the source only when asked. One protocol serves repatriation, seeding a resumed run, and dataset materialization; it never runs on a training instance.
_Avoid_: Backend job, sync service, upload script

**Progress Record**:
A small Skywright-originated object in the Run Store carrying a run's current step, target step, and the time it was written, overwritten on each flush. It is an aged intermediate result serving run-list progress — not a metric series, and not an index over one.
_Avoid_: Metric index, run status, stored progress

**Checkpoint State**:
The complete set of standard and project-specific resumable state a Training Project registers before training begins. A checkpoint is a durable snapshot of this declared state.
_Avoid_: State dictionary, model weights

**Step**:
A Training Project's monotonically numbered unit of committed progress and the safe boundary at which the Run Context may flush metrics, checkpoint state, or honor interruption. It need not correspond to a dataset batch or epoch.
_Avoid_: Batch, iteration, epoch

**Sample**:
A typed, inspectable training output in a common media form such as text, image, audio, or video, saved through the Run Store with library-understood metadata.
_Avoid_: Artifact, metric

**Artifact**:
Arbitrary project-owned run output persisted for inspection, debugging, or later reuse but not interpreted by the library.
_Avoid_: Sample, checkpoint, dataset
