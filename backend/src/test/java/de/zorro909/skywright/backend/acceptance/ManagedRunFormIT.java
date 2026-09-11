package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ManagedRunFormIT {

	private static java.net.URI slowVaultEndpoint;

	private static java.nio.file.Path slowVaultToken;

	@Test
	void unrelatedSlowCredentialsDoNotDelayInstalledWorkloadReadiness() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		slowVaultToken = java.nio.file.Files.createTempFile("managed-form-token-", ".txt");
		java.nio.file.Files.writeString(slowVaultToken, "fixture-token");
		server.createContext("/v1/", exchange -> {
			try {
				Thread.sleep(3000);
				byte[] body = "{\"data\":{\"metadata\":{\"version\":1},\"data\":{\"username\":\"reader\",\"token\":\"fixture-value\"}}}"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			finally {
				exchange.close();
			}
		});
		server.start();
		slowVaultEndpoint = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		try (var backend = BackendFixture.startWith(SlowVault.class, "managed-form", "slow-form-vault")) {
			long started = System.nanoTime();
			var response = backend.get("/api/v1/managed-run-form");
			assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
				.isLessThan(java.time.Duration.ofSeconds(5));
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("PROJECT_VERSION_UNAVAILABLE").doesNotContain("PREFLIGHT_TIMEOUT");
		}
		finally {
			server.stop(0);
			java.nio.file.Files.deleteIfExists(slowVaultToken);
		}
	}

	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@org.springframework.context.annotation.Profile("slow-form-vault")
	@org.springframework.context.annotation.Import(Installed.class)
	static class SlowVault {

		@org.springframework.context.annotation.Bean
		de.zorro909.skywright.backend.credential.VaultBindings vault() {
			var bindings = java.util.stream.IntStream.range(0, 6)
				.mapToObj(i -> new de.zorro909.skywright.backend.credential.CredentialBinding(
						java.util.UUID.randomUUID(), 1, "registry/" + i,
						de.zorro909.skywright.backend.credential.CredentialBinding.Kind.GHCR,
						"ghcr.io/example/project-" + i, "backend-resolver", "reader-" + i, "example/project-" + i,
						"read-only", java.time.Instant.parse("2026-01-01T00:00:00Z"), null, true))
				.toList();
			return new de.zorro909.skywright.backend.credential.VaultBindings(slowVaultEndpoint, "skywright",
					slowVaultToken, bindings, java.time.Clock.systemUTC());
		}

	}

	@Test
	void configuredWorkloadAndTargetRemainUnavailableUntilTheirCatalogPrerequisitesExist() throws Exception {
		try (var backend = BackendFixture.startWith(Installed.class, "managed-form")) {
			var response = backend.get("/api/v1/managed-run-form");
			assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
			var form = JsonMapper.builder().build().readTree(response.body());
			assertThat(form.at("/workloads/0/id").asText()).isEqualTo("demonstration");
			assertThat(form.at("/workloads/0/displayName").asText()).isEqualTo("Short CIFAR-10 proof");
			assertThat(form.at("/targets/0/id").asText()).isEqualTo("local-amd");
			assertThat(form.at("/targets/0/gpuCount").asInt()).isEqualTo(2);
			assertThat(form.path("ready").asBoolean()).isFalse();
			assertThat(form.path("checks").toString()).contains("PROJECT_VERSION_UNAVAILABLE", "DATASET_UNAVAILABLE");
		}
	}

	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@org.springframework.context.annotation.Profile("managed-form")
	static class Installed {

		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		de.zorro909.skywright.backend.runsubmission.DemonstrationSettings demonstration() {
			return new de.zorro909.skywright.backend.runsubmission.DemonstrationSettings(java.util.UUID.randomUUID(),
					"sha256:" + "a".repeat(64), java.util.UUID.randomUUID(), "Short CIFAR-10 proof",
					java.util.Map.of());
		}

		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		de.zorro909.skywright.backend.runsubmission.LocalRunTargetSettings target() {
			return new de.zorro909.skywright.backend.runsubmission.LocalRunTargetSettings("local-amd", "kind-skywright",
					"rx7800xt", 2, 17163091968L, "4", "8", true);
		}

	}

	@Test
	void anUnconfiguredInstallationExplainsWhyNoWorkloadCanStart() throws Exception {
		try (var backend = BackendFixture.start()) {
			var response = backend.get("/api/v1/managed-run-form");
			assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
			var form = JsonMapper.builder().build().readTree(response.body());
			assertThat(form.path("ready").asBoolean()).isFalse();
			assertThat(form.path("workloads").size()).isZero();
			assertThat(form.path("checks").toString()).contains("WORKLOAD_NOT_INSTALLED");
		}
	}

}
