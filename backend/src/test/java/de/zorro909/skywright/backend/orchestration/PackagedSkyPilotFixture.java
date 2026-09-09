package de.zorro909.skywright.backend.orchestration;

import java.net.URI;
import java.nio.file.Path;

/** The production runtime settings shared by packaged SDK qualifications. */
final class PackagedSkyPilotFixture {

	private PackagedSkyPilotFixture() {
	}

	static ProcessBuilder process(String mode, URI endpoint) {
		var builder = new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED",
				"--sun-misc-unsafe-memory-access=allow", "-Xss16m",
				"-Dgraalpy.external.directory=" + System.getProperty("graalpy.external.directory"),
				"-Dloader.main=de.zorro909.skywright.backend.orchestration.OrchestratorQualificationMain", "-cp",
				System.getProperty("backend.executable"), "org.springframework.boot.loader.launch.PropertiesLauncher",
				mode);
		builder.directory(Path.of(System.getProperty("repository.root")).toFile());
		builder.environment().put("SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT", endpoint.toString());
		return builder;
	}

}
