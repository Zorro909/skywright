package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import java.util.UUID;

@Entity(name = "TargetStorageResourceEntity")
@Table(name = "target_storage_resource")
class TargetStorageResourceEntity {

	@Id
	@Column(name = "resource_key", nullable = false, length = 64)
	private String resourceKey;

	@Column(name = "endpoint", nullable = false, length = 2048)
	private String endpoint;

	@Column(name = "bucket", nullable = false, length = 255)
	private String bucket;

	@Column(name = "target_storage_id", nullable = false)
	private UUID targetStorageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, length = 32)
	private TargetStoragePurpose purpose;

	protected TargetStorageResourceEntity() {
	}

	static TargetStorageResourceEntity create(UUID storageId, TargetStoragePurpose purpose, URI endpoint,
			String bucket) {
		TargetStorageResourceEntity result = new TargetStorageResourceEntity();
		result.resourceKey = TargetStorageResourceClaim.resourceKey(endpoint, bucket);
		result.endpoint = endpoint.toString();
		result.bucket = bucket;
		result.targetStorageId = storageId;
		result.purpose = purpose;
		return result;
	}

	TargetStorageResourceClaim domain() {
		return new TargetStorageResourceClaim(this.targetStorageId, this.purpose);
	}

}
