# Skywright

Skywright provides a portable contract for defining and running machine-learning training work across local and cloud targets without taking ownership of project-specific training control flow.

## Language

**Principal Identity**:
The stable authority under which Skywright accepts and attributes a request. In the initial deployment, every caller able to reach the backend receives one built-in Principal Identity; Skywright performs no authentication and cannot distinguish individual humans. A later access layer may supply distinct identities without changing action records.
_Avoid_: User session, credential, authenticated human

**Credential Authority**:
The deployment-wide external source of truth for secret material consumed by managed Skywright roles and integrated systems. Skywright retains references to its entries rather than the secret values themselves; developer-local direct execution lies outside this authority.
_Avoid_: Skywright secret store, database secret, shared credential

**Credential Binding**:
A non-secret deployment definition associating one external resource, consuming role, and access profile with an entry in the Credential Authority. It exposes enrollment, validation, expiry, rotation, and usage metadata without exposing secret material; operator-supplied candidates become active only after validation.
_Avoid_: Credential, Vault path, database secret

**Credential Projection**:
A role-scoped runtime copy of a Credential Binding's secret material supplied to one managed process without making that process's source of configuration authoritative. A Run's projection remains fixed for its lifetime, including recovery, while later Runs receive later credential versions.
_Avoid_: Credential Authority, persisted secret, Run Definition

**Credential Projection Record**:
The immutable, non-secret evidence that one Credential Binding revision was projected to a named role for a Run or operation, including when that use began and ended. It lets rotation and revocation account for active consumers without retaining secret material or replacing the Credential Authority's audit.
_Avoid_: Credential, Vault lease, secret audit log

**Training Project**:
A consumer of Skywright that owns model and data semantics, training control flow, and project-specific configuration. Its identity is Skywright-owned and survives a change of registry or repository.
_Avoid_: Plugin, managed trainer

**Training Project Version**:
An immutable, identifiable version of a Training Project, labelled by the commit and pipeline that produced it and resolving to one Training Project Image per accelerator backend it declares, together with the Project Configuration Contract and Project Metric Contract that its code expects. It exists only once its build has published every image it declares and both contracts.
_Avoid_: Latest project, floating project version, working tree

**Training Project Image**:
The built container image carrying one Training Project Version's source and locked dependencies on top of an Environment Profile. One exists per accelerator backend that version declares, and its digest rather than its tag is what a Run Definition pins.
_Avoid_: Project container, image tag, local build

**Project Configuration Contract**:
The version-bound schema contribution, defaults, and Defaults Completion Witness supplied by a Training Project, pinned to the exact Skywright Configuration Schema against which they were checked. Its schema defines only project-owned properties within the shared Run Configuration tree, while its defaults may choose values for both project- and library-owned properties without redefining the latter; it belongs to exactly one Training Project Version and cannot be mixed with another version's code.
_Avoid_: Project config template, arbitrary parameters

**Defaults Completion Witness**:
A validation-only JSON document that fills, but never replaces, paths absent from the combined library and project defaults, proving that those possibly incomplete defaults can produce at least one valid Run Configuration. It belongs to a Project Configuration Contract and never becomes a default or enters a Run Definition.
_Avoid_: Example configuration, fallback defaults

**Training Contract**:
The library-owned, validated standards a Training Project must follow for configuration, datasets, checkpointing, metrics, resume behavior, and handling its Credential Projection without disclosure. It does not prescribe the project's training loop.
_Avoid_: Training framework, callback framework

**Run Context**:
The explicit library-provided runtime boundary through which a Training Project accesses resolved datasets, metrics, persistence, and resume state while retaining ownership of its training loop. Its first construction attempt irrevocably claims the training process, which can never attempt another; a later attempt is a Training Contract violation, and every direct run therefore begins in a fresh process. It exclusively owns the process's training-runtime setup and cooperative interruption handling, while accelerator access remains explicit and host logging remains outside its boundary.
_Avoid_: Trainer, callback host, global context

**Training Process Boundary**:
The library-owned outer boundary that creates a training process's sole Run Context, invokes the Training Project's entry point, finalizes its Execution Termination Report, and exits with the corresponding orchestration outcome. It owns process lifecycle without owning the project's training loop.
_Avoid_: Training framework, project entry point, Run Context

