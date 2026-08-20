package de.zorro909.skywright.backend.targetstorage;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Entity(name = "TargetStorageEntity")
@Table(name = "target_storage")
class TargetStorageEntity {

	@Id
	UUID id;

	@Column(nullable = false)
	String name;

	@Enumerated(value = EnumType.STRING)
	@Column(nullable = false)
	TargetStoragePurpose purpose;

	@Column(nullable = false)
	String bucket;

	@Column(name = "registration_revision", nullable = false)
	long registrationRevision;

	@Column(nullable = false)
	boolean activated;

	@Column(name = "active_revision")
	Long activeRevision;

	@Column(name = "candidate_revision")
	Long candidateRevision;

	@Enumerated(value = EnumType.STRING)
	@Column(nullable = false)
	CapabilityAvailability availability;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_configuration", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@OrderColumn(name = "configuration_position")
	List<TargetStorageConfigurationEmbeddable> configurations = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_binding", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@OrderColumn(name = "binding_position")
	List<TargetStorageBindingEmbeddable> bindings = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_assessment", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@OrderColumn(name = "assessment_position")
	List<TargetStorageAssessmentEmbeddable> assessments = new ArrayList<>();

	protected TargetStorageEntity() {
	}

	static TargetStorageEntity from(TargetStorageSnapshot snapshot) {
		TargetStorageEntity result = new TargetStorageEntity();
		result.apply(snapshot);
		return result;
	}

	void apply(TargetStorageSnapshot snapshot) {
		this.id = snapshot.id();
		this.name = snapshot.name();
		this.purpose = snapshot.purpose();
		this.bucket = snapshot.bucket();
		this.registrationRevision = snapshot.registrationRevision();
		this.activated = snapshot.activated();
		this.activeRevision = snapshot.activeRevision();
		this.candidateRevision = snapshot.candidateRevision();
		this.availability = snapshot.availability();
		this.configurations = snapshot.configurations()
			.entrySet()
			.stream()
			.map(entry -> TargetStorageConfigurationEmbeddable.from(entry.getKey(), entry.getValue()))
			.toList();
		this.bindings = snapshot.bindings().stream().map(TargetStorageBindingEmbeddable::from).toList();
		this.assessments = snapshot.assessments().stream().map(TargetStorageAssessmentEmbeddable::from).toList();
	}

	TargetStorageAggregate domain() {
		LinkedHashMap<Long, TargetStorageConfiguration> decodedConfigurations = new LinkedHashMap<>();
		this.configurations.stream()
			.map(TargetStorageConfigurationEmbeddable::domain)
			.forEach(value -> decodedConfigurations.put(value.revision(), value.configuration()));
		return TargetStorageAggregate.restore(this.id, this.name, this.purpose, this.bucket, this.registrationRevision,
				this.activated, this.activeRevision, this.candidateRevision, decodedConfigurations,
				this.bindings.stream().map(TargetStorageBindingEmbeddable::domain).toList(),
				this.assessments.stream().map(TargetStorageAssessmentEmbeddable::domain).toList(), this.availability);
	}

}
