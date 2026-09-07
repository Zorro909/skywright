package de.zorro909.skywright.backend.runsubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class StopProjectionTimeoutTest {

	@Test
	void bodyTricklingAfterHeadersStillTimesOutAndReleasesTheClient() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			server.setExecutor(executor);
			server.createContext("/", exchange -> {
				try {
					exchange.sendResponseHeaders(200, 1000);
					for (int i = 0; i < 1000; i++) {
						exchange.getResponseBody().write('x');
						exchange.getResponseBody().flush();
						Thread.sleep(20);
					}
				}
				catch (Exception stopped) {
					/* Client deadline aborts the response. */ }
				finally {
					exchange.close();
				}
			});
			server.start();
			try (var client = S3AsyncClient.builder()
				.endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
				.region(Region.US_EAST_1)
				.forcePathStyle(true)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
				.overrideConfiguration(ClientOverrideConfiguration.builder()
					.retryStrategy(b -> b.maxAttempts(1))
					.apiCallTimeout(Duration.ofMillis(500))
					.apiCallAttemptTimeout(Duration.ofMillis(500))
					.build())
				.build()) {
				long began = System.nanoTime();
				assertThatThrownBy(() -> client
					.getObject(b -> b.bucket("fixture").key("control"),
							new de.zorro909.skywright.backend.runstore.BoundedS3Body(1000))
					.join()).isInstanceOf(CompletionException.class);
				assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
			}
			finally {
				server.stop(0);
				executor.shutdownNow();
			}
		}
	}

	@Test
	void excessiveBodyFailsBeforeBufferingAndCancelsTheSubscription() {
		var body = new de.zorro909.skywright.backend.runstore.BoundedS3Body(4);
		var result = body.prepare();
		body.onResponse(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(4L).build());
		var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
		body.onStream(software.amazon.awssdk.core.async.SdkPublisher
			.adapt(subscriber -> subscriber.onSubscribe(new org.reactivestreams.Subscription() {
				public void request(long count) {
					subscriber.onNext(java.nio.ByteBuffer.wrap(new byte[5]));
				}

				public void cancel() {
					cancelled.set(true);
				}
			})));
		assertThat(result).isCompletedExceptionally();
		assertThat(cancelled).isTrue();
	}

}