**Training Process Outcome**:
The success, recoverable-interruption, cancellation, or terminal-failure control result emitted by a Training Process Boundary. It controls orchestration rather than diagnosing termination: only a safely finalized interruption requests recovery, while the Execution Termination Report retains the exact cause and an abrupt loss may emit no outcome.
_Avoid_: Execution Termination Cause, Run Lifecycle State, diagnostic exit code

**Target Storage**:
A control-plane registration with a stable identity for one storage destination that Skywright addresses but never creates. Its purpose and bucket are immutable, while qualified access configuration and role-scoped Credential Binding associations may change; datasets and run outputs never share one.
_Avoid_: Bucket, provider, storage backend

**Target Class**:
One of the four execution categories for which Skywright resolves defaults: local single-GPU, local multi-GPU, cloud on-demand, or cloud spot.
_Avoid_: Provider, accelerator backend, instance type

**Storage Location**:
A concrete path within one Target Storage, and the single addressing concept for durable content — whether that content is a Dataset's payload or one run's Run Store. Training Project code never sees its storage protocol.
_Avoid_: Dataset Location, path, URL

**Dataset**:
A stable, versioned lineage of immutable Dataset Definitions with a mutable pointer to the definition currently preferred for catalog display and lifecycle recommendations. Run Definitions never follow this pointer; they pin an exact Dataset Definition version.
_Avoid_: Dataset Definition, latest dataset version

**Dataset Definition**:
A validated, immutable snapshot of a durable input corpus, identified by a stable dataset identity and version, with its integrity manifest and shared loading configuration; payload semantics such as image, text, or video remain with the Training Project. Its version is a human-assigned label plus a mandatory content fingerprint, or an abbreviated fingerprint when no label is supplied; any content transformation creates a new definition.
_Avoid_: Dataset object, data path, replay buffer

**Dataset Item**:
One canonical member of a Dataset Definition, stably identified within that definition by its ordinal; its payload semantics remain with the Training Project.
_Avoid_: Sample, input sample, record

**Dataset Item Sequence**:
The exact ordered sequence of Dataset Item identities committed by a Run, derived from its Dataset Definition and library-owned ordering inputs; the initial policy makes each epoch a deterministic permutation containing every item exactly once. Recovery, Storage Location, cache state, loader-worker count, accelerator count, and batch grouping may change retrieval or grouping but never this flattened logical order; identical decoded tensors, augmentations, or numerical results are not promised.
_Avoid_: Sample order, statistically equivalent sampling, batch order

**Dataset Cursor**:
The checkpointed `(global epoch, item offset, epoch-local Step count)` locating the next uncommitted Dataset Item in a Dataset Item Sequence. It advances only when the enclosing Step completes, so prefetched items and items from an interrupted Step are replayed.
_Avoid_: DataLoader cursor, batch cursor, items fetched

**Ordering Reset**:
An explicit Run Definition mode for a checkpoint-seeded Run that changes Dataset Definition and abandons exact sequence continuation. It restores global epoch and global Step, resets item offset and epoch-local Step count to zero, and is never inferred from changed inputs; changing the ordering seed or policy remains invalid.
_Avoid_: Automatic reset, exact continuation, warm start

**Dataset Publication**:
The all-or-nothing creation of a Dataset Definition and its authoritative remote Storage Location from a storage-ready local corpus. Before publication succeeds, neither the definition nor staged content is visible as a Dataset.
_Avoid_: Dataset preprocessing, dataset upload, materialization

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
The fully resolved, immutable, single semantic configuration tree governing the Training Contract inside a training process; orchestration and backend choices remain separate in the Run Definition. Library and project ownership do not create parallel namespaces: an option is library-owned when Skywright interprets it to provide or enforce the Training Contract, and project-owned only when its meaning belongs entirely to the Training Project. A project may choose and read a library-owned value for its own purposes but cannot redefine the property's meaning or constraints; every default is materialized before the run is accepted.
_Avoid_: Config file, parameters

**Skywright Configuration Schema**:
The versioned, content-addressed definition and property catalogue for the library-owned part of Run Configuration. A Project Configuration Contract contributes project-owned properties to it, while both project CI and the backend independently validate the resulting whole-document contract.
_Avoid_: Project schema, handwritten configuration reference

