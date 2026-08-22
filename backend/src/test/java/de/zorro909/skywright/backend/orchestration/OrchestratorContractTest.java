package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class OrchestratorContractTest {

	@Test
	void publicContractContainsOnlyImmutableJavaValues() {
		var resultTypes = Stream.of(Orchestrator.class.getMethods())
			.flatMap(method -> Stream.concat(Stream.of(method.getGenericReturnType().getTypeName()),
					Arrays.stream(method.getGenericParameterTypes()).map(type -> type.getTypeName())))
			.toList();

		assertThat(resultTypes).noneMatch(name -> name.contains("org.graalvm") || name.contains("polyglot"));
		assertThat(Stream
			.of(OrchestratorOperation.class, OperationOutcome.Submitted.class, OperationOutcome.Observed.class,
					OperationOutcome.Controlled.class, OperationOutcome.Cleaned.class, OperationOutcome.Failed.class)
			.flatMap(type -> Arrays.stream(type.getAnnotations())))
			.noneMatch(annotation -> annotation.annotationType() == Entity.class);
		assertThat(Stream.of(OperationOutcome.Observed.class.getRecordComponents()).map(RecordComponent::getType))
			.doesNotContain(org.graalvm.polyglot.Value.class, org.graalvm.polyglot.Context.class);
	}

	@Test
	void taskContractRetainsImmutableAcceleratorImageCandidates() {
		var cuda = new OrchestratorTaskSpecification.Resources("aws", "8", "32", "A100:1",
				"docker:project@sha256:cuda");
		var rocm = new OrchestratorTaskSpecification.Resources("kubernetes/rocm", "8", "32", "MI300X:1",
				"docker:project@sha256:rocm");
		var candidates = new ArrayList<>(List.of(cuda, rocm));

		var task = new OrchestratorTaskSpecification("run-1", null, "train", candidates, Map.of());
		candidates.clear();

		assertThat(task.resources()).containsExactly(cuda, rocm);
	}

}
