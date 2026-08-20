package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.zorro909.skywright.backend.boundary.generated.model.StageTargetStorageRevision;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class TargetStorageHttpAdapterTest {

	@Test
	void stagingRefreshesBindingReadinessBeforeAutomaticQualification() {
		var registry = new TargetStorageRegistry(new InMemoryTargetStorageRepository());
		UUID storageId = registry.register("Outputs", TargetStoragePurpose.RUN_OUTPUT, "outputs",
				new TargetStorageConfiguration(URI.create("https://storage.example"), "eu-central-1", true, Map.of()),
				Arrays.stream(TargetStorageRole.values())
					.map(role -> new TargetStorageBinding(role, UUID.randomUUID(), 1, BindingReadiness.MISSING))
					.toList());
		AtomicBoolean qualified = new AtomicBoolean();
		var qualification = new TargetStorageQualification(registry, request -> {
			qualified.set(true);
			throw new QualificationAttempted();
		});
		var adapter = new TargetStorageHttpAdapter(registry, qualification,
				(bindingId, bindingRevision, consumingRole) -> BindingReadiness.READY);
		var generatedConfiguration = new de.zorro909.skywright.backend.boundary.generated.model.TargetStorageConfiguration(
				"https://storage.example", "eu-central-1", true, Map.of());

		assertThatThrownBy(() -> adapter.stageTargetStorageRevision(storageId,
				new StageTargetStorageRevision(registry.get(storageId).registrationRevision(), generatedConfiguration)))
			.isInstanceOf(QualificationAttempted.class);

		assertThat(qualified).isTrue();
		assertThat(registry.get(storageId).bindings())
			.allMatch(binding -> binding.readiness() == BindingReadiness.READY);
	}

	private static final class QualificationAttempted extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

}