**Run Submission**:
A request to create a run from a specific Training Project Version, configuration overrides, and requested target capabilities. It is intent awaiting resolution, not the repeatable run artifact.
_Avoid_: Run Definition, job

**Run Definition**:
The immutable, fully resolved description of what should run, including its Training Project Version, Run Configuration, the exact Skywright Metric Schema identity, requested target capabilities, the Target Storage its execution writes to, and any runtime or cost ceiling. Changing any of those creates a new Run Definition.
_Avoid_: Run Submission, Run Record, mutable job configuration

**Orchestrator Task Specification**:
A Run Definition projected into the orchestrator's own vocabulary, expressed against the orchestrator's documented task schema. It is a derived, throwaway description produced at submission, never a second authority on what should run.
_Avoid_: Run Definition, task YAML, job spec

**Run Record**:
The Skywright-originated durable record of one Run Definition's execution, carrying the immutable run identity that names its orchestrator job and the submission attempt that started it. It holds no orchestrator-sourced fact and no stored status: lifecycle state is derived, and the infrastructure actually selected is a Retained SkyPilot Fact. It also holds its Run Store's current Storage Location, which changes when the store moves, and — when the run was seeded from an earlier one — that predecessor and the exact checkpoint. A clone receives a new Run Record; only a terminal Run may enter deletion, which also deletes its Run Store.
_Avoid_: Run Definition, Run Store, status field

**Run Deletion Operation**:
The durable, addressable progress of the sole confirmed request to irreversibly remove a terminal Run Record and every state exclusively owned by or attributed to it. It is separate from Run Lifecycle State, fences new work, and advances monotonically through retryable partial failure; it becomes a Run Deletion Receipt only after every known external object is absent and the Run's database state is removed.
_Avoid_: Deleting lifecycle state, synchronous delete, cancellation

**Run Deletion Receipt**:
The permanent minimal evidence that a Run Deletion Operation completed: Run and operation identities, requesting principal, request and completion times, protocol version, and successful outcome. It remains a resolvable lineage endpoint but retains no Run definition, outcome, storage address, outputs, costs, failure history, or other operational metadata, and never causes deletion to cascade into descendant Runs.
_Avoid_: Soft-deleted Run Record, Run archive, deletion operation

**Execution Attempt**:
One lifetime of the Training Project process within a Run, beginning only once its Execution Attempt Record is durable. Infrastructure recovery starts a new Execution Attempt in the same Run, while retrying a terminal Run creates a new Run rather than another attempt.
_Avoid_: Run, clone, submission attempt, retry

**Execution Attempt Record**:
The immutable Run Store record establishing an Execution Attempt's identity and the checkpoint from which it starts. Its missing Execution Termination Report means only that the attempt did not report a cause.
_Avoid_: Run Record, recovery count, termination report

**Recovery Debt**:
A Run's progress-decayed count of infrastructure recoveries: each recovery adds one and each newly published Durable Safe Point removes one, never below zero. The initial Execution Attempt adds no debt; exceeding the finite maximum fixed in the Run Definition ends automatic recovery.
_Avoid_: Recovery count, lifetime retry count, time-window retry limit

**Recovery Exhaustion Record**:
The immutable, run-level Run Store fact that a prospective recovery was refused because it would exceed the Run Definition's maximum Recovery Debt. It records the policy and evidence used by the runtime startup gate and makes the Run terminal without creating an Execution Attempt or claiming a process termination cause.
_Avoid_: Execution Termination Report, retry failure, inferred preemption

**Run Lifecycle State**:
A run's waiting, running, interrupted, finished, failed, or cancelled condition, computed per read from Retained SkyPilot Facts, the Run Definition, its Execution Termination Reports, and any Recovery Exhaustion Record. It is never stored, so a corrected mapping corrects every past run. Whether a source could be reached is a separate fact, not another state.
_Avoid_: Run status column, unknown state, aborted, SkyPilot job status

**Run Notice**:
An immutable observation concerning a Run that a Principal Identity should be able to learn about independently of whether or how it is delivered. Every newly observed terminal Run transition and every automatic resolution of an Attention Item creates one, except that successful Run deletion leaves only its Run Deletion Receipt. Later evidence corrects a Notice by appending a linked Notice rather than rewriting what Skywright observed; a Run Notice may open or refer to an Attention Item, but an informational occurrence needs none.
_Avoid_: Notification, delivery attempt, Attention Item

