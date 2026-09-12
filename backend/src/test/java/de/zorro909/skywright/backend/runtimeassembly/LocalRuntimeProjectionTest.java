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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void vastProjectionPinsCudaPurchaseModeAndPreservesTheAcceptedWorkload(boolean spot) throws Exception {
		var value = RunDefinition.decode(Files.readString(ROOT.resolve("definition.json"))).value();
		value.withObject("/targetRequest")
			.put("targetClass", spot ? "cloud-spot" : "cloud-on-demand")
			.put("purchaseMode", spot ? "spot" : "on-demand")
			.put("target", "vast")
			.put("gpuModel", "RTX_3060");
		var quote = JSON
			.readTree(Files.readString(Path.of("../sdk/src/skywright/_run_definition_resources/corpus.json")))
			.at("/valid/0/costQuote");
		value.set("costQuote", quote);
		var definition = RunDefinition.from(value);
		var local = materials();
		var cuda = new RuntimeMaterials(local.materialsVersion(), local.runId(),
				"ghcr.io/example/project@sha256:" + "a".repeat(64), local.configurationContract(),
				local.metricContract(), local.dataset(), local.datasetLocation(), local.sourceCheckpoint());
		var target = new VastRuntimeProjection.Target("US", "1x-RTX_3060-16384", "RTX_3060", 12L * 1024 * 1024 * 1024,
				"2", "8", 20, new java.math.BigDecimal("0.14"), spot ? new java.math.BigDecimal("0.04") : null);
		var task = new VastRuntimeProjection().project(definition, cuda, target);
		assertThat(task.resources()).hasSize(1);
		var resource = task.resources().getFirst();
		assertThat(resource.infrastructure()).isEqualTo("vast");
		assertThat(resource.useSpot()).isEqualTo(spot);
		assertThat(resource.maxBidHourlyCost()).isEqualTo(spot ? new java.math.BigDecimal("0.04") : null);
		assertThat(resource.imageId()).isEqualTo("docker:ghcr.io/example/project@sha256:" + "a".repeat(64));
		assertThat(resource.region()).isEqualTo("US");
		assertThat(resource.instanceType()).isEqualTo("1x-RTX_3060-16384");
		assertThat(resource.diskSize()).isEqualTo(20);
		assertThat(resource.maxHourlyCost()).isEqualByComparingTo("0.14");
		assertThat(task.runtimePullSecret()).isNull();
		assertThat(task.environment()).isEmpty();
		// Execute the real delivery shell against a child-process probe: registry secrets
		// must be gone before even the first Python child, while Dataset credentials
		// survive.
		var probe = Files.createTempDirectory("skywright-registry-isolation-");
		try {
			var executable = probe.resolve("python");
			Files.writeString(executable, "#!/bin/sh\n" + "test -z \"${SKYPILOT_DOCKER_USERNAME+x}\" && "
					+ "test -z \"${SKYPILOT_DOCKER_PASSWORD+x}\" && " + "test -z \"${SKYPILOT_DOCKER_SERVER+x}\" && "
					+ "test \"$SKYWRIGHT_DATASET_ACCESS_KEY_ID\" = dataset-test && " + "printf isolated\nexit 73\n");
			Files.setPosixFilePermissions(executable,
					java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			var process = new ProcessBuilder("/bin/sh", "-c", task.run()).redirectErrorStream(true);
			process.environment().put("PATH", probe.toString());
			process.environment().put("SKYPILOT_DOCKER_USERNAME", "registry-test");
			process.environment().put("SKYPILOT_DOCKER_PASSWORD", "registry-test-secret");
			process.environment().put("SKYPILOT_DOCKER_SERVER", "ghcr.io");
			process.environment().put("SKYWRIGHT_DATASET_ACCESS_KEY_ID", "dataset-test");
			var child = process.start();
			assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			assertThat(child.exitValue()).isEqualTo(73);
			assertThat(new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
				.isEqualTo("isolated");
		}
		finally {
			Files.deleteIfExists(probe.resolve("python"));
			Files.deleteIfExists(probe);
		}
		var payload = Pattern.compile("b64decode\\('([^']+)'\\)").matcher(task.run());
		assertThat(payload.find()).isTrue();
		assertThat(new String(Base64.getDecoder().decode(payload.group(1)), java.nio.charset.StandardCharsets.UTF_8))
			.isEqualTo(definition.encode());
		assertThatThrownBy(() -> new VastRuntimeProjection().project(definition, local, target))
			.hasMessage("Missing digest-pinned target image");
		value.withObject("/targetRequest").put("target", "runpod");
		assertThatThrownBy(() -> new VastRuntimeProjection().project(RunDefinition.from(value), cuda, target))
			.hasMessage("Unsupported Vast capabilities or pinned target");
	}

	@Test
	void vastCatalogConstraintsRequireAnExplicitSpotBidBelowTheOwnersExclusiveHourlyLimit() {
		for (var amount : java.util.List.of("0", "0.15", "0.20"))
			assertThatThrownBy(() -> new VastRuntimeProjection.Target("US", "1x-RTX_3060-16384", "RTX_3060", 12L << 30,
					"2", "8", 20, new java.math.BigDecimal(amount)))
				.hasMessage("Invalid Vast resource constraints");
		assertThatThrownBy(
				() -> new de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification.Resources("vast",
						"2", "8", "RTX_3060:1", null, true, null, "US", "1x-RTX_3060-16384", 20,
						new java.math.BigDecimal("0.14")))
			.hasMessage("Vast interruptible requires an explicit bounded bid");
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
			.containsExactly("infrastructure", "cpus", "memory", "accelerators", "imageId", "useSpot", "jobRecovery",
					"region", "instanceType", "diskSize", "maxHourlyCost", "maxBidHourlyCost");
	}

	@Test
	void qualifiedWriterAuthorityIsAnOperatorProjectionOutsideTheRunDefinition() throws Exception {
		var definition = RunDefinition.decode(Files.readString(ROOT.resolve("definition.json")));
		var materials = materials();
		var projector = new LocalRuntimeProjection();
		var ordinary = projector.project(definition, materials, TARGET, null);
		var qualified = projector.project(definition, materials, TARGET, null, null, true);
		assertThat(qualified.environment()).containsOnly(
				java.util.Map.entry("SKYWRIGHT_WRITER_AUTHORITY_SOCKET", "/run/skywright-writer/authority.sock"));
		assertThat(qualified.run())
			.isEqualTo("set -e\npython -c 'import skywright._writer_authority'\n" + ordinary.run());
		assertThat(qualified.resources()).isEqualTo(ordinary.resources());
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
