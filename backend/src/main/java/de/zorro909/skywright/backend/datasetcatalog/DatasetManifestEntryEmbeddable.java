package de.zorro909.skywright.backend.datasetcatalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class DatasetManifestEntryEmbeddable {

	@Column(name = "object_key", nullable = false, length = 2048)
	String objectKey;

	@Column(name = "byte_count", nullable = false)
	long byteCount;

	@Column(name = "checksum_sha256", nullable = false)
	String checksumSha256;

	protected DatasetManifestEntryEmbeddable() {
	}

	static DatasetManifestEntryEmbeddable from(DatasetManifestEntry value) {
		var result = new DatasetManifestEntryEmbeddable();
		result.objectKey = value.objectKey();
		result.byteCount = value.byteCount();
		result.checksumSha256 = value.checksumSha256();
		return result;
	}

	DatasetManifestEntry domain() {
		return new DatasetManifestEntry(this.objectKey, this.byteCount, this.checksumSha256);
	}

}