**Attention Item**:
A durable, addressable representation of one unresolved condition episode concerning a Run that may require human action. Reobservation updates the same open item; a condition that clears and later recurs opens a linked new item rather than reopening its predecessor. It resolves automatically when a recoverable underlying condition clears and otherwise requires an Attention Disposition; acknowledging it records awareness without resolving it.
_Avoid_: Run Notice, alert message, Run Lifecycle State

**Attention Disposition**:
An explicit Principal Identity's durable conclusion that a permanent Attention Item was remediated, accepted, or requires no action. It resolves the item without altering the Run fact that caused it.
_Avoid_: Acknowledgement, condition recovery, Run outcome

**Attention Acknowledgement**:
A Principal Identity's durable statement that it has seen an Attention Item. It stops that identity's reminders but neither resolves the item nor claims that its underlying condition changed.
_Avoid_: Attention Disposition, delivery receipt, resolution

**Attention Inbox**:
The authoritative pull-based view of durable Run Notices and Attention Items for a Principal Identity. Configured push delivery may draw attention to its contents but never replaces or removes them.
_Avoid_: Notification channel, activity feed, delivery history

**Attention Routing Policy**:
A Principal Identity's mutable selection of named push channels and unacknowledged-item reminder behavior for each kind of Run Notice and Attention Item. It is evaluated when an occurrence is recorded, independently of every Run Definition; the resulting delivery intentions do not change when the policy later changes.
_Avoid_: Run Configuration, Run Definition, channel credentials

**Attention Channel**:
A named, outbound-only destination to which Skywright can send a minimal non-sensitive attention envelope. It neither speaks for a Principal Identity nor accepts acknowledgement, disposition, retry, or Run-control commands.
_Avoid_: Attention Inbox, interactive integration, authenticated principal

**Attention Delivery**:
A durable, non-authoritative intention to project a Run Notice or Attention Item to one configured push channel. Skywright attempts it at least once under a stable identity, permits duplicate receipt, and retains exhausted failure for manual retry unless a later related occurrence has already been delivered. Related deliveries remain causally ordered within that channel; success means only that the channel accepted the payload, never that a human saw it. Delivery outcome changes neither the source occurrence nor the Attention Inbox and never creates recursive attention.
_Avoid_: Run Notice, Attention Item, authoritative notification

**Capability Availability**:
The current ability to perform one named observation, control, or enforcement capability from its required sources and services. It is reported independently of Run Lifecycle State: losing a dependency makes only affected capabilities explicitly unavailable and never invents a lifecycle value or stops a Run merely because Skywright lost visibility.
_Avoid_: Run Lifecycle State, stale fallback, global health status

**Runtime Ceiling**:
An optional Run Definition duration evaluated against the union of that Run's attributable compute-allocation intervals, including setup and every recovery but excluding queueing and gaps without an allocation. It is a terminal-stop trigger observed by the backend, not a guaranteed maximum duration.
_Avoid_: Execution Attempt timeout, queue deadline, hard runtime cap

**Metered Resource**:
A compute allocation, durable-storage occupancy, data transfer, or dedicated auxiliary resource whose consumption is attributable to one Run. Shared services, account-level discounts and credits, and taxes are not Metered Resources.
_Avoid_: Shared infrastructure, provider invoice line

**Metered Usage**:
The durably observed quantity of a Metered Resource consumed by one Run, expressed in the resource's pricing unit and retaining the observation's provenance. Missing usage remains an explicit gap rather than becoming zero.
_Avoid_: Cost total, estimated usage, billing line

**Price Source**:
The explicitly selected authority for one target and Metered Resource family's rates, carrying its provenance and freshness. It may be a provider surface, SkyPilot's catalog, or an operator-maintained schedule; an operator override is always deliberate rather than an automatic fallback.
_Avoid_: First available price, provider invoice

**Applied Rate**:
The immutable, sourced unit price and billing rules selected for a Metered Usage interval. Later source or operator changes create a new rate for later usage and never revalue historical usage.
_Avoid_: Current price, mutable rate, billed amount

