package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DeploymentConfigurationIT {

	@TempDir
	Path temporaryDirectory;

	@Test
	void commandLineConfigurationOverridesSystemEnvironmentAndFileSources() throws Exception {
		var configurationFile = temporaryDirectory.resolve("application.properties");
		Files.writeString(configurationFile, "skywright.deployment.environment=invalid_file!\n");
		var port = BackendProcess.availablePort();
		try (var backend = BackendProcess.start(Map.of("SKYWRIGHT_DEPLOYMENT_ENVIRONMENT", "invalid_environment!"),
				List.of("-Dskywright.deployment.environment=invalid_system!"),
				"--spring.config.additional-location=" + configurationFile.toUri(), "--server.port=" + port,
				"--skywright.deployment.environment=acceptance")) {
			BackendProcess.awaitReadiness(port, Duration.ofSeconds(20));

			assertThat(backend.isAlive()).isTrue();
			assertThat(backend.output()).contains("Started SkywrightBackendApplication");
		}
	}

	@Test
	void executableContainsGeneratedDeploymentConfigurationMetadata() throws Exception {
		try (var executable = new JarFile(System.getProperty("backend.executable"))) {
			var metadata = executable.getJarEntry("META-INF/spring-configuration-metadata.json");

			assertThat(metadata).isNotNull();
			assertThat(new String(executable.getInputStream(metadata).readAllBytes(), StandardCharsets.UTF_8))
				.contains("skywright.deployment.environment");
		}
	}

	@Test
	void unrelatedConfigurationFailuresUseTheirOwnDiagnostics() throws Exception {
		try (var backend = BackendProcess.start("--skywright.deployment.environment=test",
				"--server.port=not-a-port")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("server.port").doesNotContain("Skywright deployment setting");
		}
	}

	@Test
	void missingRequiredDeploymentConfigurationStopsStartupBeforeReadiness() throws Exception {
		try (var backend = BackendProcess.start("--server.port=0")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("skywright.deployment.environment")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void invalidDeploymentConfigurationIsDiagnosedWithoutEchoingItsValue() throws Exception {
		var sensitiveValue = "production-private-token!";
		try (var backend = BackendProcess.start("--debug", "--server.port=0",
				"--skywright.deployment.environment=" + sensitiveValue)) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output())
				.contains("skywright.deployment.environment", "must be a lowercase deployment identifier")
				.doesNotContain(sensitiveValue)
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void unknownDeploymentConfigurationIsRejectedWithoutEchoingItsValue() throws Exception {
		var sensitiveValue = "unknown-private-token";
		try (var backend = BackendProcess.start("--server.port=0", "--skywright.deployment.environment=test",
				"--skywright.deployment.unexpected=" + sensitiveValue)) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output())
				.contains("Unknown Skywright deployment setting", "skywright.deployment.unexpected")
				.doesNotContain(sensitiveValue)
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

}
