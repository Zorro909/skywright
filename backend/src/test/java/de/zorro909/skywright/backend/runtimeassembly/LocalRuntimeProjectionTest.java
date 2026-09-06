package de.zorro909.skywright.backend.runtimeassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import tools.jackson.databind.json.JsonMapper;

class LocalRuntimeProjectionTest {

	private static final Path ROOT = Path.of("../sdk/tests/fixtures/managed-runtime");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final LocalRuntimeProjection.Target TARGET = new LocalRuntimeProjection.Target("local-amd", "local",
			"MI300X", 4, 192L * 1024 * 1024 * 1024, "8", "32");

	private RuntimeMaterials materials() throws Exception {
		return new RuntimeMaterials(1, UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"registry.example/project@sha256:" + "b".repeat(64),
				Files.readString(ROOT.resolve("configuration.json")).stripTrailing(),
				Files.readString(ROOT.resolve("metrics.json")).stripTrailing(),
				JSON.readValue(Files.readString(ROOT.resolve("dataset.json")), RuntimeMaterials.Dataset.class),
				new RuntimeMaterials.DatasetLocation("datasets", "http://localhost:8333", "datasets", "us-east-1",
						"authority", "authority", 1, null, true, "when_required"),
				null);
	}

	@Test
	void mapsEveryFieldAndDeliversTheUnchangedAcceptedDefinition() throws Exception {
		var definition = RunDefinition.decode(Files.readString(ROOT.resolve("definition.json")));
		var materials = materials();
		var task = new LocalRuntimeProjection().project(definition, materials, TARGET,
				"skywright-pull-" + materials.runId());
		assertThat(task.name()).isEqualTo("skywright-" + materials.runId());
		assertThat(task.setup()).isNull();
		assertThat(task.environment()).isEmpty();
		assertThat(task.runtimePullSecret()).isEqualTo("skywright-pull-" + materials.runId());
		var resource = task.resources().getFirst();
		assertThat(resource.infrastructure()).isEqualTo("kubernetes/local");
		assertThat(resource.cpus()).isEqualTo("8");
		assertThat(resource.memory()).isEqualTo("32");
		assertThat(resource.accelerators()).isEqualTo("MI300X:1");
		assertThat(resource.imageId()).isEqualTo("docker:" + materials.image());
		assertThat(resource.useSpot()).isFalse();
		assertThat(resource.jobRecovery().maxRestartsOnErrors()).isZero();
		assertThat(resource.jobRecovery().recoverOnExitCodes()).containsExactly(75);
		var payloads = Pattern.compile("b64decode\\('([^']+)'\\)")
			.matcher(task.run())
			.results()
			.map(match -> new String(Base64.getDecoder().decode(match.group(1)),
					java.nio.charset.StandardCharsets.UTF_8))
			.toList();
		assertThat(payloads).hasSize(2);
		assertThat(RunDefinition.decode(payloads.getFirst())).isEqualTo(definition);
		assertThat(JSON.readValue(payloads.get(1), RuntimeMaterials.class)).isEqualTo(materials);
		assertThat(task.run()).contains("exec python -m skywright._runtime --definition", "--materials")
			.doesNotContain("_factory", "ACCESS_KEY", "SECRET_KEY");
		assertThat(Stream.of(resource.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
			.containsExactly("infrastructure", "cpus", "memory", "accelerators", "imageId", "useSpot", "jobRecovery");
	}

	@Test
	void rejectsUnavailableCapabilitiesAndMismatchedArtifactsBeforeSubmission() throws Exception {
		var accepted = RunDefinition.decode(Files.readString(ROOT.resolve("definition.json")));
		var materials = materials();
		for (String field : java.util.List.of("target", "gpuModel", "gpuCount", "minimumGpuMemoryBytes")) {
			var value = accepted.value();
			var target = (tools.jackson.databind.node.ObjectNode) value.path("targetRequest");
			if (field.equals("gpuCount")) {
				target.put("gpuCount", 5);
				target.put("targetClass", "local-multi-gpu");
			}
			else if (field.equals("minimumGpuMemoryBytes"))
				target.put(field, java.math.BigInteger.ONE.shiftLeft(64));
			else
				target.put(field, "unavailable");
			assertThatThrownBy(
					() -> new LocalRuntimeProjection().project(RunDefinition.from(value), materials, TARGET, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
		var invalid = new RuntimeMaterials(1, materials.runId(), "registry.example/project:latest",
				materials.configurationContract(), materials.metricContract(), materials.dataset(),
				materials.datasetLocation(), null);
		assertThatThrownBy(() -> new LocalRuntimeProjection().project(accepted, invalid, TARGET, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LocalRuntimeProjection().project(accepted, materials, TARGET,
				"skywright-pull-" + UUID.randomUUID()))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
