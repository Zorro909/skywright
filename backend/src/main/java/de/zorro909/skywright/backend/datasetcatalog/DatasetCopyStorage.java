package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;
import java.util.UUID;

interface DatasetCopyStorage extends DatasetCopyVerifier {

	VerifiedDatasetReplacement stageReplacement(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
			DatasetCopyView copy, UUID operationId);

	VerifiedDatasetReplacement verifyReplacement(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
			DatasetCopyView copy, UUID operationId);

	void deleteAndVerify(List<DatasetManifestEntry> manifest, DatasetCopyView copy, long generation);

}
