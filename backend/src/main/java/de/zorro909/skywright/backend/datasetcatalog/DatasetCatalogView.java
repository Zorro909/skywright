package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;

public record DatasetCatalogView(long revision, DatasetDefinitionView definition, List<DatasetCopyView> copies,
		List<DatasetLeaseView> leases, List<DatasetCacheView> caches, List<DatasetCopyOperationView> operations) {
}
