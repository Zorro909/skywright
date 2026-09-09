package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
final class PackagedHeldSkyPilotIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest
	@CsvSource({ "false,false", "true,false", "false,true", "true,true" })
	@Timeout(180)
	void packagedNativeSdkKeepsControlAndShutdownBoundedWithAnOutstandingStream(boolean saturateControl, boolean tls,
			@TempDir Path temporary) throws Exception {
		assertThat(Runtime.getRuntime().availableProcessors())
			.as("timing qualification requires at least two available CPUs for the packaged JVM and real server")
			.isGreaterThanOrEqualTo(2);
		var repository = Path.of(System.getProperty("repository.root"));
		var mode = saturateControl ? "held-control" : "held";
		var output = repository
			.resolve("backend/target/service-logs/" + mode + (tls ? "-tls" : "") + "-sdk-qualification.log");
		var certificate = tls ? SkyPilotTlsFixture.create(temporary, true) : null;
		try (var api = SkyPilotApiServerFixture.start();
				var proxy = new HeldSkyPilotProxy(api.endpoint(), certificate == null ? null : certificate.context())) {
			var endpoint = tls && saturateControl ? URI.create("https://localhost:" + proxy.endpoint().getPort())
					: proxy.endpoint();
			var builder = PackagedSkyPilotFixture.process(mode, endpoint);
			if (certificate != null) {
				certificate.configureTrust(builder);
			}
			builder.redirectErrorStream(true);
			var process = builder.start();
			List<HeldSkyPilotProxy.RequestTiming> startupRequests = List.of();
			List<HeldSkyPilotProxy.RequestTiming> heldRequests = List.of();
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
				awaitLine(lines, "CANCEL_STARTUP", Duration.ofSeconds(120));
				proxy.beginCancellation();
				command(input, "startup");
				proxy.hold(awaitLine(lines, "HOLD ", Duration.ofSeconds(12)).substring(5));
				startupRequests = proxy.finishCancellation();
				command(input, "complete");
				assertThat(proxy.awaitHeld(Duration.ofSeconds(10))).as("SDK is waiting at /api/stream").isTrue();
				proxy.beginCancellation();
				command(input, "measure");
				awaitLine(lines, "CANCELLED", Duration.ofSeconds(10));
				heldRequests = proxy.finishCancellation();
				command(input, "probe");
				if (saturateControl) {
					awaitLine(lines, "HOLD_CONTROL", Duration.ofSeconds(10));
					proxy.holdControl();
					command(input, "control");
					assertThat(proxy.awaitControl(Duration.ofSeconds(10))).as("SDK control call is waiting on the wire")
						.isTrue();
					command(input, "measure_control");
				}
				awaitLine(lines, "UNREACHABLE", Duration.ofSeconds(10));
				api.stop();
				command(input, "unreachable");
				var result = JSON.readTree(awaitLine(lines, "{", Duration.ofSeconds(12)));
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("packaged JVM exits with stream still held")
					.isTrue();
				assertThat(process.exitValue()).isZero();
				assertThat(result.required("cancellation_ms").asLong()).isLessThan(2000);
				assertThat(result.required("probe_ms").asLong()).isLessThan(2000);
				assertThat(result.required("admission_ms").asLong()).isLessThan(100);
				assertThat(result.required("catalogue_admission_ms").asLong()).isLessThan(100);
				if (saturateControl) {
					assertThat(result.required("control_admission_ms").asLong()).isLessThan(100);
				}
				assertThat(result.required("shutdown_ms").asLong()).isLessThan(5000);
				assertThat(result.required("startup_cancellation").required("thread_cpu_ms").asLong()).isPositive();
				assertThat(result.required("held_cancellation").required("thread_cpu_ms").asLong()).isPositive();
				for (var requests : List.of(startupRequests, heldRequests)) {
					assertThat(requests).filteredOn(request -> request.path().equals("/jobs/cancel"))
						.singleElement()
						.satisfies(request -> {
							assertThat(request.method()).isEqualTo("POST");
							assertThat(request.state()).isEqualTo(HeldSkyPilotProxy.RequestState.COMPLETED);
							assertThat(request.status()).isEqualTo(200);
						});
				}
				System.out.println("Packaged SDK evidence: " + result);
			}
			finally {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
				reader.join(5000);
				Object exitCode = process.isAlive() ? "still-running" : process.exitValue();
				var wireEvidence = JSON.writeValueAsString(Map.of("startup", startupRequests, "held", heldRequests,
						"unfinished", proxy.finishCancellation(), "exit_code", exitCode));
				Files.writeString(output.resolveSibling(output.getFileName() + ".requests.json"), wireEvidence);
				System.out.println("Cancellation wire evidence: " + wireEvidence);
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
