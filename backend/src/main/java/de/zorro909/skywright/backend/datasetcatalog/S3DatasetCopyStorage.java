package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;
import java.util.UUID;

/** Control adapter: only metadata and a bounded receipt cross the process boundary. */
final class S3DatasetCopyStorage implements DatasetCopyStorage {

	private final DatasetCopyWorkerLauncher workers;

	S3DatasetCopyStorage(DatasetCopyWorkerLauncher workers) {
		this.workers = workers;
	}

	@Override
	public boolean ready() {
		return this.workers.ready();
	}

	@Override
	public void verify(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest, DatasetCopyView copy) {
		this.workers.execute("verify", definition, manifest, copy, null, 0);
	}

	@Override
	public VerifiedDatasetReplacement stageReplacement(DatasetDefinitionView definition,
			List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
		return this.workers.execute("stage", definition, manifest, copy, operationId, 0);
	}

	@Override
	public VerifiedDatasetReplacement verifyReplacement(DatasetDefinitionView definition,
			List<DatasetManifestEntry> manifest, DatasetCopyView copy, UUID operationId) {
		return this.workers.execute("replacement", definition, manifest, copy, operationId, 0);
	}

	@Override
	public void deleteAndVerify(List<DatasetManifestEntry> manifest, DatasetCopyView copy, long generation) {
		this.workers.execute("delete", null, manifest, copy, null, generation);
	}

}
