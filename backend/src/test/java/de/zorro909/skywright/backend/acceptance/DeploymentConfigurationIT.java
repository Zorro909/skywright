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
		try (var backend = BackendProcess.startWithDatabase(null,
				Map.of("SKYWRIGHT_DEPLOYMENT_ENVIRONMENT", "invalid_environment!"),
				List.of("-Dskywright.deployment.environment=invalid_system!"),
				"--spring.config.additional-location=" + configurationFile.toUri(), "--server.port=" + port,
				"--skywright.deployment.environment=acceptance", "--skywright.deployment.reporting-currency=EUR")) {
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
				.contains("skywright.deployment.environment", "skywright.deployment.reporting-currency");
		}
	}

	@Test
	void unrelatedConfigurationFailuresUseTheirOwnDiagnostics() throws Exception {
		try (var backend = BackendProcess.startWithDatabase("--skywright.deployment.environment=test",
				"--skywright.deployment.reporting-currency=EUR", "--server.port=not-a-port")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("server.port").doesNotContain("Skywright deployment setting");
		}
	}

	@Test
	void missingReportingCurrencyStopsStartup() throws Exception {
		try (var backend = BackendProcess.startWithDatabase("--server.port=0",
				"--skywright.deployment.environment=test")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("skywright.deployment.reportingCurrency")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void invalidReportingCurrencyStopsStartup() throws Exception {
		try (var backend = BackendProcess.startWithDatabase("--server.port=0",
				"--skywright.deployment.environment=test", "--skywright.deployment.reporting-currency=ZZZ")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("skywright.deployment.reporting-currency")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void reportingCurrencyWithoutAMinorUnitStopsStartup() throws Exception {
		try (var backend = BackendProcess.startWithDatabase("--server.port=0",
				"--skywright.deployment.environment=test", "--skywright.deployment.reporting-currency=XAU")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("reportingCurrencyMinorUnitDefined")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void missingRequiredDeploymentConfigurationStopsStartupBeforeReadiness() throws Exception {
		try (var backend = BackendProcess.startWithDatabase("--server.port=0")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("skywright.deployment.environment")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void invalidDeploymentConfigurationIsDiagnosedWithoutEchoingItsValue() throws Exception {
		var sensitiveValue = "production-private-token!";
		try (var backend = BackendProcess.startWithDatabase("--debug", "--server.port=0",
				"--skywright.deployment.environment=" + sensitiveValue,
				"--skywright.deployment.reporting-currency=EUR")) {
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
		try (var backend = BackendProcess.startWithDatabase("--server.port=0",
				"--skywright.deployment.environment=test", "--skywright.deployment.reporting-currency=EUR",
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
