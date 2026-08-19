package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
class TargetStorageAssessmentEmbeddable {

	@Column(name = "assessment_id", nullable = false)
	UUID id;

	@Column(name = "configuration_revision", nullable = false)
	long configurationRevision;

	@Column(name = "observed_from", nullable = false)
	Instant observedFrom;

	@Column(name = "observed_until", nullable = false)
	Instant observedUntil;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	CapabilityAvailability availability;

	@Column(name = "binding_revisions", nullable = false, columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	List<TargetStorageBindingRevision> bindingRevisions;

	@Column(nullable = false, columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	List<TargetStorageCapabilityResult> capabilities;

	protected TargetStorageAssessmentEmbeddable() {
	}

	static TargetStorageAssessmentEmbeddable from(TargetStorageAssessment assessment) {
		var result = new TargetStorageAssessmentEmbeddable();
		result.id = assessment.id();
		result.configurationRevision = assessment.configurationRevision();
		result.observedFrom = assessment.observedFrom();
		result.observedUntil = assessment.observedUntil();
		result.availability = assessment.availability();
		result.bindingRevisions = assessment.bindingRevisions();
		result.capabilities = assessment.capabilities();
		return result;
	}

	TargetStorageAssessment domain() {
		return new TargetStorageAssessment(this.id, this.configurationRevision, this.observedFrom, this.observedUntil,
				this.availability, this.bindingRevisions, this.capabilities);
	}

}
