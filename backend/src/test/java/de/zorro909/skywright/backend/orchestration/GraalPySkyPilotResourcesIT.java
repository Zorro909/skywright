package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Counts real OS sockets across the production native bridge, including failed reads. */
final class GraalPySkyPilotResourcesIT {

	@Test
	@Timeout(120)
	void healthFailuresTimeoutAndShutdownReleaseTheirSockets() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")));
		var status = new AtomicInteger(200);
		long initial = sample("initial");
		var held = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try (var executor = Executors.newCachedThreadPool()) {
			server.setExecutor(executor);
			server.createContext("/api/health", exchange -> {
				try (exchange) {
					int code = status.get();
					if (code == 0) {
						held.countDown();
						try {
							release.await(15, TimeUnit.SECONDS);
						}
						catch (InterruptedException failure) {
							Thread.currentThread().interrupt();
						}
						return;
					}
					byte[] body = (code == 201 ? "invalid-json" : "{\"version\":\"0.13.0\"}")
						.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(code, body.length);
					exchange.getResponseBody().write(body);
				}
			});
			server.start();
			try (var client = new GraalPySkyPilotClient(Path.of(System.getProperty("graalpy.external.directory")),
					URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
				client.probe();
				long baseline = sample("warm");
				for (int round = 0; round < 4; round++) {
					for (int code : new int[] { 200, 500, 401, 403, 201 }) {
						status.set(code);
						for (int request = 0; request < 10; request++) {
							if (code == 200) {
								client.probe();
							}
							else {
								assertThatThrownBy(client::probe).isInstanceOf(SkyPilotClientFailure.class);
							}
						}
					}
					assertThat(sample("round-" + round)).isLessThanOrEqualTo(baseline + 8);
				}
				Thread.sleep(1000);
				assertThat(sample("idle")).isLessThanOrEqualTo(baseline + 8);
				// A read timeout must release the client descriptor even while the peer
				// holds its end.
				status.set(0);
				assertThatThrownBy(client::probe).isInstanceOf(SkyPilotClientFailure.class);
				assertThat(sample("timeout")).isLessThanOrEqualTo(baseline + 8);
				var pending = executor.submit(() -> {
					assertThatThrownBy(client::probe).isInstanceOf(SkyPilotClientFailure.class);
				});
				assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
				client.close();
				pending.get(10, TimeUnit.SECONDS);
			}
			finally {
				release.countDown();
				server.stop(0);
			}
		}
		Thread.sleep(1000);
		assertThat(sample("closed")).isLessThanOrEqualTo(initial + 1);
	}

	private static long sample(String phase) throws Exception {
		long sockets;
		try (var files = Files.list(Path.of("/proc/self/fd"))) {
			sockets = files.filter(path -> {
				try {
					return Files.readSymbolicLink(path).toString().startsWith("socket:");
				}
				catch (java.io.IOException ignored) {
					return false;
				}
			}).count();
		}
		var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		var rss = Files.readAllLines(Path.of("/proc/self/status"))
			.stream()
			.filter(line -> line.startsWith("VmRSS:"))
			.findFirst()
			.orElseThrow();
		System.out.printf("bridge-resources phase=%s sockets=%d heapBytes=%d %s%n", phase, sockets, heap, rss);
		return sockets;
	}

}
