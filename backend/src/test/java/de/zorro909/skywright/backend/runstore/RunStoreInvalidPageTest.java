package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class RunStoreInvalidPageTest {

	@ParameterizedTest
	@ValueSource(strings = { "oversized", "missing-token", "repeated-token" })
	void invalidProviderPageProducesOneFailedMeasurement(String mode) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger requests = new AtomicInteger();
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			String content = "<Contents><Key>project/run/v1/artifacts/entry</Key><Size>8</Size></Contents>";
			String xml = "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>runs</Name>"
					+ (mode.equals("oversized") ? "<IsTruncated>false</IsTruncated>" + content + content
							: "<IsTruncated>true</IsTruncated>" + (mode.equals("repeated-token")
									? "<NextContinuationToken>same-token</NextContinuationToken>" : ""))
					+ "</ListBucketResult>";
			byte[] body = xml.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		try {
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
			var target = new ResolvedTargetStorage("test",
					URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "runs", Region.US_EAST_1, true,
					Map.of(), credentials, "project", "run", UUID.randomUUID(), 1);
			try (var objects = new S3RunStoreObjectStore(target,
					new RunStoreOperationControl(Duration.ofSeconds(5), () -> false))) {
				assertThatThrownBy(() -> objects.list("project/run/v1/artifacts/", 1,
						mode.equals("repeated-token") ? "same-token" : null))
					.hasMessageContaining("RUN_STORE_INVALID_PAGE");
				assertThat(requests).hasValue(1);
				var batch = objects.drainMeasurements();
				assertThat(batch.throughSequence()).isEqualTo(1);
				assertThat(batch.measurements()).singleElement().satisfies(measurement -> {
					assertThat(measurement.operation()).isEqualTo("ListObjectsV2");
					assertThat(measurement.bytes()).isZero();
					assertThat(measurement.succeeded()).isFalse();
				});
			}
		}
		finally {
			server.stop(0);
		}
	}

}
