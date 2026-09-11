package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("real-service")
final class PackagedSkyPilotResourcesIT {

	@TempDir
	Path temporary;

	@Test
	@Timeout(240)
	void repeatedSdkOperationsDoNotOpenTelemetrySockets() throws Exception {
		var tls = SkyPilotTlsFixture.create(this.temporary.resolve("tls"), true);
		var telemetry = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		telemetry.setHttpsConfigurator(new HttpsConfigurator(tls.context()));
		var requests = new AtomicInteger();
		telemetry.createContext("/", exchange -> {
			try (exchange) {
				requests.incrementAndGet();
				exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
				exchange.sendResponseHeaders(204, -1);
			}
		});
		telemetry.start();
		try (var server = SkyPilotApiServerFixture.start()) {
			var builder = PackagedSkyPilotFixture.process("sdk-resources", server.endpoint());
			// A caller trying to enable telemetry must not bypass the bridge's lifecycle
			// policy.
			builder.environment().put("SKYPILOT_DISABLE_USAGE_COLLECTION", "false");
			builder.environment()
				.put("SKYPILOT_USAGE_LOG_URL", "https://127.0.0.1:" + telemetry.getAddress().getPort());
			builder.environment().put("JAVA_TOOL_OPTIONS", "-Xmx2g -XX:ActiveProcessorCount=2");
			tls.configureTrust(builder);
			var output = this.temporary.resolve("resource-workload.log");
			var child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try {
				assertThat(child.waitFor(180, TimeUnit.SECONDS)).as("bounded resource workload").isTrue();
				String logs = Files.readString(output);
				assertThat(child.exitValue()).as(logs + "\ntelemetry requests=" + requests.get()).isZero();
				assertThat(logs).contains("\"sdk_resources_bounded\":true", "\"phase\":\"idle\"");
				assertThat(requests).hasValue(0);
			}
			finally {
				child.destroyForcibly();
				child.waitFor(10, TimeUnit.SECONDS);
			}
		}
		finally {
			telemetry.stop(0);
		}
	}

}
