package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;

record DatasetCopyWorkItem(long catalogRevision, DatasetDefinitionView definition, List<DatasetManifestEntry> manifest,
		DatasetCopyView copy, DatasetCopyOperationView operation) {
}
