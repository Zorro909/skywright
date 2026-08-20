package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.zorro909.skywright.backend.runstore.RunStoreS3CapabilityFloor;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;

final class TargetStorageRegistryTest {

	private final InMemoryTargetStorageRepository repository = new InMemoryTargetStorageRepository();

	private final TargetStorageRegistry registry = new TargetStorageRegistry(this.repository);

	@Test
	void successfulQualificationPromotesCandidateAndDerivesEligibility() {
		List<TargetStorageBinding> bindings = readyBindings();
		UUID id = this.registry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				configuration("http://storage.example", "eu-central-1"), bindings);
		TargetStorageAssessment assessment = successfulAssessment(1, bindings);
		this.registry.recordQualification(id, assessment);
		this.registry.activate(id, this.registry.get(id).registrationRevision());

		TargetStorageView storage = this.registry.get(id);
		assertThat(storage.activeRevision()).isEqualTo(1);
		assertThat(storage.candidateRevision()).isNull();
		assertThat(storage.eligible()).isTrue();
		assertThat(storage.assessments()).containsExactly(assessment);
	}

	@Test
	void failedCandidateNeverDisplacesTheActiveRevision() {
		UUID id = eligibleRunOutput();
		this.registry.stageRevision(id, this.registry.get(id).registrationRevision(),
				configuration("http://replacement.example", "us-east-1"));
		this.registry.recordQualification(id,
				failedAssessment(2, "conditional-create-not-enforced", this.registry.get(id).bindings()));

		TargetStorageView storage = this.registry.get(id);
		assertThat(storage.activeRevision()).isEqualTo(1);
		assertThat(storage.candidateRevision()).isEqualTo(2);
		assertThat(storage.configuration().endpoint()).isEqualTo(URI.create("http://storage.example"));
		assertThat(storage.eligible()).isTrue();
		assertThat(storage.revisions()).extracting(TargetStorageRevisionView::state)
			.containsExactly("active", "candidate");
	}

	@Test
	void failedInitialCandidateExposesItsCurrentCapabilityAvailability() {
		UUID id = this.registry.register("Draft", TargetStoragePurpose.RUN_OUTPUT, "draft",
				configuration("http://storage.example", "eu-central-1"), List.of());

		this.registry.recordQualification(id, failedAssessment(1, "conditional-create-not-enforced", List.of()));

		assertThat(this.registry.get(id).availability()).isEqualTo(CapabilityAvailability.INCOMPATIBLE);
		assertThat(this.registry.get(id).candidateRevision()).isEqualTo(1);
	}

	@Test
	void staleRevisionCannotOverwriteAConcurrentEdit() {
		UUID id = eligibleRunOutput();
		long staleRevision = this.registry.get(id).registrationRevision();
		this.registry.replaceBindings(id, staleRevision, this.registry.get(id).bindings());

		assertThatThrownBy(() -> this.registry.stageRevision(id, staleRevision,
				configuration("http://stale.example", "us-east-1")))
			.isInstanceOf(TargetStorageConflictException.class)
			.hasMessageContaining("TARGET_STORAGE_REVISION_CONFLICT");
	}

	@Test
	void qualificationThatFinishesAfterANewerCandidateCannotPromoteTheStaleCandidate() {
		UUID id = eligibleRunOutput();
		this.registry.stageRevision(id, this.registry.get(id).registrationRevision(),
				configuration("http://candidate-two.example", "us-east-1"));
		this.registry.stageRevision(id, this.registry.get(id).registrationRevision(),
				configuration("http://candidate-three.example", "us-east-1"));

		this.registry.recordQualification(id, successfulAssessment(2, this.registry.get(id).bindings()));

		assertThat(this.registry.get(id).activeRevision()).isEqualTo(1);
		assertThat(this.registry.get(id).candidateRevision()).isEqualTo(3);
	}

	@Test
	void activeRevisionCanBeRequalifiedToExposeCurrentUnavailability() {
		UUID id = eligibleRunOutput();

		assertThat(this.registry.qualificationRequest(id).configurationRevision()).isEqualTo(1);
		this.registry.recordQualification(id,
				failedAssessment(1, "storage-unavailable", this.registry.get(id).bindings()));

		assertThat(this.registry.get(id).activeRevision()).isEqualTo(1);
		assertThat(this.registry.get(id).eligible()).isFalse();
	}

	@Test
	void bindingRevisionChangeRequiresFreshQualificationEvidence() {
		UUID id = eligibleRunOutput();
		List<TargetStorageBinding> rotated = readyBindings().stream()
			.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), 2, binding.readiness()))
			.toList();

		this.registry.replaceBindings(id, this.registry.get(id).registrationRevision(), rotated);

		assertThat(this.registry.get(id).eligible()).isFalse();
		this.registry.recordQualification(id, successfulAssessment(1, rotated));
		assertThat(this.registry.get(id).eligible()).isTrue();
	}

	@Test
	void qualificationFinishingAfterBindingRotationIsRetainedButCannotChangeEligibility() {
		UUID id = eligibleRunOutput();
		TargetStorageQualificationRequest staleRequest = this.registry.qualificationRequest(id);
		List<TargetStorageBinding> rotated = staleRequest.bindings()
			.stream()
			.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), 2, binding.readiness()))
			.toList();
		this.registry.replaceBindings(id, this.registry.get(id).registrationRevision(), rotated);
		TargetStorageAssessment staleAssessment = successfulAssessment(staleRequest.configurationRevision(),
				staleRequest.bindings());

		this.registry.recordQualification(id, staleAssessment);

		assertThat(this.registry.get(id).assessments()).contains(staleAssessment);
		assertThat(this.registry.get(id).eligible()).isFalse();
		assertThat(this.registry.get(id).availability()).isEqualTo(CapabilityAvailability.TRANSIENTLY_UNAVAILABLE);
	}

	@Test
	void delayedAssessmentForOldBindingsCannotHideNewerCurrentEvidence() {
		UUID id = eligibleRunOutput();
		List<TargetStorageBinding> oldBindings = this.registry.get(id).bindings();
		List<TargetStorageBinding> currentBindings = oldBindings.stream()
			.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), 2, binding.readiness()))
			.toList();
		this.registry.replaceBindings(id, this.registry.get(id).registrationRevision(), currentBindings);
		this.registry.recordQualification(id, successfulAssessment(1, currentBindings));

		this.registry.recordQualification(id, successfulAssessment(1, oldBindings));

		assertThat(this.registry.get(id).eligible()).isTrue();
	}

	@Test
	void olderObservationForCurrentBindingsCannotOverwriteNewerAvailability() {
		UUID id = eligibleRunOutput();
		List<TargetStorageBinding> bindings = this.registry.get(id).bindings();
		this.registry.recordQualification(id, successfulAssessment(1, bindings, Instant.parse("2026-08-19T10:00:00Z")));

		this.registry.recordQualification(id,
				failedAssessment(1, "delayed-failure", bindings, Instant.parse("2026-08-19T09:00:00Z")));

		assertThat(this.registry.get(id).availability()).isEqualTo(CapabilityAvailability.AVAILABLE);
		assertThat(this.registry.get(id).eligible()).isTrue();
	}

	@Test
	void laterRecordedAssessmentWinsWhenObservationTimestampsTie() {
		UUID id = eligibleRunOutput();
		List<TargetStorageBinding> bindings = this.registry.get(id).bindings();

		this.registry.recordQualification(id,
				failedAssessment(1, "same-time-failure", bindings, Instant.parse("2026-08-19T08:00:02Z")));

		assertThat(this.registry.get(id).availability()).isEqualTo(CapabilityAvailability.INCOMPATIBLE);
		assertThat(this.registry.get(id).eligible()).isFalse();
	}

	@Test
	void purposeAndBucketAreImmutableAndKnownResourceCannotCrossPurposes() {
		this.registry.register("Datasets", TargetStoragePurpose.DATASET, "shared",
				configuration("http://storage.example", "eu-central-1"), readyBindings());

		assertThatThrownBy(() -> this.registry.register("Outputs", TargetStoragePurpose.RUN_OUTPUT, "shared",
				configuration("http://storage.example", "eu-central-1"), readyBindings()))
			.isInstanceOf(TargetStorageConflictException.class)
			.hasMessageContaining("TARGET_STORAGE_PURPOSE_CONFLICT");
	}

	@Test
	void equivalentEndpointSpellingsCannotClaimTheSameBucket() {
		this.registry.register("Datasets", TargetStoragePurpose.DATASET, "shared",
				configuration("HTTP://STORAGE.EXAMPLE:80/", "eu-central-1"), readyBindings());

		assertThatThrownBy(() -> this.registry.register("Outputs", TargetStoragePurpose.RUN_OUTPUT, "shared",
				configuration("http://storage.example", "eu-central-1"), readyBindings()))
			.isInstanceOf(TargetStorageConflictException.class)
			.hasMessageContaining("TARGET_STORAGE_PURPOSE_CONFLICT");
	}

	@Test
	void encodedSlashRemainsDistinctFromAPathSeparatorInResourceClaims() {
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example./")))
			.isEqualTo("https://storage.example");
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example/base%2Fsegment")))
			.isEqualTo("https://storage.example/base%2Fsegment")
			.isNotEqualTo(
					TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example/base/segment")));
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example/base%2fsegment")))
			.isEqualTo("https://storage.example/base%2Fsegment");
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example/%7eowner")))
			.isEqualTo("https://storage.example/~owner");
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example//tenant")))
			.isEqualTo("https://storage.example//tenant")
			.isNotEqualTo(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example")));
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example//tenant/..")))
			.isEqualTo("https://storage.example//");
		assertThat(TargetStorageResourceClaim.canonicalEndpoint(URI.create("https://storage.example//.")))
			.isEqualTo("https://storage.example//");
	}

	@Test
	void descriptorAndCredentialBindingComeFromOneRegistrySnapshotDuringRotation() {
		UUID id = eligibleRunOutput();
		TargetStorageBinding expected = this.registry.get(id)
			.bindings()
			.stream()
			.filter(binding -> binding.role() == TargetStorageRole.BACKEND)
			.findFirst()
			.orElseThrow();
		TargetStorageCredentialAccess credentials = (bindingId, bindingRevision, consumingRole) -> {
			assertThat(bindingId).isEqualTo(expected.bindingId());
			assertThat(bindingRevision).isEqualTo(expected.bindingRevision());
			List<TargetStorageBinding> rotated = this.registry.get(id)
				.bindings()
				.stream()
				.map(binding -> new TargetStorageBinding(binding.role(), binding.bindingId(), 2, binding.readiness()))
				.toList();
			this.registry.replaceBindings(id, this.registry.get(id).registrationRevision(), rotated);
			return java.util.Optional.of(AnonymousCredentialsProvider.create());
		};

		var resolved = new TargetStorageResolver(this.registry, credentials).resolveRunOutput(id, "backend", "project",
				"run");

		assertThat(resolved.storageId()).isEqualTo(id.toString());
		assertThat(this.registry.get(id).eligible()).isFalse();
	}

	@Test
	void datasetEligibilityDoesNotRequireTheRunOutputOnlyMetricViewBinding() {
		List<TargetStorageBinding> bindings = readyBindings().stream()
			.filter(binding -> binding.role() != TargetStorageRole.METRIC_VIEW)
			.toList();
		UUID id = this.registry.register("Datasets", TargetStoragePurpose.DATASET, "datasets",
				configuration("http://storage.example", "eu-central-1"), bindings);
		this.registry.recordQualification(id, successfulAssessment(1, bindings));
		this.registry.activate(id, this.registry.get(id).registrationRevision());

		assertThat(this.registry.get(id).eligible()).isTrue();
	}

	@Test
	void automaticQualificationWaitsForEveryPurposeSpecificBindingRole() {
		UUID incomplete = this.registry.register("Incomplete", TargetStoragePurpose.RUN_OUTPUT, "incomplete",
				configuration("http://incomplete.example", "eu-central-1"), List.of());
		AtomicInteger probes = new AtomicInteger();
		var qualification = new TargetStorageQualification(this.registry, request -> {
			probes.incrementAndGet();
			return failedAssessment(request.configurationRevision(), "not-available", request.bindings());
		});

		qualification.qualifyWhenReady(incomplete);

		assertThat(probes).hasValue(0);
	}

	@Test
	void defaultsAreUniquePerTargetClassAndNeverFallBackAcrossClasses() {
		UUID local = eligibleRunOutput();
		UUID cloud = eligibleRunOutput("Cloud outputs", "cloud-runs", "http://cloud.example");
		this.registry.assignDefaults(TargetClass.LOCAL_SINGLE_GPU, local, true, local);
		this.registry.assignDefaults(TargetClass.CLOUD_SPOT, cloud, false, cloud);

		assertThat(this.registry.resolve(TargetClass.LOCAL_SINGLE_GPU, null, null))
			.isEqualTo(new TargetStorageSelection(local, true, local));
		assertThat(this.registry.resolve(TargetClass.CLOUD_SPOT, null, null))
			.isEqualTo(new TargetStorageSelection(cloud, false, cloud));
		assertThatThrownBy(() -> this.registry.resolve(TargetClass.CLOUD_ON_DEMAND, null, null))
			.isInstanceOf(TargetStorageIneligibleException.class)
			.hasMessageContaining("TARGET_STORAGE_DEFAULT_MISSING");
	}

	@Test
	void deactivationPreservesResolutionButPreventsNewSelectionAndReferencedDeletion() {
		UUID id = eligibleRunOutput();
		this.registry.assignDefaults(TargetClass.LOCAL_MULTI_GPU, id, true, id);
		TargetStorageDescriptor before = this.registry.resolveDescriptor(id);

		this.registry.deactivate(id, this.registry.get(id).registrationRevision());

		assertThat(this.registry.resolveDescriptor(id)).isEqualTo(before);
		assertThatThrownBy(() -> this.registry.resolve(TargetClass.LOCAL_MULTI_GPU, null, null))
			.isInstanceOf(TargetStorageIneligibleException.class);
		assertThatThrownBy(() -> this.registry.delete(id)).isInstanceOf(TargetStorageReferencedException.class)
			.hasMessageContaining("TARGET_STORAGE_REFERENCED");
	}

	@Test
	void failureConstructionCannotCarryCredentialValues() {
		assertThatThrownBy(() -> TargetStorageCapabilityResult.failure("put-object", "access-denied",
				"The binding cannot perform PutObject", Map.of("provider-detail", "do-not-store")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("free-form capability observations");
	}

	@Test
	void incompleteCapabilityEvidenceCannotBePromoted() {
		assertThatThrownBy(
				() -> new TargetStorageAssessment(UUID.randomUUID(), 1, Instant.parse("2026-08-19T08:00:00Z"),
						Instant.parse("2026-08-19T08:00:02Z"), CapabilityAvailability.AVAILABLE, List.of(),
						List.of(TargetStorageCapabilityResult.success("put-object"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("every required S3 capability");
	}

	private UUID eligibleRunOutput() {
		return eligibleRunOutput("Run outputs", "runs", "http://storage.example");
	}

	private UUID eligibleRunOutput(String name, String bucket, String endpoint) {
		List<TargetStorageBinding> bindings = readyBindings();
		UUID id = this.registry.register(name, TargetStoragePurpose.RUN_OUTPUT, bucket,
				configuration(endpoint, "eu-central-1"), bindings);
		this.registry.recordQualification(id, successfulAssessment(1, bindings));
		this.registry.activate(id, this.registry.get(id).registrationRevision());
		return id;
	}

	private static TargetStorageConfiguration configuration(String endpoint, String region) {
		return new TargetStorageConfiguration(URI.create(endpoint), region, true, Map.of());
	}

	private static List<TargetStorageBinding> readyBindings() {
		return List.of(
				new TargetStorageBinding(TargetStorageRole.TRAINING_PROCESS, UUID.randomUUID(), 1,
						BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.BACKEND, UUID.randomUUID(), 1, BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.TRANSFER_WORKER, UUID.randomUUID(), 1,
						BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.METRIC_VIEW, UUID.randomUUID(), 1, BindingReadiness.READY));
	}

	private static TargetStorageAssessment successfulAssessment(long revision, List<TargetStorageBinding> bindings) {
		return successfulAssessment(revision, bindings, Instant.parse("2026-08-19T08:00:02Z"));
	}

	private static TargetStorageAssessment successfulAssessment(long revision, List<TargetStorageBinding> bindings,
			Instant observedUntil) {
		return new TargetStorageAssessment(UUID.randomUUID(), revision, observedUntil.minusSeconds(2), observedUntil,
				CapabilityAvailability.AVAILABLE, bindings.stream().map(TargetStorageBindingRevision::from).toList(),
				RunStoreS3CapabilityFloor.requiredCapabilities()
					.stream()
					.map(TargetStorageCapabilityResult::success)
					.toList());
	}

	private static TargetStorageAssessment failedAssessment(long revision, String code,
			List<TargetStorageBinding> bindings) {
		return failedAssessment(revision, code, bindings, Instant.parse("2026-08-19T08:00:04Z"));
	}

	private static TargetStorageAssessment failedAssessment(long revision, String code,
			List<TargetStorageBinding> bindings, Instant observedUntil) {
		return new TargetStorageAssessment(UUID.randomUUID(), revision, observedUntil.minusSeconds(2), observedUntil,
				CapabilityAvailability.INCOMPATIBLE, bindings.stream().map(TargetStorageBindingRevision::from).toList(),
				RunStoreS3CapabilityFloor.requiredCapabilities()
					.stream()
					.map(capability -> "conditional-create".equals(capability)
							? TargetStorageCapabilityResult.failure(capability, code,
									"Conditional creation was not enforced", Map.of())
							: TargetStorageCapabilityResult.success(capability))
					.toList());
	}

}
