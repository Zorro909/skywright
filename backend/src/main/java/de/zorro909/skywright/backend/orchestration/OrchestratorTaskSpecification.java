package de.zorro909.skywright.backend.orchestration;

import java.util.List;
import java.util.Map;

/** The finite SkyPilot task contract populated by Run projection in issue 55. */
public record OrchestratorTaskSpecification(String name, String setup, String run, List<Resources> resources,
		Map<String, String> environment, String runtimePullSecret, String runtimePullNamespace) {

	public OrchestratorTaskSpecification(String name, String setup, String run, List<Resources> resources,
			Map<String, String> environment) {
		this(name, setup, run, resources, environment, null, null);
	}

	public OrchestratorTaskSpecification(String name, String setup, String run, List<Resources> resources,
			Map<String, String> environment, String runtimePullSecret) {
		this(name, setup, run, resources, environment, runtimePullSecret, null);
	}

	public OrchestratorTaskSpecification {
		requireText(name, "name");
		requireText(run, "run");
		resources = List.copyOf(resources);
		if (resources.isEmpty()) {
			throw new IllegalArgumentException("resources must not be empty");
		}
		environment = Map.copyOf(environment);
		if (environment.keySet().stream().anyMatch(OrchestratorTaskSpecification::credentialVariable)) {
			throw new IllegalArgumentException("Credentials must not enter the task environment");
		}

		if (runtimePullNamespace != null
				&& (runtimePullSecret == null || !runtimePullNamespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")))
			throw new IllegalArgumentException("Invalid runtime pull namespace");

		if (runtimePullSecret != null && (!runtimePullSecret
			.matches("skywright-pull-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
				|| resources.stream()
					.anyMatch(r -> !(r.infrastructure().equals("kubernetes")
							|| r.infrastructure().startsWith("kubernetes/"))))) {
			throw new IllegalArgumentException("Runtime pull secret requires the local Kubernetes target");
		}
	}

	private static boolean credentialVariable(String key) {
		var storageSecret = (key.startsWith("SKYWRIGHT_DATASET_") || key.startsWith("SKYWRIGHT_RUN_STORE_"))
				&& !key.endsWith("_CREDENTIAL_FILE");
		return storageSecret || key.startsWith("VAULT_") || key.startsWith("SKYPILOT_SERVICE_ACCOUNT_")
				|| key.startsWith("SKYPILOT_DOCKER_")
				|| java.util.Set
					.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE",
							"AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE", "AWS_WEB_IDENTITY_TOKEN_FILE",
							"KUBECONFIG", "DOCKER_CONFIG", "VAST_API_KEY", "VASTAI_API_KEY")
					.contains(key);
	}

	/** Vast receives its image-pull authentication in SkyPilot's secret channel. */
	public boolean usesRegistrySecretChannel() {
		return resources.size() == 1 && resources.getFirst().infrastructure().equals("vast");
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	public record Resources(String infrastructure, String cpus, String memory, String accelerators, String imageId,
			boolean useSpot, JobRecovery jobRecovery, String region, String instanceType, Integer diskSize,
			java.math.BigDecimal maxHourlyCost, java.math.BigDecimal maxBidHourlyCost) {

		public Resources(String infrastructure, String cpus, String memory, String accelerators, String imageId,
				boolean useSpot, JobRecovery jobRecovery, String region, String instanceType, Integer diskSize,
				java.math.BigDecimal maxHourlyCost) {
			this(infrastructure, cpus, memory, accelerators, imageId, useSpot, jobRecovery, region, instanceType,
					diskSize, maxHourlyCost, null);
		}

		public Resources(String infrastructure, String cpus, String memory, String accelerators, String imageId,
				boolean useSpot, JobRecovery jobRecovery) {
			this(infrastructure, cpus, memory, accelerators, imageId, useSpot, jobRecovery, null, null, null, null);
		}

		public Resources(String infrastructure, String cpus, String memory, String accelerators, String imageId,
				boolean useSpot) {
			this(infrastructure, cpus, memory, accelerators, imageId, useSpot, null);
		}

		public Resources {
			requireText(infrastructure, "infrastructure");
			requireText(cpus, "cpus");
			requireText(memory, "memory");
			if (region != null || instanceType != null || diskSize != null || maxHourlyCost != null
					|| maxBidHourlyCost != null || (infrastructure.equals("vast") && useSpot)) {
				if (!infrastructure.equals("vast") || region == null || !region.matches("[A-Z]{2}")
						|| instanceType == null || !instanceType.matches("[A-Za-z0-9_-]+") || diskSize == null
						|| diskSize < 1 || maxHourlyCost == null || maxHourlyCost.signum() <= 0
						|| maxHourlyCost.compareTo(new java.math.BigDecimal("0.15")) >= 0)
					throw new IllegalArgumentException("Invalid Vast resource constraints");
				if (useSpot != (maxBidHourlyCost != null)
						|| (maxBidHourlyCost != null && (maxBidHourlyCost.signum() <= 0
								|| maxBidHourlyCost.compareTo(new java.math.BigDecimal("0.15")) >= 0)))
					throw new IllegalArgumentException("Vast interruptible requires an explicit bounded bid");
			}
		}

	}

	/** Only a durably finalized interruption may request an application-code recovery. */
	public record JobRecovery(int maxRestartsOnErrors, List<Integer> recoverOnExitCodes) {

		public JobRecovery {
			recoverOnExitCodes = List.copyOf(recoverOnExitCodes);
			if (maxRestartsOnErrors != 0 || !recoverOnExitCodes.equals(List.of(75))) {
				throw new IllegalArgumentException("Only finalized interruption outcome 75 may request recovery");
			}
		}

	}

}
