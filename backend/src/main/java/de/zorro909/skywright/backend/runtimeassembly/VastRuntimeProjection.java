package de.zorro909.skywright.backend.runtimeassembly;

import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** CUDA projection only. Catalog filtering does not authorize a paid rental. */
public final class VastRuntimeProjection {

	public record Target(String region, String instanceType, String gpuModel, long gpuMemoryBytes, String cpus,
			String memory, int diskSize, BigDecimal maxHourlyCost) {
		public Target {
			if (gpuModel == null || !gpuModel.matches("[A-Za-z0-9_]+") || gpuMemoryBytes < 1)
				throw new IllegalArgumentException("Incomplete Vast GPU capabilities");
			new OrchestratorTaskSpecification.Resources("vast", cpus, memory, gpuModel + ":1", null, false, null,
					region, instanceType, diskSize, maxHourlyCost);
		}
	}

	public OrchestratorTaskSpecification project(RunDefinition definition, RuntimeMaterials materials, Target target) {
		var request = definition.value().path("targetRequest");
		if (!request.path("targetClass").asText().equals("cloud-on-demand")
				|| !request.path("purchaseMode").asText().equals("on-demand")
				|| !request.path("target").asText().equals("vast") || request.path("gpuCount").asInt() != 1
				|| !request.path("gpuModel").asText().equals(target.gpuModel())
				|| request.path("minimumGpuMemoryBytes").asLong() > target.gpuMemoryBytes())
			throw new IllegalArgumentException("Unsupported Vast on-demand capabilities or pinned target");
		String command = "unset SKYPILOT_DOCKER_USERNAME SKYPILOT_DOCKER_PASSWORD SKYPILOT_DOCKER_SERVER\n"
				+ RuntimeDelivery.command(definition, materials, "cuda");
		var resources = new OrchestratorTaskSpecification.Resources("vast", target.cpus(), target.memory(),
				target.gpuModel() + ":1", "docker:" + materials.image(), false,
				new OrchestratorTaskSpecification.JobRecovery(0, List.of(75)), target.region(), target.instanceType(),
				target.diskSize(), target.maxHourlyCost());
		return new OrchestratorTaskSpecification("skywright-" + materials.runId(), null, command, List.of(resources),
				Map.of());
	}

}
