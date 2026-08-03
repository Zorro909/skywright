# Skywright

Skywright provides a portable contract for defining and running machine-learning training work across local and cloud targets without taking ownership of project-specific training control flow.

## Language

**Training Project**:
A consumer of Skywright that owns model and data semantics, training control flow, and project-specific configuration.
_Avoid_: Plugin, managed trainer

**Training Contract**:
The library-owned, validated standards a Training Project must follow for configuration, datasets, checkpointing, metrics, and resume behavior. It does not prescribe the project's training loop.
_Avoid_: Training framework, callback framework

**Run Context**:
The explicit library-provided runtime boundary through which a Training Project accesses resolved datasets, metrics, persistence, and resume state while retaining ownership of its training loop.
_Avoid_: Trainer, callback host, global context

**Dataset Definition**:
A validated description of a durable input corpus's stable identity, version, locations, access requirements, and shared loading configuration; payload semantics such as image, text, or video remain with the Training Project.
_Avoid_: Dataset object, data path, replay buffer

**Generated Experience**:
Training data produced during a run, such as RL rollouts or replay-buffer entries. It is project-owned run state rather than a Dataset, though it may be persisted for inspection or later promoted into one.
_Avoid_: Dataset

**Run Configuration**:
The complete typed configuration for a run, composed from library-defined common options and project-defined options.
_Avoid_: Config file, parameters

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
