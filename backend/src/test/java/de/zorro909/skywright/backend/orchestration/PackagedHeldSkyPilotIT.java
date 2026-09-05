package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class PackagedHeldSkyPilotIT {

	@Test
	@Timeout(180)
	void packagedNativeSdkKeepsControlAndShutdownBoundedWithAnOutstandingStream() throws Exception {
		var repository = Path.of(System.getProperty("repository.root"));
		var output = repository.resolve("backend/target/service-logs/held-sdk-qualification.log");
		try (var api = SkyPilotApiServerFixture.start(); var proxy = new HeldSkyPilotProxy(api.endpoint())) {
			var builder = new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED",
					"--sun-misc-unsafe-memory-access=allow", "-Xss16m",
					"-Dgraalpy.external.directory=" + System.getProperty("graalpy.external.directory"),
					"-Dloader.main=de.zorro909.skywright.backend.orchestration.OrchestratorQualificationMain", "-cp",
					repository.resolve("backend/target/skywright-backend-0.1.0-SNAPSHOT.jar").toString(),
					"org.springframework.boot.loader.launch.PropertiesLauncher", "held");
			builder.directory(repository.toFile());
			builder.environment().put("SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT", proxy.endpoint().toString());
			builder.redirectErrorStream(true);
			var process = builder.start();
			var lines = new LinkedBlockingQueue<String>();
			var reader = Thread.ofPlatform().start(() -> {
				try (var stream = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
						var log = Files.newBufferedWriter(output)) {
					for (String line; (line = stream.readLine()) != null;) {
						log.write(line + "\n");
						log.flush();
						lines.add(line);
					}
				}
				catch (java.io.IOException failure) {
					lines.add("READER_FAILED " + failure.getMessage());
				}
				finally {
					lines.add("PROCESS_EXITED");
				}
			});
			try (var input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
				proxy.hold(awaitLine(lines, "HOLD ", Duration.ofSeconds(90)).substring(5));
				command(input, "complete");
				assertThat(proxy.awaitHeld(Duration.ofSeconds(10))).as("SDK is waiting at /api/stream").isTrue();
				command(input, "measure");
				awaitLine(lines, "HOLD_CONTROL", Duration.ofSeconds(10));
				proxy.holdControl();
				command(input, "control");
				assertThat(proxy.awaitControl(Duration.ofSeconds(10))).as("SDK control call is waiting on the wire")
					.isTrue();
				command(input, "measure_control");
				awaitLine(lines, "UNREACHABLE", Duration.ofSeconds(10));
				api.stop();
				command(input, "unreachable");
				var result = JsonMapper.builder().build().readTree(awaitLine(lines, "{", Duration.ofSeconds(12)));
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("packaged JVM exits with stream still held")
					.isTrue();
				assertThat(process.exitValue()).isZero();
				assertThat(result.required("cancellation_ms").asLong()).isLessThan(2000);
				assertThat(result.required("probe_ms").asLong()).isLessThan(2000);
				assertThat(result.required("admission_ms").asLong()).isLessThan(100);
				assertThat(result.required("control_admission_ms").asLong()).isLessThan(100);
				assertThat(result.required("shutdown_ms").asLong()).isLessThan(5000);
				System.out.println("Packaged SDK evidence: " + result);
			}
			finally {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
				reader.join(5000);
			}
		}
	}

	private static void command(OutputStreamWriter input, String command) throws Exception {
		input.write(command + "\n");
		input.flush();
	}

	private static String awaitLine(LinkedBlockingQueue<String> lines, String prefix, Duration timeout)
			throws Exception {
		var deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			var line = lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertThat(line).as("packaged qualification output: " + prefix).isNotNull().isNotEqualTo("PROCESS_EXITED");
			if (line.startsWith(prefix)) {
				return line;
			}
		}
	}

}
