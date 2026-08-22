package de.zorro909.skywright.backend.orchestration;

import java.util.Map;
import java.util.Objects;

/** The finite SkyPilot task contract populated by Run projection in issue 55. */
public record OrchestratorTaskSpecification(String name, String setup, String run, Resources resources,
		Map<String, String> environment) {

	public OrchestratorTaskSpecification {
		requireText(name, "name");
		requireText(run, "run");
		Objects.requireNonNull(resources, "resources");
		environment = Map.copyOf(environment);
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	public record Resources(String infrastructure, String cpus, String memory, String imageId) {

		public Resources {
			requireText(infrastructure, "infrastructure");
			requireText(cpus, "cpus");
			requireText(memory, "memory");
		}

	}

}
