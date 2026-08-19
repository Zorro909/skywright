package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.EnumSet;
import java.util.UUID;
import java.util.stream.Collectors;

final class TargetStorageAggregate {

	private final UUID id;

	private String name;

	private final TargetStoragePurpose purpose;

	private final String bucket;

	private long registrationRevision;

	private boolean activated;

	private Long activeRevision;

	private Long candidateRevision;

	private final Map<Long, TargetStorageConfiguration> configurations;

	private List<TargetStorageBinding> bindings;

	private final List<TargetStorageAssessment> assessments;

	private CapabilityAvailability availability;

	private TargetStorageAggregate(UUID id, String name, TargetStoragePurpose purpose, String bucket,
			long registrationRevision, boolean activated, Long activeRevision, Long candidateRevision,
			Map<Long, TargetStorageConfiguration> configurations, List<TargetStorageBinding> bindings,
			List<TargetStorageAssessment> assessments, CapabilityAvailability availability) {
		this.id = id;
		this.name = name;
		this.purpose = purpose;
		this.bucket = bucket;
		this.registrationRevision = registrationRevision;
		this.activated = activated;
		this.activeRevision = activeRevision;
		this.candidateRevision = candidateRevision;
		this.configurations = new LinkedHashMap<>(configurations);
		this.bindings = TargetStorageAggregate.validatedBindings(bindings);
		this.assessments = new ArrayList<>(assessments);
		this.availability = availability;
	}

	static TargetStorageAggregate create(UUID id, String name, TargetStoragePurpose purpose, String bucket,
			TargetStorageConfiguration configuration, List<TargetStorageBinding> bindings) {
		return new TargetStorageAggregate(id, name, purpose, bucket, 1L, false, null, 1L, Map.of(1L, configuration),
				bindings, List.of(), CapabilityAvailability.TRANSIENTLY_UNAVAILABLE);
	}

	static TargetStorageAggregate restore(UUID id, String name, TargetStoragePurpose purpose, String bucket,
			long registrationRevision, boolean activated, Long activeRevision, Long candidateRevision,
			Map<Long, TargetStorageConfiguration> configurations, List<TargetStorageBinding> bindings,
			List<TargetStorageAssessment> assessments, CapabilityAvailability availability) {
		return new TargetStorageAggregate(id, name, purpose, bucket, registrationRevision, activated, activeRevision,
				candidateRevision, configurations, bindings, assessments, availability);
	}

	UUID id() {
		return this.id;
	}

	TargetStoragePurpose purpose() {
		return this.purpose;
	}

	String bucket() {
		return this.bucket;
	}

	Long activeRevision() {
		return this.activeRevision;
	}

	boolean hasResource(URI endpoint, String resourceBucket) {
		return this.bucket.equals(resourceBucket)
				&& this.configurations.values().stream().anyMatch(value -> value.endpoint().equals(endpoint));
	}

	void requireRevision(long expected) {
		if (expected != this.registrationRevision) {
			throw new TargetStorageConflictException("TARGET_STORAGE_REVISION_CONFLICT",
					"Expected registration revision " + expected + " but current revision is "
							+ this.registrationRevision);
		}
	}

	void rename(String value) {
		this.name = value;
		++this.registrationRevision;
	}

	long stage(TargetStorageConfiguration configuration) {
		long revision = this.configurations.keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
		this.configurations.put(revision, configuration);
		this.candidateRevision = revision;
		++this.registrationRevision;
		return revision;
	}

	void replaceBindings(List<TargetStorageBinding> value) {
		this.bindings = TargetStorageAggregate.validatedBindings(value);
		++this.registrationRevision;
	}

	void record(TargetStorageAssessment assessment) {
		if (!this.configurations.containsKey(assessment.configurationRevision())) {
			throw new IllegalArgumentException("assessment names an unknown configuration revision");
		}
		this.assessments.add(assessment);
		boolean currentBindings = this.sameBindingRevisions(assessment.bindingRevisions());
		if (currentBindings && assessment.availability() == CapabilityAvailability.AVAILABLE
				&& Objects.equals(this.candidateRevision, assessment.configurationRevision())) {
			this.activeRevision = assessment.configurationRevision();
			this.candidateRevision = null;
			this.availability = CapabilityAvailability.AVAILABLE;
		}
		else if (currentBindings && Objects.equals(this.activeRevision, assessment.configurationRevision())) {
			this.availability = assessment.availability();
		}
		++this.registrationRevision;
	}