**Reporting Currency**:
The deployment-wide currency in which Run Cost Estimates and their aggregates are presented. An Applied Rate retains its native currency and the frozen, sourced conversion used to express it in the Reporting Currency.
_Avoid_: Provider currency, current exchange rate

**Eligible GPU Offering**:
A configured cloud provider's instance offering whose GPU model, GPU count, and purchase mode satisfy a Run Submission's requested target capabilities. Eligibility does not assert that the provider has immediate live capacity.
_Avoid_: Available instance, selected infrastructure, live capacity

**Target Support Tier**:
The deployment's explicit support promise for one named target and purchase-mode pairing, so one mode may be demoted without changing another. A First-class Target is release-gated by documented credentials and end-to-end evidence; a Compatible Target is a finite, operator-configured allowlist entry with no release guarantee; a Deferred Target is unavailable until promoted.
_Avoid_: Any SkyPilot provider, adapter maturity, presumed compatibility

**Cost Quote**:
The immutable pre-run minimum-to-maximum GPU-compute price across a Run Submission's Eligible GPU Offerings, expressed per hour, 24-hour day, and 168-hour week. It does not forecast storage, transfer, or auxiliary-resource cost, assert live capacity, or estimate the resources ultimately selected for execution.
_Avoid_: Run Cost Estimate, price guarantee, provider bill

**Cost Component**:
The valuation of one Metered Resource's usage within a Run Cost Estimate. It is ongoing while that resource still accrues and completed once its usage closes; whether its usage and pricing are complete is a separate property.
_Avoid_: Run cost total, invoice line

**Run Cost Estimate**:
The non-invoice monetary estimate derived from Metered Usage and Applied Rates for one Run, including every recovery Execution Attempt in that Run. A checkpoint-seeded clone owns a separate estimate, and missing usage or pricing leaves the estimate explicitly incomplete rather than treating the component as free.
_Avoid_: Actual spend, provider bill, GPU cost

**Cost Ceiling**:
An optional Run Definition amount in the Reporting Currency evaluated against the available Run Cost Estimate. It is a best-effort terminal-stop trigger rather than a guaranteed spend maximum; incomplete estimate inputs make it visibly unenforceable without stopping the Run.
_Avoid_: Budget guarantee, provider spending limit, actual-cost cap

**Ceiling Stop Decision**:
The immutable backend decision that one or both of a Run's observed ceiling exposures reached their configured values, carrying the triggering observations and their freshness. It is the sole authority for a ceiling stop and may be projected for delivery without making that projection another evaluator.
_Avoid_: Policy Stop Request, billing alert, stored Run status

**Policy Stop Request**:
The Run Store projection of a Ceiling Stop Decision that asks the Training Process Boundary to stop terminally at its next Safe Point after making that point durable. It authorizes no independent ceiling calculation and never requests recovery.
_Avoid_: Cancellation Request, Interruption Request, Ceiling Stop Decision

**Retained SkyPilot Fact**:
An immutable orchestrator-sourced fact Skywright appends to outlive SkyPilot's retention policy, kept in storage of that provenance alone and joined to a run only by run identity. The source wins while it still answers; retained rows supplement only what it has purged.
_Avoid_: Mirrored state, run status cache, Skywright-originated fact

**Run Log Archive**:
The immutable, SkyPilot-provenance post-hoc log source inside a Run Store, containing separate ordered raw terminal-byte streams for task output and controller output. The task stream is indexed by Setup Log Segment and Execution Attempt without rewriting its chronology; its terminal manifest records each stream as complete or partial. Until that manifest is published, SkyPilot remains authoritative.
_Avoid_: Live log, Retained SkyPilot Fact, Execution Termination Report, Artifact

**Live Log View**:
A non-authoritative, offset-followed rendering of terminal bytes already captured in a staging Run Log Archive while SkyPilot remains authoritative. It continues across preemption and Execution Attempts; source loss leaves captured bytes visible with explicit freshness, while Run Store loss makes the view unavailable. It interprets terminal control sequences rather than inventing log records, timestamps, or line boundaries.
_Avoid_: Live log source, held SkyPilot stream, normalized log feed

**Archive Cursor**:
A stable position between bytes in one Run Log Archive stream, used to page history and resume a Live Log View without depending on storage chunks or the Run Store's current Storage Location.
_Avoid_: SkyPilot log offset, line number, object key

