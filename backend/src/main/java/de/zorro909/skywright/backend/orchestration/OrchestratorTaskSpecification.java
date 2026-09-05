package de.zorro909.skywright.backend.orchestration;

import java.util.List;
import java.util.Map;

/** The finite SkyPilot task contract populated by Run projection in issue 55. */
public record OrchestratorTaskSpecification(String name, String setup, String run, List<Resources> resources,
		Map<String, String> environment, String runtimePullSecret) {

	public OrchestratorTaskSpecification(String name, String setup, String run, List<Resources> resources,
			Map<String, String> environment) {
		this(name, setup, run, resources, environment, null);
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

		if (runtimePullSecret != null && (!runtimePullSecret
			.matches("skywright-pull-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
				|| resources.stream().anyMatch(r -> !r.infrastructure().equals("kubernetes")))) {
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
							"KUBECONFIG", "DOCKER_CONFIG")
					.contains(key);
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	public record Resources(String infrastructure, String cpus, String memory, String accelerators, String imageId,
			boolean useSpot) {

		public Resources {
			requireText(infrastructure, "infrastructure");
			requireText(cpus, "cpus");
			requireText(memory, "memory");
		}

	}

}
