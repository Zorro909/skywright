package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.net.URI;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
class TargetStorageConfigurationEmbeddable {

	@Column(name = "configuration_revision", nullable = false)
	long revision;

	@Column(nullable = false, length = 2048)
	String endpoint;

	@Column(nullable = false)
	String region;

	@Column(name = "path_style_access", nullable = false)
	boolean pathStyleAccess;

	@Column(name = "compatibility_options", nullable = false, columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	Map<String, String> compatibilityOptions;

	protected TargetStorageConfigurationEmbeddable() {
	}

	static TargetStorageConfigurationEmbeddable from(long revision, TargetStorageConfiguration configuration) {
		var result = new TargetStorageConfigurationEmbeddable();
		result.revision = revision;
		result.endpoint = configuration.endpoint().toString();
		result.region = configuration.region();
		result.pathStyleAccess = configuration.pathStyleAccess();
		result.compatibilityOptions = configuration.compatibilityOptions();
		return result;
	}

	TargetStorageEncoding.DecodedConfiguration domain() {
		return new TargetStorageEncoding.DecodedConfiguration(this.revision, new TargetStorageConfiguration(
				URI.create(this.endpoint), this.region, this.pathStyleAccess, this.compatibilityOptions));
	}

}
