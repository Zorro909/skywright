package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity(name = "TargetStorageDefaultsEntity")
@Table(name = "target_storage_defaults")
class TargetStorageDefaultsEntity {

	@Id
	@Enumerated(value = EnumType.STRING)
	@Column(name = "target_class")
	TargetClass targetClass;

	@Column(name = "execution_storage_id", nullable = false)
	UUID executionStorageId;

	@Column(name = "repatriation_enabled", nullable = false)
	boolean repatriationEnabled;

	@Column(name = "repatriation_storage_id", nullable = false)
	UUID repatriationStorageId;

	protected TargetStorageDefaultsEntity() {
	}

	static TargetStorageDefaultsEntity from(TargetStorageDefaults defaults) {
		TargetStorageDefaultsEntity result = new TargetStorageDefaultsEntity();
		result.targetClass = defaults.targetClass();
		result.executionStorageId = defaults.executionStorageId();
		result.repatriationEnabled = defaults.repatriationEnabled();
		result.repatriationStorageId = defaults.repatriationStorageId();
		return result;
	}

	TargetStorageDefaults domain() {
		return new TargetStorageDefaults(this.targetClass, this.executionStorageId, this.repatriationEnabled,
				this.repatriationStorageId);
	}

}
