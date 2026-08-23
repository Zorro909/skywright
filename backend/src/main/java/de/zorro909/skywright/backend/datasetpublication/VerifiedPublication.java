package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetManifestEntry;
import java.time.Instant;
import java.util.List;

record VerifiedPublication(List<DatasetManifestEntry> manifest, long objectCount, long byteCount, Instant verifiedAt) {

	VerifiedPublication {
		manifest = List.copyOf(manifest);
	}

}
