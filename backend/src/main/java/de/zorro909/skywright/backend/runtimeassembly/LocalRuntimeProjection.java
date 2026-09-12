package de.zorro909.skywright.backend.runtimeassembly;

import java.util.List;
import java.util.Map;
import java.util.Set;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import org.mapstruct.factory.Mappers;

/** Projects one accepted definition onto one qualified local Kubernetes AMD target. */
public final class LocalRuntimeProjection {

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
		return project(definition, materials, target, runtimePullSecret, runtimePullNamespace, false);
	}

	public OrchestratorTaskSpecification project(RunDefinition definition, RuntimeMaterials materials, Target target,
			String runtimePullSecret, String runtimePullNamespace, boolean writerAuthorityEnabled) {
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

		if (runtimePullSecret != null && !runtimePullSecret.equals("skywright-pull-" + materials.runId()))
			throw new IllegalArgumentException("Pull projection belongs to a different Run");
		String command = RuntimeDelivery.command(definition, materials, "rocm");
		if (writerAuthorityEnabled)
			command = "set -e\npython -c 'import skywright._writer_authority'\n" + command;
		var resources = new OrchestratorTaskSpecification.Resources("kubernetes/" + target.kubernetesContext(),
				target.cpus(), target.memory(), target.gpuModel() + ":" + request.path("gpuCount").asInt(),
				"docker:" + materials.image(), false, new OrchestratorTaskSpecification.JobRecovery(0, List.of(75)));
		return this.mapper.map(new TaskPlan("skywright-" + materials.runId(), null, command, List.of(resources),
				writerAuthorityEnabled
						? Map.of("SKYWRIGHT_WRITER_AUTHORITY_SOCKET", "/run/skywright-writer/authority.sock")
						: Map.of(),
				runtimePullSecret, runtimePullNamespace));
	}

}
