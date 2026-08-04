# Skywright

Skywright provides a portable contract for defining and running machine-learning training work across local and cloud targets without taking ownership of project-specific training control flow.

## Language

**Training Project**:
A consumer of Skywright that owns model and data semantics, training control flow, and project-specific configuration.
_Avoid_: Plugin, managed trainer

**Training Project Version**:
An immutable, identifiable version of a Training Project together with the Project Configuration Contract that its code expects.
_Avoid_: Latest project, floating project version

**Project Configuration Contract**:
The version-bound definition of a Training Project's configuration shape and defaults. It belongs to exactly one Training Project Version and cannot be mixed with another version's code.
_Avoid_: Project config template, arbitrary parameters

**Training Contract**:
The library-owned, validated standards a Training Project must follow for configuration, datasets, checkpointing, metrics, and resume behavior. It does not prescribe the project's training loop.
_Avoid_: Training framework, callback framework

**Run Context**:
The explicit library-provided runtime boundary through which a Training Project accesses resolved datasets, metrics, persistence, and resume state while retaining ownership of its training loop.
_Avoid_: Trainer, callback host, global context

**Dataset Definition**:
A validated description of a durable input corpus's stable identity, version, locations, access requirements, and shared loading configuration; payload semantics such as image, text, or video remain with the Training Project.
_Avoid_: Dataset object, data path, replay buffer

**Dataset Location**:
A concrete, content-equivalent storage location for one Dataset Definition version. Skywright selects a location for an execution without exposing its storage protocol to Training Project code.
_Avoid_: Data path, project-selected location

**Dataset Cache**:
A bounded, non-authoritative local copy of Dataset content used only to accelerate access. It may survive a same-host restart but never replaces a durable Dataset Location.
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
The immutable, fully resolved description of what should run, including its Training Project Version, Run Configuration, and requested target capabilities. Changing any of those creates a new Run Definition.
_Avoid_: Run Submission, Run Record, mutable job configuration

**Run Record**:
The execution-specific history and lifecycle state associated with one Run Definition, including the infrastructure selected to execute it. A clone receives a new Run Record.
_Avoid_: Run Definition, Run Store

**Environment Profile**:
The library-owned selection of accelerator-compatible runtime dependencies inferred from a run's target capabilities. A Training Project does not choose between CUDA and ROCm dependencies itself.
_Avoid_: Project environment, device configuration

**Metric Definition**:
A metric's declared identity and comparison semantics. Every recorded metric must have a definition established before the run begins.
_Avoid_: Dynamic metric, log field

**Run Store**:
The mandatory durable home for a run's checkpoints, samples, and artifacts. Its storage target may differ between local and external execution without changing the Training Project's contract.
_Avoid_: Bucket, output directory

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
