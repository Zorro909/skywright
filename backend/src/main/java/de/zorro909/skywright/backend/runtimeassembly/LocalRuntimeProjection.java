package de.zorro909.skywright.backend.runtimeassembly;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.json.JsonMapper;

/** Projects one accepted definition onto one qualified local Kubernetes AMD target. */
public final class LocalRuntimeProjection {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> FIELDS = Set.of("schemaVersion", "trainingProjectVersion", "configuration",
			"datasetDefinition", "targetRequest", "storage", "executionPolicy", "orderingReset");

	private final LocalTaskMapper mapper = Mappers.getMapper(LocalTaskMapper.class);

	public record Target(String identity, String kubernetesContext, String gpuModel, int maximumGpuCount,
			long gpuMemoryBytes, String cpus, String memory) {
		public Target {
			if (identity == null || identity.isBlank() || kubernetesContext == null
					|| !kubernetesContext.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || gpuModel == null
					|| !gpuModel.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || maximumGpuCount < 1 || gpuMemoryBytes < 1
					|| cpus == null || memory == null || !cpus.matches("[1-9][0-9]*(?:\\+)?")
					|| !memory.matches("[1-9][0-9]*(?:\\+)?"))
				throw new IllegalArgumentException("Local AMD target qualification is incomplete");
		}
	}

	public record TaskPlan(String jobName, String setupCommand, String trainingCommand,
			List<OrchestratorTaskSpecification.Resources> candidates, Map<String, String> variables, String pullSecret,
			String pullNamespace) {
	}

	public OrchestratorTaskSpecification project(RunDefinition definition, RuntimeMaterials materials, Target target,
			String runtimePullSecret) {
		return project(definition, materials, target, runtimePullSecret, null);
	}

	public OrchestratorTaskSpecification project(RunDefinition definition, RuntimeMaterials materials, Target target,
			String runtimePullSecret, String runtimePullNamespace) {
		var value = definition.value();
		if (value.path("schemaVersion").asInt() != 2 || !value.propertyNames().equals(FIELDS))
			throw new IllegalArgumentException("Unsupported local Run Definition shape");
		var request = value.path("targetRequest");
		if (!Set.of("local-single-gpu", "local-multi-gpu").contains(request.path("targetClass").asText())
				|| !request.path("purchaseMode").asText().equals("local")
				|| request.path("gpuCount").asInt() > target.maximumGpuCount()
				|| (request.has("minimumGpuMemoryBytes") && request.path("minimumGpuMemoryBytes")
					.bigIntegerValue()
					.compareTo(java.math.BigInteger.valueOf(target.gpuMemoryBytes())) > 0)
				|| (request.has("target") && !request.path("target").asText().equals(target.identity()))
				|| (request.has("gpuModel") && !request.path("gpuModel").asText().equals(target.gpuModel())))
			throw new IllegalArgumentException("Unsupported local AMD capabilities or pinned target");
		var version = value.path("trainingProjectVersion");
		String image = materials.image();
		if (image == null || !image.matches("[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}")
				|| !image.endsWith("@" + version.at("/images/rocm").asText()))
			throw new IllegalArgumentException("Missing digest-pinned ROCm image");
		if (!digest(materials.configurationContract()).equals(version.at("/configurationContract/digest").asText())
				|| !digest(materials.metricContract()).equals(version.at("/metricContract/digest").asText()))
			throw new IllegalArgumentException("Pinned project contract is missing or differs");
		var dataset = materials.dataset();
		if (!dataset.datasetIdentity().equals(value.at("/datasetDefinition/datasetIdentity").asText())
				|| !dataset.version().equals(value.at("/datasetDefinition/version").asText())
				|| !dataset.contentFingerprint().equals(value.at("/datasetDefinition/contentFingerprint").asText()))
			throw new IllegalArgumentException("Dataset materials differ from the accepted definition");
		if (runtimePullSecret != null && !runtimePullSecret.equals("skywright-pull-" + materials.runId()))
			throw new IllegalArgumentException("Pull projection belongs to a different Run");
		String directory = "/tmp/skywright-runtime-" + materials.runId();
		String command = "set -eu\numask 077\npython - <<'SKYWRIGHT_DELIVERY'\nimport base64,pathlib\np=pathlib.Path('"
				+ directory + "');p.mkdir(exist_ok=True)\n(p/'definition.json').write_bytes(base64.b64decode('"
				+ encoded(definition.encode()) + "'))\n(p/'materials.json').write_bytes(base64.b64decode('"
				+ encoded(JSON.writeValueAsString(materials))
				+ "'))\nSKYWRIGHT_DELIVERY\ncd /workspace\nexec python -m skywright._runtime --definition '" + directory
				+ "/definition.json' --materials '" + directory + "/materials.json' --cache-directory '" + directory
				+ "/dataset-cache'";
		var resources = new OrchestratorTaskSpecification.Resources("kubernetes/" + target.kubernetesContext(),
				target.cpus(), target.memory(), target.gpuModel() + ":" + request.path("gpuCount").asInt(),
				"docker:" + image, false, new OrchestratorTaskSpecification.JobRecovery(0, List.of(75)));
		return this.mapper.map(new TaskPlan("skywright-" + materials.runId(), null, command, List.of(resources),
				Map.of(), runtimePullSecret, runtimePullNamespace));
	}

	private static String encoded(String value) {
		if (value.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
			throw new IllegalArgumentException("Runtime delivery document exceeds its byte limit");
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String digest(String value) {
		if (value == null)
			throw new IllegalArgumentException("Missing project contract artifact");
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
