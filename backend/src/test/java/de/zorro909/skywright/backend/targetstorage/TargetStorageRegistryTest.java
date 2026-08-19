package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

final class TargetStorageRegistryTest {

	private final InMemoryTargetStorageRepository repository = new InMemoryTargetStorageRepository();

	private final AtomicReference<BindingReadiness> readiness = new AtomicReference<>(BindingReadiness.READY);

	private final TargetStorageRegistry registry = new TargetStorageRegistry(this.repository,
			(bindingId, bindingRevision, consumingRole) -> this.readiness.get(), Optional.empty(), storageId -> false);

	@Test
	void successfulQualificationPromotesCandidateAndDerivesEligibility() {
		UUID id = this.registry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				configuration("http://storage.example", "eu-central-1"), readyBindings());
		TargetStorageAssessment assessment = successfulAssessment(1);
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
		this.registry.recordQualification(id, failedAssessment(2, "conditional-create-not-enforced"));

		TargetStorageView storage = this.registry.get(id);
		assertThat(storage.activeRevision()).isEqualTo(1);
		assertThat(storage.candidateRevision()).isEqualTo(2);
		assertThat(storage.configuration().endpoint()).isEqualTo(URI.create("http://storage.example"));
		assertThat(storage.eligible()).isTrue();
		assertThat(storage.revisions()).extracting(TargetStorageRevisionView::state)
			.containsExactly("active", "candidate");
	}

	@Test
	void staleRevisionCannotOverwriteAConcurrentEdit() {
		UUID id = eligibleRunOutput();
		long staleRevision = this.registry.get(id).registrationRevision();
		this.registry.rename(id, staleRevision, "Primary outputs");

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

		this.registry.recordQualification(id, successfulAssessment(2));

		assertThat(this.registry.get(id).activeRevision()).isEqualTo(1);
		assertThat(this.registry.get(id).candidateRevision()).isEqualTo(3);
	}

	@Test
	void qualificationThatUsedReplacedBindingRevisionsCannotPromoteACandidate() {
		UUID id = this.registry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				configuration("http://storage.example", "eu-central-1"), readyBindings());
		this.registry.replaceBindings(id, this.registry.get(id).registrationRevision(), readyBindings(2));

		this.registry.recordQualification(id, successfulAssessment(1));

		assertThat(this.registry.get(id).activeRevision()).isNull();
		assertThat(this.registry.get(id).candidateRevision()).isEqualTo(1);
		assertThat(this.registry.get(id).assessments()).hasSize(1);
	}

	@Test
	void activeRevisionCanBeRequalifiedToExposeCurrentUnavailability() {
		UUID id = eligibleRunOutput();

		assertThat(this.registry.qualificationRequest(id).configurationRevision()).isEqualTo(1);
		this.registry.recordQualification(id, failedAssessment(1, "storage-unavailable"));

		assertThat(this.registry.get(id).activeRevision()).isEqualTo(1);
		assertThat(this.registry.get(id).eligible()).isFalse();
	}

	@Test
	void currentBindingReadinessMakesPreviouslyEligibleStorageUnavailable() {
		UUID id = eligibleRunOutput();
		this.registry.assignDefaults(TargetClass.LOCAL_SINGLE_GPU, id, true, id);

		this.readiness.set(BindingReadiness.EXPIRED);

		assertThat(this.registry.get(id).eligible()).isFalse();
		assertThat(this.registry.get(id).bindings())
			.allMatch(binding -> binding.readiness() == BindingReadiness.EXPIRED);
		assertThatThrownBy(() -> this.registry.resolve(TargetClass.LOCAL_SINGLE_GPU, null, null))
			.isInstanceOf(TargetStorageIneligibleException.class)
			.hasMessageContaining("TARGET_STORAGE_INELIGIBLE");
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
	void resolvesTheExistingRunStoreDescriptorWithRoleScopedCredentials() {
		var provider = StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret"));
		var resolvingRegistry = new TargetStorageRegistry(new InMemoryTargetStorageRepository(),
				(bindingId, bindingRevision, consumingRole) -> BindingReadiness.READY,
				Optional.of((bindingId, bindingRevision, consumingRole) -> Optional.of(provider)), storageId -> false);
		UUID id = resolvingRegistry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				new TargetStorageConfiguration(URI.create("http://storage.example"), "eu-central-1", true,
						Map.of("chunkedEncoding", "true")),
				readyBindings());
		resolvingRegistry.recordQualification(id, successfulAssessment(1));

		var resolved = resolvingRegistry.resolveRunStore(id, TargetStorageRole.BACKEND, "project", "run");

		assertThat(resolved.storageId()).isEqualTo(id.toString());
		assertThat(resolved.credentials()).isSameAs(provider);
		assertThat(resolved.compatibilityOptions()).containsEntry("chunkedEncoding", "true");
	}

	@Test
	void failureConstructionCannotCarryCredentialValues() {
		assertThatThrownBy(() -> TargetStorageCapabilityResult.failure("put-object", "access-denied",
				"The binding cannot perform PutObject", Map.of("secretAccessKey", "do-not-store")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("secret-bearing");
	}

	@Test
	void incompleteRegistrationIsRetainedButIneligible() {
		UUID id = this.registry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				configuration("http://storage.example", "eu-central-1"),
				List.of(new TargetStorageBinding(TargetStorageRole.BACKEND, UUID.randomUUID(), 1,
						BindingReadiness.MISSING)));

		assertThat(this.registry.get(id).eligible()).isFalse();
		assertThat(this.registry.get(id).bindings()).hasSize(1);
	}

	@Test
	void assessmentForRegistrationWithoutBindingsRoundTripsThroughPersistenceEncoding() {
		Instant observed = Instant.parse("2026-08-19T08:00:00Z");
		var assessment = new TargetStorageAssessment(UUID.randomUUID(), 1, observed, observed,
				CapabilityAvailability.TRANSIENTLY_UNAVAILABLE, List.of(),
				TargetStorageCapabilities.REQUIRED.stream()
					.map(capability -> TargetStorageCapabilityResult.failure(capability, "binding-unavailable",
							"A ready binding is required", Map.of()))
					.toList());

		assertThat(TargetStorageEncoding.assessment(TargetStorageEncoding.assessment(assessment)))
			.isEqualTo(assessment);
	}

	@Test
	void failedOnDemandQualificationIsRecordedBeforeReturningTheStableFailure() {
		UUID id = this.registry.register("Run outputs", TargetStoragePurpose.RUN_OUTPUT, "runs",
				configuration("http://storage.example", "eu-central-1"), readyBindings());
		TargetStorageAssessment assessment = failedAssessment(1, "conditional-create-not-enforced");
		var qualification = new TargetStorageQualification(this.registry, request -> assessment);

		assertThatThrownBy(() -> qualification.qualify(id))
			.isInstanceOf(TargetStorageQualificationFailedException.class)
			.hasMessageContaining("TARGET_STORAGE_QUALIFICATION_FAILED");
		assertThat(this.registry.get(id).assessments()).containsExactly(assessment);
		assertThat(this.registry.get(id).candidateRevision()).isEqualTo(1);
	}

	private UUID eligibleRunOutput() {
		return eligibleRunOutput("Run outputs", "runs", "http://storage.example");
	}

	private UUID eligibleRunOutput(String name, String bucket, String endpoint) {
		UUID id = this.registry.register(name, TargetStoragePurpose.RUN_OUTPUT, bucket,
				configuration(endpoint, "eu-central-1"), readyBindings());
		this.registry.recordQualification(id, successfulAssessment(1));
		this.registry.activate(id, this.registry.get(id).registrationRevision());
		return id;
	}

	private static TargetStorageConfiguration configuration(String endpoint, String region) {
		return new TargetStorageConfiguration(URI.create(endpoint), region, true, Map.of());
	}

	private static List<TargetStorageBinding> readyBindings() {
		return readyBindings(1);
	}

	private static List<TargetStorageBinding> readyBindings(long revision) {
		return List.of(
				new TargetStorageBinding(TargetStorageRole.TRAINING_PROCESS,
						bindingId(TargetStorageRole.TRAINING_PROCESS), revision, BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.BACKEND, bindingId(TargetStorageRole.BACKEND), revision,
						BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.TRANSFER_WORKER,
						bindingId(TargetStorageRole.TRANSFER_WORKER), revision, BindingReadiness.READY),
				new TargetStorageBinding(TargetStorageRole.METRIC_VIEW, bindingId(TargetStorageRole.METRIC_VIEW),
						revision, BindingReadiness.READY));
	}

	private static UUID bindingId(TargetStorageRole role) {
		return UUID.nameUUIDFromBytes(role.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static TargetStorageAssessment successfulAssessment(long revision) {
		return new TargetStorageAssessment(UUID.randomUUID(), revision, Instant.parse("2026-08-19T08:00:00Z"),
				Instant.parse("2026-08-19T08:00:02Z"), CapabilityAvailability.AVAILABLE, readyBindings(),
				TargetStorageCapabilities.REQUIRED.stream().map(TargetStorageCapabilityResult::success).toList());
	}

	private static TargetStorageAssessment failedAssessment(long revision, String code) {
		return new TargetStorageAssessment(UUID.randomUUID(), revision, Instant.parse("2026-08-19T08:00:00Z"),
				Instant.parse("2026-08-19T08:00:02Z"), CapabilityAvailability.INCOMPATIBLE, readyBindings(),
				List.of(TargetStorageCapabilityResult.failure("conditional-create", code,
						"Conditional creation was not enforced", Map.of())));
	}

}