**Setup Log Segment**:
The ordered task-log output produced before an Execution Attempt begins. It may be linked to the following attempt as its preparation, but never belongs to that attempt; a setup failure may leave it unlinked at Run level.
_Avoid_: Execution Attempt log, controller log, setup attempt

**Orchestrator Operation**:
One control action the backend has handed to the orchestrator and is waiting on. It exists only while the process that started it lives: it is never persisted, so losing it loses the view of an action, never the action itself.
_Avoid_: Request id, job handle, pending action, command

**Execution Termination Report**:
The atomic, immutable final record an Execution Attempt writes to its Run Store when it terminates of its own accord, naming the Execution Termination Cause, last committed Step, and latest Durable Safe Point. Its absence is not a diagnosis: it means the attempt did not get to speak.
_Avoid_: Exit code, crash log, preemption signal

**Execution Termination Cause**:
The canonical process-known reason named by an Execution Termination Report: completed, cancelled, interrupted, policy stopped, contract violation, Training Project failure, or Skywright failure. It is not stored on the Run Record, and its absence does not imply a cause.
_Avoid_: Stop reason, Run status, exit code

**Environment Profile**:
The library-owned base image for one accelerator backend, carrying the Skywright library and the accelerator-compatible runtime dependencies a Training Project must not choose between or replace. It is the base a Training Project Image is built on, not something a Run Definition pins.
_Avoid_: Project environment, device configuration, run-time dependency install

**Metric Definition**:
A metric's declared identity and recording semantics: canonical name, numeric kind, controlled unit, Recording Basis, comparison direction, optional bounds, and a Step Reduction when Step-based. A project metric is comparable only within one Training Project identity and when every semantic field of its definitions matches; presentation-only display name and description do not break comparability, and comparison direction never implies a run-level summary.
_Avoid_: Dynamic metric, log field

**Project Metric Contract**:
The canonical, content-addressed, version-bound set of project-owned Metric Definitions published with a Training Project Version after both project CI and the backend validate it against the exact Skywright Metric Schema it names. A Run Submission can neither add to nor alter it.
_Avoid_: Run metrics, dynamic registry

**Skywright Metric Schema**:
The versioned, content-addressed definition format, library-owned Metric Definitions, Metric Unit registry, and naming rules with which a Project Metric Contract composes. Each Project Metric Contract and Run Definition pin its exact identity.
_Avoid_: Project Metric Contract, Metric Catalog, TensorBoard schema

**Metric Catalog**:
The immutable set of Metric Definitions deterministically composed at runtime from a Run Definition's pinned Project Metric Contract and exact Skywright Metric Schema. Names are unique and `skywright/` is reserved for library definitions; the catalog is not persisted separately, and TensorBoard observations do not contain its semantics.
_Avoid_: Project Metric Contract, observed tags, metric index

**Metric Observation**:
A provisional project-reported finite scalar value for a declared Step-based metric, associated by the Run Context with the next Step to commit rather than with a caller-supplied Step number. All observations for that metric are combined by its Step Reduction when the Step commits and discarded if the Step does not commit; a definition permits observations but does not require any, while returning with pending observations is a contract violation.
_Avoid_: Metric point, committed metric

**Metric Unit**:
A stable identifier from Skywright's versioned registry describing a Metric Definition's quantity, including an explicit dimensionless identifier. Free-form display strings are not Metric Units; later registry additions do not change existing definitions.
_Avoid_: Unit label, description

**Recording Basis**:
The declared primary axis on which a metric is recorded: a committed Step or wall-clock time. Training Projects may declare only Step-based metrics; Skywright may define either kind.
_Avoid_: TensorBoard axis, sampling interval

**Step Reduction**:
The declared `mean`, `sum`, `min`, `max`, or `last` rule that combines one or more Metric Observations for a metric within a committed Step into exactly one recorded value. `mean` requires a real-valued metric; optional bounds constrain the reduced value.
_Avoid_: TensorBoard smoothing, cross-run aggregation

**System Metric**:
A library-owned Metric Definition under `skywright/system/` that a Training Project can neither declare nor write. Step-based kinds are derived in the Run Context from reported Steps; wall-time kinds come from a background sampler on a wall-clock cadence.
_Avoid_: Project metric, separate monitoring channel

