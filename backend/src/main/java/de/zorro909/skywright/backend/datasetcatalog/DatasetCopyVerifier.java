package de.zorro909.skywright.backend.datasetcatalog;

import java.util.List;

@FunctionalInterface
public interface DatasetCopyVerifier {

	void verify(DatasetDefinitionView definition, List<DatasetManifestEntry> manifest, DatasetCopyView copy);

}
