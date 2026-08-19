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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
	@OrderColumn(name = "configuration_order")
	@Column(name = "encoded_configuration", nullable = false, length = 8192)
	List<String> configurations = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_binding", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@OrderColumn(name = "binding_order")
	@Column(name = "encoded_binding", nullable = false, length = 4096)
	List<String> bindings = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_assessment", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@OrderColumn(name = "assessment_order")
	@Column(name = "encoded_assessment", nullable = false, length = 65535)
	List<String> assessments = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "target_storage_resource", joinColumns = { @JoinColumn(name = "target_storage_id") })
	@Column(name = "resource_key", nullable = false, length = 4096)
	Set<String> resources = new LinkedHashSet<>();

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
			.map(entry -> TargetStorageEncoding.configuration(entry.getKey(), entry.getValue()))
			.toList();
		this.bindings = snapshot.bindings().stream().map(TargetStorageEncoding::binding).toList();
		this.assessments = snapshot.assessments().stream().map(TargetStorageEncoding::assessment).toList();
		this.resources = snapshot.configurations()
			.values()
			.stream()
			.map(configuration -> TargetStorageEntity.resourceKey(configuration.endpoint(), snapshot.bucket()))
			.distinct()
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	static String resourceKey(java.net.URI endpoint, String bucket) {
		return TargetStorageConfiguration.resourceKey(endpoint, bucket);
	}

	TargetStorageAggregate domain() {
		LinkedHashMap<Long, TargetStorageConfiguration> decodedConfigurations = new LinkedHashMap<>();
		this.configurations.stream()
			.map(TargetStorageEncoding::configuration)
			.forEach(value -> decodedConfigurations.put(value.revision(), value.configuration()));
		return TargetStorageAggregate.restore(this.id, this.name, this.purpose, this.bucket, this.registrationRevision,
				this.activated, this.activeRevision, this.candidateRevision, decodedConfigurations,
				this.bindings.stream().map(TargetStorageEncoding::binding).toList(),
				this.assessments.stream().map(TargetStorageEncoding::assessment).toList(), this.availability);
	}

}
