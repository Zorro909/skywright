package de.zorro909.skywright.backend.worker;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

class TransferObjectChecksumTest {

	@Test
	void registeredRequestPolicyAndManualFullObjectVerificationReachTheWire() throws Exception {
		byte[] expected = "the complete object".getBytes(StandardCharsets.UTF_8);
		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
		var actual = new AtomicReference<>(expected);
		var checksumMode = new AtomicReference<String>();
		var requestChecksum = new AtomicReference<String>();
		var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/bucket/key", exchange -> {
			if (exchange.getRequestMethod().equals("PUT")) {
				requestChecksum.set(exchange.getRequestHeaders().getFirst("x-amz-sdk-checksum-algorithm"));
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().set("ETag", "\"stored\"");
				exchange.sendResponseHeaders(200, -1);
			}
			else {
				checksumMode.set(exchange.getRequestHeaders().getFirst("x-amz-checksum-mode"));
				// Deliberately unsuitable evidence: the full digest must come from bytes.
				exchange.getResponseHeaders().set("x-amz-checksum-type", "COMPOSITE");
				exchange.getResponseHeaders()
					.set("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(new byte[32]));
				byte[] body = actual.get();
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		try {
			for (String policy : List.of("when-required", "when-supported")) {
				try (var client = TransferObjects.client(
						URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "us-east-1",
						StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")), true,
						false, TransferObjects.checksumCalculation(policy),
						ClientOverrideConfiguration.builder().build(), null)) {
					client.putObject(b -> b.bucket("bucket").key("key"), AsyncRequestBody.fromBytes(expected)).join();
					if (policy.equals("when-required"))
						assertThat(requestChecksum.get()).isNull();
					else
						assertThat(requestChecksum.get()).isNotBlank();
					actual.set(expected);
					TransferObjects.verify(client, "bucket", "key", expected.length, digest);
					assertThat(checksumMode.get()).isNull();
					actual.set(new byte[expected.length]);
					assertThatThrownBy(() -> TransferObjects.verify(client, "bucket", "key", expected.length, digest))
						.isInstanceOf(TransferObjects.IntegrityMismatch.class);
				}
			}
		}
		finally {
			server.stop(0);
		}
	}

}
