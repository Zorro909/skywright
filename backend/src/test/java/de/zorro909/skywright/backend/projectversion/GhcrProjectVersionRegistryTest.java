package de.zorro909.skywright.backend.projectversion;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GhcrProjectVersionRegistryTest {

	@Test
	void usesHeadForEnumerationAndDigestChecksAndPullsArtifactContentByBlobDigest() throws Exception {
		String label = "1".repeat(40) + "-github-81-1";
		String manifestDigest = "sha256:" + "9".repeat(64);
		String blobDigest = "sha256:" + "8".repeat(64);
		List<String> requests = new ArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			server.createContext("/", exchange -> respond(exchange, requests, label, manifestDigest, blobDigest));
			server.start();
			URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			var registry = new GhcrProjectVersionRegistry(HttpClient.newHttpClient(), endpoint,
					repository -> Optional.of("Bearer test-token"));

			assertThat(registry.listVersions("ghcr.io/example/project"))
				.containsExactly(new ProjectVersionReference(label, manifestDigest));
			assertThat(registry.pullArtifact("ghcr.io/example/project", manifestDigest))
				.contains(new RegistryArtifact(manifestDigest, "artifact-content"));
			assertThat(registry.imageAvailable("ghcr.io/example/project", manifestDigest)).isTrue();
			assertThat(registry.imageAvailable("ghcr.io/example/project", "sha256:" + "7".repeat(64))).isFalse();
		}
		finally {
			server.stop(0);
		}

		assertThat(requests).contains("GET /v2/example/project/tags/list Bearer test-token",
				"HEAD /v2/example/project/manifests/" + label + " Bearer test-token",
				"GET /v2/example/project/manifests/" + manifestDigest + " Bearer test-token",
				"GET /v2/example/project/blobs/" + blobDigest + " Bearer test-token");
	}

	private static void respond(HttpExchange exchange, List<String> requests, String label, String manifestDigest,
			String blobDigest) throws java.io.IOException {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + authorization);
		String path = exchange.getRequestURI().getPath();
		if (path.endsWith("/tags/list")) {
			body(exchange, 200, "{\"tags\":[\"" + label + "\",\"latest\"]}");
		}
		else if (path.endsWith("/manifests/" + label)) {
			exchange.getResponseHeaders().add("Docker-Content-Digest", manifestDigest);
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		}
		else if (path.endsWith("/manifests/" + manifestDigest) && "GET".equals(exchange.getRequestMethod())) {
			exchange.getResponseHeaders().add("Docker-Content-Digest", manifestDigest);
			body(exchange, 200, "{\"layers\":[{\"digest\":\"" + blobDigest + "\"}]}");
		}
		else if (path.endsWith("/manifests/" + manifestDigest)) {
			exchange.getResponseHeaders().add("Docker-Content-Digest", manifestDigest);
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		}
		else if (path.endsWith("/blobs/" + blobDigest)) {
			body(exchange, 200, "artifact-content");
		}
		else {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		}
	}

	private static void body(HttpExchange exchange, int status, String value) throws java.io.IOException {
		byte[] body = value.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

}