	void activate() {
		if (this.activeRevision == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_NOT_QUALIFIED",
					"Target Storage cannot activate without a successful qualification");
		}
		this.activated = true;
		++this.registrationRevision;
	}

	void deactivate() {
		this.activated = false;
		++this.registrationRevision;
	}

	boolean eligible() {
		return this.activated && this.activeRevision != null && this.availability == CapabilityAvailability.AVAILABLE
				&& this.bindingsReady();
	}

	TargetStorageDescriptor descriptor() {
		TargetStorageConfiguration configuration = this.configurations.get(this.activeRevision);
		return new TargetStorageDescriptor(this.id, configuration.endpoint(), this.bucket, configuration.region(),
				configuration.pathStyleAccess(), configuration.compatibilityOptions());
	}

	TargetStorageQualificationRequest qualificationRequest() {
		Long revision = this.candidateRevision == null ? this.activeRevision : this.candidateRevision;
		if (revision == null) {
			throw new TargetStorageIneligibleException("TARGET_STORAGE_CANDIDATE_MISSING",
					"Target Storage has no configuration revision to qualify");
		}
		return new TargetStorageQualificationRequest(this.id, this.purpose, this.bucket, revision,
				this.configurations.get(revision), this.bindings);
	}

	TargetStorageView view() {
		Long visibleRevision = this.activeRevision == null ? this.candidateRevision : this.activeRevision;
		return new TargetStorageView(this.id, this.name, this.purpose, this.bucket, this.registrationRevision,
				this.activated, this.eligible(), this.activeRevision, this.candidateRevision,
				visibleRevision == null ? null : this.configurations.get(visibleRevision), this.revisions(),
				this.bindings, List.copyOf(this.assessments));
	}

	private List<TargetStorageRevisionView> revisions() {
		return this.configurations.entrySet()
			.stream()
			.map(entry -> new TargetStorageRevisionView(entry.getKey(), this.revisionState(entry.getKey()),
					entry.getValue()))
			.toList();
	}

	private String revisionState(long revision) {
		if (Objects.equals(this.activeRevision, revision)) {
			return "active";
		}
		if (Objects.equals(this.candidateRevision, revision)) {
			return "candidate";
		}
		return "historical";
	}

	Map<Long, TargetStorageConfiguration> configurations() {
		return Map.copyOf(this.configurations);
	}

	TargetStorageSnapshot snapshot() {
		return new TargetStorageSnapshot(this.id, this.name, this.purpose, this.bucket, this.registrationRevision,
				this.activated, this.activeRevision, this.candidateRevision, this.configurations, this.bindings,
				this.assessments, this.availability);
	}

	private boolean bindingsReady() {
		var roles = this.bindings.stream()
			.filter(binding -> binding.readiness() == BindingReadiness.READY)
			.map(TargetStorageBinding::role)
			.collect(Collectors.toSet());
		return roles.containsAll(List.of(TargetStorageRole.TRAINING_PROCESS, TargetStorageRole.BACKEND,
				TargetStorageRole.TRANSFER_WORKER, TargetStorageRole.METRIC_VIEW));
	}

	private boolean sameBindingRevisions(List<TargetStorageBinding> assessedBindings) {
		return this.bindings.stream()
			.allMatch(binding -> assessedBindings.stream()
				.anyMatch(assessed -> assessed.role() == binding.role()
						&& assessed.bindingId().equals(binding.bindingId())
						&& assessed.bindingRevision() == binding.bindingRevision()));
	}

	private static List<TargetStorageBinding> validatedBindings(List<TargetStorageBinding> bindings) {
		List<TargetStorageBinding> copy = List.copyOf(bindings);
		var roles = copy.stream()
			.map(TargetStorageBinding::role)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(TargetStorageRole.class)));
		if (roles.size() != copy.size()) {
			throw new IllegalArgumentException("Credential Binding roles must be unique");
		}
		if (!roles.equals(EnumSet.allOf(TargetStorageRole.class))) {
			throw new IllegalArgumentException("All four Credential Binding roles are required");
		}
		return copy;
	}

}
