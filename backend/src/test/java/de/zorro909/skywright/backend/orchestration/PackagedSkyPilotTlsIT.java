package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class PackagedSkyPilotTlsIT {

	@ParameterizedTest
	@CsvSource({ "false,false", "false,true", "true,false", "true,true" })
	@Timeout(180)
	void packagedSdkRejectsUntrustedAuthoritiesAndMismatchedHostnames(boolean matchingHostname, boolean dns,
			@TempDir Path temporary) throws Exception {
		var repository = Path.of(System.getProperty("repository.root"));
		var server = SkyPilotTlsFixture.create(temporary.resolve("server"), matchingHostname);
		var trust = matchingHostname ? SkyPilotTlsFixture.create(temporary.resolve("unrelated-authority"), true)
				: server;
		var mode = (matchingHostname ? "untrusted-authority" : "wrong-hostname") + (dns ? "-dns" : "-ip");
		try (var api = SkyPilotApiServerFixture.start();
				var proxy = new HeldSkyPilotProxy(api.endpoint(), server.context())) {
			var log = repository.resolve("backend/target/service-logs/" + mode + "-tls-qualification.log");
			var endpoint = dns ? URI.create("https://localhost:" + proxy.endpoint().getPort()) : proxy.endpoint();
			var builder = PackagedSkyPilotFixture.process("tls-rejected", endpoint);
			builder.environment().put("SKYPILOT_SERVICE_ACCOUNT_TOKEN", "synthetic-tls-qualification-token");
			trust.configureTrust(builder);
			var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
			try {
				assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("packaged TLS rejection exits").isTrue();
				assertThat(process.exitValue()).as(Files.readString(log)).isZero();
				var evidence = Files.readAllLines(log)
					.stream()
					.filter(line -> line.startsWith("{"))
					.reduce((earlier, later) -> later)
					.orElseThrow();
				var result = JsonMapper.builder().build().readTree(evidence);
				assertThat(result.required("sdk_tls_rejected").asBoolean()).isTrue();
				assertThat(result.required("probe_tls_rejected").asBoolean()).isTrue();
				assertThat(result.required("cause").asText()).isEqualTo("REACHABILITY");
				assertThat(proxy.requests()).as("TLS rejection prevents any HTTP request reaching the API").isZero();
			}
			finally {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}

}
