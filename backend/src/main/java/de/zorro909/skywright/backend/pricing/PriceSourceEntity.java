package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity(name = "PriceSourceEntity")
@Table(name = "price_source")
class PriceSourceEntity {

	@Id
	UUID id;

	@Column(nullable = false)
	String name;

	@Column(nullable = false)
	String kind;

	@Column(name = "registration_revision", nullable = false)
	long registrationRevision;

	@Column(name = "active_revision")
	Long activeRevision;

	@Column(name = "candidate_revision")
	Long candidateRevision;

	@Column(name = "schedule_revision", nullable = false)
	long scheduleRevision;

	@Column(name = "assessed_schedule_revision")
	Long assessedScheduleRevision;

	@Column(name = "credential_binding_id")
	UUID credentialBindingId;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "price_source_revision", joinColumns = @JoinColumn(name = "price_source_id"))
	@OrderColumn(name = "revision_position")
	List<PriceSourceRevisionValue> revisions = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "price_source_assessment", joinColumns = @JoinColumn(name = "price_source_id"))
	@OrderColumn(name = "assessment_position")
	List<PriceSourceAssessmentValue> assessments = new ArrayList<>();

	protected PriceSourceEntity() {
	}

	static PriceSourceEntity create(UUID id, String name, String kind, UUID credentialBindingId,
			String configurationJson) {
		PriceSourceEntity source = new PriceSourceEntity();
		source.id = id;
		source.name = name;
		source.kind = kind;
		source.registrationRevision = 1;
		source.candidateRevision = 1L;
		source.credentialBindingId = credentialBindingId;
		source.revisions.add(new PriceSourceRevisionValue(1, configurationJson));
		return source;
	}

}

@Embeddable
class PriceSourceRevisionValue {

	@Column(name = "configuration_revision", nullable = false)
	long revision;

	@Column(name = "configuration_json", nullable = false, length = 100000)
	String configurationJson;

	protected PriceSourceRevisionValue() {
	}

	PriceSourceRevisionValue(long revision, String configurationJson) {
		this.revision = revision;
		this.configurationJson = configurationJson;
	}

}

@Embeddable
class PriceSourceAssessmentValue {

	@Column(name = "assessment_id", nullable = false)
	UUID id;

	@Column(name = "configuration_revision", nullable = false)
	long revision;

	@Column(nullable = false)
	boolean successful;

	@Column(name = "capability_results", nullable = false, length = 4096)
	String capabilityResults;

	@Column(name = "observed_from", nullable = false)
	Instant observedFrom;

	@Column(name = "observed_until", nullable = false)
	Instant observedUntil;

	protected PriceSourceAssessmentValue() {
	}

	PriceSourceAssessmentValue(UUID id, long revision, boolean successful, String capabilityResults,
			Instant observedFrom, Instant observedUntil) {
		this.id = id;
		this.revision = revision;
		this.successful = successful;
		this.capabilityResults = capabilityResults;
		this.observedFrom = observedFrom;
		this.observedUntil = observedUntil;
	}

}

record PriceSourceView(UUID id, String name, String kind, long registrationRevision, Long activeRevision,
		Long candidateRevision, UUID credentialBindingId, List<PriceSourceRevisionView> revisions,
		List<PriceSourceAssessmentView> assessments) {
}

record PriceSourceRevisionView(long revision, Map<String, Object> configuration) {
}

record PriceSourceAssessmentView(UUID id, long revision, boolean successful, List<String> capabilityResults,
		Instant observedFrom, Instant observedUntil) {
}