**Metric Segment**:
An immutable TensorBoard event file holding part of one run's metric history. The open segment is replaced whole on each flush and sealed once it crosses a size or age threshold; every replacement extends its predecessor byte-for-byte, so a reader tracking an offset continues across it.
_Avoid_: Metric row, appendable log, tracker run

**Metric View**:
The ephemeral, stateless TensorBoard instance serving exactly one run's Metric Segments, spun up on access through the backend's proxy and stopped when idle. It holds no data of its own, so stopping it loses nothing and respawning is transparent.
_Avoid_: Metric store, dashboard service, retained instance

**Run Store**:
The mandatory durable home for a run's checkpoints, samples, artifacts, metrics, and retained logs: one Storage Location per run. Its Target Storage may differ between local and external execution and may change once the run is terminal, without changing the Training Project's contract — so readers resolve it through the Run Record rather than the Run Definition. It is the determined source for a completed run's metrics and retained logs, with SkyPilot-sourced logs kept provenance-distinct from process-authored outputs.
_Avoid_: Bucket, output directory

**Repatriation**:
The move of a terminal run's Run Store to the destination Target Storage configured for its Target Class, copying and verifying before anything is deleted. It is a no-op when the run already executed against that destination, and a failure leaves the store where it is rather than degrading the run.
_Avoid_: Backup, archive, sync

**Transfer Worker**:
The role that copies content between two Storage Locations, verifies it, publishes it, and deletes the source only when asked. One protocol serves repatriation, seeding a resumed run, and dataset materialization; it never runs on a training instance.
_Avoid_: Backend job, sync service, upload script

**Progress Record**:
A small Skywright-originated object in the Run Store carrying a run's current step, latest Durable Safe Point, target step, and the time it was written, overwritten on each flush. It is an aged intermediate result serving run-list progress and exposing checkpoint durability lag — not a metric series, and not an index over one.
_Avoid_: Metric index, run status, stored progress

**Checkpoint State**:
The complete set of library-owned and project-specific resumable state established before training begins, including the Dataset Cursor and the fingerprint of its ordering inputs. A checkpoint is a durable snapshot of this declared state.
_Avoid_: State dictionary, model weights

**Step**:
A Training Project's monotonically numbered unit of committed progress and the safe boundary at which the Run Context may flush metrics, checkpoint state, or honor interruption. Step-scoped observations remain provisional until it commits; it may contain any number of batches or Dataset Items but cannot span Dataset epochs, and a Run must commit at least one Step to complete successfully.
_Avoid_: Batch, iteration, epoch

**Safe Point**:
The boundary after a Step commits at which the Run Context may safely snapshot Checkpoint State, flush buffered observations, persist progress, and honor a pending Interruption Request. It commits logical progress but is not durable unless a checkpoint for it becomes a Durable Safe Point; a signal or termination path is not itself a Safe Point.
_Avoid_: Signal handler, process exit, checkpoint cadence

**Durable Safe Point**:
A Safe Point whose checkpoint has been confirmed published in the Run Store, making its committed progress recoverable after loss of the compute instance. Merely capturing a snapshot or starting its upload does not make it durable.
_Avoid_: Safe Point, local snapshot, pending checkpoint

**Interruption Request**:
A request for a running Training Project to stop at its next Safe Point. An unqualified first `SIGINT` or `SIGTERM` creates one unless a Cancellation Request already exists, but the signal never proves preemption; receiving the request does not itself commit progress or make state durable.
_Avoid_: Preemption, Safe Point, immediate termination

**Cancellation Request**:
A user-directed request through Skywright's explicit cancellation surface to end a Run terminally at its next Safe Point without creating a checkpoint for the cancellation. It is never inferred from a process signal, escalates to forced orchestrator cancellation after bounded grace, and retry starts a new Run from an existing Durable Safe Point.
_Avoid_: Interruption Request, aborted, pause

**Sample**:
A typed, inspectable training output in a common media form such as text, image, audio, or video, saved through the Run Store with library-understood metadata.
_Avoid_: Artifact, metric

**Artifact**:
Arbitrary project-owned run output persisted for inspection, debugging, or later reuse but not interpreted by the library.
_Avoid_: Sample, checkpoint, dataset
