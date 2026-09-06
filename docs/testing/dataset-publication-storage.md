# Dataset Publication temporary-storage budget

The verifier hashes S3 object responses with a 1 MiB buffer per active object. It
checks the advertised length, consumed length and SHA-256 before returning any
verified manifest. It closes each response on success or failure. Shard payloads
never enter `/tmp`.

`skywright.dataset-publication.verification-concurrency` defaults to four and
accepts 1 through 16. These values bound simultaneous reads and hashing buffers,
not the complete JVM heap. The S3 transport and manifest metadata also use memory.
Manifests are limited to 16 MiB and 100,000 objects. Object lengths and the checked
total must fit a nonnegative signed 64-bit integer. Object size does not determine
temporary-storage use. The qualified shard size is 256 MiB, with four concurrent
objects totaling 1 GiB. Larger objects use the same streaming buffer, but have not
been qualified by this scenario.

The production overlay retains the backend's 64 MiB memory-backed `/tmp`, read-only
root filesystem and UID 10001. Job and result JSON files use that shared temporary
volume. The worker writes a pending result and atomically renames it only after
serialization completes. Failure to create control files or commit the result
reports retryable `DATASET_WORKER_TEMPORARY_STORAGE_UNAVAILABLE`. The worker exits
74 when it cannot write its result. The launcher and restart recovery remove both
committed and pending result files and release the credential projection after the
worker exits. No partial result makes a Dataset Publication available.

## Reproduction and regression checks

The original worker at `e96ac58d761a022ddc300a4c4a6b5ed235466dc5` failed to verify
one 256 MiB shard under the packaged 64 MiB temporary limit. Increasing only that
limit to 512 MiB made the same upload and manifest pass. Streaming made the
original 64 MiB scenario pass without changing the deployment budget.

`DatasetPublicationImageIT` runs the packaged application worker against real
SeaweedFS. It derives the tmpfs size from `kubectl kustomize deployment/overlays/production` and covers four concurrent 256 MiB objects,
same-size checksum corruption, exhausted control storage followed by retry, and
process cancellation during a held shard response followed by restart. The held
response uses a controlled HTTP server. `DatasetPublicationApiIT` checks the
application's publication, cancellation and failure behavior against PostgreSQL
and S3. `DatasetPublicationWorkerRecoveryTest` checks projection and partial-file
cleanup after restart.

Run the image and application checks from the repository root:

```sh
mvn -pl backend-deployment -am -DskipFrontendInstall=true -DskipFrontendTests=true \
  -Dit.test=DatasetPublicationImageIT,DatasetPublicationApiIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

The additional Kubernetes scenario copies the production pod specification into
an isolated namespace, then runs the actual worker against an S3 pod. It samples
`/tmp`, fills the volume, interrupts a blocked worker by deleting its pod and
verifies again in a replacement pod. It removes only its owned namespace. Use a
disposable cluster and load the backend and SeaweedFS images separately:

```sh
kind create cluster --name publication-budget --kubeconfig /tmp/publication-kubeconfig
kind load docker-image --name publication-budget skywright-backend:qualification
kind load docker-image --name publication-budget docker.io/chrislusf/seaweedfs:4.42
uv run --project sdk --locked --group ml-test python \
  tests/deployment/support/publication_pod_scenario.py \
  --kubeconfig /tmp/publication-kubeconfig --image skywright-backend:qualification \
  --output /tmp/publication-storage.json
kind delete cluster --name publication-budget
```

Use the tag produced by the local image build in place of the example backend tag.
The fixture credentials are public test credentials and enter the worker through
stdin. The test does not use the operator's default Kubernetes context.

Changes to deployment configuration and these image qualification classes select
both the image and real-service integration quality lanes. Selector regression
coverage lives in `tests/quality/test_quality_cli.py`.

## Recorded Kubernetes result

The 2026-09-06 run on Kubernetes v1.36.1 verified all 1 GiB in 4.02 seconds.
Peak sampled `/tmp` use was 40,960 bytes within the 67,108,864-byte volume.
Exhaustion returned exit 74 with no result. The interrupted worker stopped and
the replacement pod verified the same objects. Raw evidence, baseline outcomes
and the tested image ID are in `dataset-publication-storage.json`. This measures
temporary storage, not total pod memory. The test cluster was removed afterward.
