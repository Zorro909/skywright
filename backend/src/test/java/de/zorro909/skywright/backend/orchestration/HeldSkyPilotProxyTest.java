package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class HeldSkyPilotProxyTest {

	@Test
	void cancellationEvidenceIncludesPendingAndFailedUpstreamRequests() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/slow", exchange -> {
			try (exchange) {
				entered.countDown();
				if (release.await(5, TimeUnit.SECONDS)) {
					exchange.sendResponseHeaders(200, -1);
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		});
		upstream.start();
		var endpoint = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort());
		try (var proxy = new HeldSkyPilotProxy(endpoint); var client = HttpClient.newHttpClient()) {
			proxy.beginCancellation();
			var response = client.sendAsync(
					HttpRequest.newBuilder(proxy.endpoint().resolve("/slow")).timeout(Duration.ofSeconds(8)).build(),
					HttpResponse.BodyHandlers.discarding());
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(proxy.finishCancellation()).singleElement().satisfies(request -> {
					assertThat(request.path()).isEqualTo("/slow");
					assertThat(request.state()).isEqualTo(HeldSkyPilotProxy.RequestState.PENDING);
					assertThat(request.status()).isZero();
					assertThat(request.upstreamMillis()).isNotNegative();
				});
			}
			finally {
				release.countDown();
			}
			assertThat(response.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
			upstream.stop(0);
			proxy.beginCancellation();
			var failed = client.send(HttpRequest.newBuilder(proxy.endpoint().resolve("/unavailable"))
				.timeout(Duration.ofSeconds(5))
				.build(), HttpResponse.BodyHandlers.discarding());
			assertThat(failed.statusCode()).isEqualTo(503);
			assertThat(proxy.finishCancellation()).singleElement().satisfies(request -> {
				assertThat(request.path()).isEqualTo("/unavailable");
				assertThat(request.state()).isEqualTo(HeldSkyPilotProxy.RequestState.FAILED);
				assertThat(request.status()).isZero();
			});
		}
		finally {
			release.countDown();
			upstream.stop(0);
		}
	}

}
