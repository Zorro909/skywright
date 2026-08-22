package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;

record DatasetCatalogSnapshot(long revision, DatasetDefinitionView definition, List<DatasetCopyView> copies,
		List<DatasetManifestEntry> manifest, List<DatasetLeaseView> leases, List<DatasetCacheView> caches,
		List<DatasetCopyOperationView> operations) {
}
