package de.zorro909.skywright.backend.projectversion;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Bounded registry requests and credential-free signed blob delivery. */
final class RegistryHttp {

	private final HttpClient client;

	private final URI endpoint;

	RegistryHttp(HttpClient client, URI endpoint) {
		this.client = client;
		this.endpoint = endpoint;
	}

	Optional<String> authorize(String repository, Optional<String> registered) {
		if (registered.isPresent()) {
			return registered;
		}
		String scope = URLEncoder.encode("repository:" + repository.substring("ghcr.io/".length()) + ":pull",
				StandardCharsets.UTF_8);
		var response = send(HttpRequest.newBuilder(this.endpoint.resolve("/token?service=ghcr.io&scope=" + scope))
			.timeout(Duration.ofSeconds(5))
			.GET()
			.build());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("registry token unavailable");
		}
		String token;
		try {
			var document = JsonMapper.builder().build().readTree(response.body());
			token = document.path("token").asText(document.path("access_token").asText(""));
		}
		catch (RuntimeException malformed) {
			throw new IllegalStateException("registry token invalid");
		}
		if (token.isBlank() || token.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalStateException("registry token invalid");
		}
		return Optional.of("Bearer " + token);
	}

	HttpResponse<String> send(HttpRequest request) {
		try {
			return this.client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException error) {
			throw new IllegalStateException("registry unavailable");
		}
		catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("registry request interrupted");
		}
	}

	HttpResponse<String> blob(HttpRequest request) {
		var response = send(request);
		for (int redirects = 0; redirects < 3
				&& (response.statusCode() == 302 || response.statusCode() == 307); redirects++) {
			URI location;
			try {
				location = response.uri()
					.resolve(response.headers()
						.firstValue("Location")
						.orElseThrow(() -> new IllegalStateException("registry blob redirect missing")));
			}
			catch (IllegalArgumentException malformed) {
				throw new IllegalStateException("registry blob redirect invalid");
			}
			boolean sameEndpoint = this.endpoint.getScheme().equals(location.getScheme())
					&& this.endpoint.getAuthority().equals(location.getAuthority());
			boolean githubCdn = "https".equals(location.getScheme())
					&& "pkg-containers.githubusercontent.com".equals(location.getHost()) && location.getPort() == -1;
			if ((!sameEndpoint && !githubCdn) || location.getUserInfo() != null || location.getFragment() != null) {
				throw new IllegalStateException("registry blob redirect rejected");
			}
			// Signed CDN URLs authorize only their object. Never forward registry
			// credentials.
			response = send(HttpRequest.newBuilder(location).timeout(Duration.ofSeconds(10)).GET().build());
		}
		return response;
	}

}
