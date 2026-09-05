package de.zorro909.skywright.backend.credential;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads only explicitly registered revisions; never caches or persists secret material.
 */
public final class VaultBindings {

	public enum Status {

		READY, MISSING, INVALID, EXPIRED, UNAVAILABLE

	}

	public record Readiness(UUID bindingId, long revision, String role, Status status, Instant checkedAt) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final URI endpoint;

	private final String mount;

	private final Path tokenFile;

	private final List<CredentialBinding> bindings;

	private final Clock clock;

	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(3))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	public VaultBindings(URI endpoint, String mount, Path tokenFile, List<CredentialBinding> bindings, Clock clock) {
		if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
				|| endpoint.getQuery() != null || endpoint.getFragment() != null
				|| !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
				|| !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme())
						&& List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())))
				|| mount == null || !mount.matches("[a-zA-Z0-9_-]+") || tokenFile == null) {
			throw new IllegalArgumentException("Invalid Vault connection configuration");
		}
		for (int i = 0; i < bindings.size(); i++) {
			for (int j = i + 1; j < bindings.size(); j++) {
				var a = bindings.get(i);
				var b = bindings.get(j);
				if (a.id().equals(b.id()) || a.path().equals(b.path())
						|| (a.resource().equals(b.resource()) && a.identity().equals(b.identity()))) {
					throw new IllegalArgumentException(
							"Credential Bindings require distinct IDs, paths and resource identities");
				}
			}
		}
		this.endpoint = endpoint;
		this.mount = mount;
		this.tokenFile = tokenFile;
		this.bindings = List.copyOf(bindings);
		this.clock = clock;
	}

	public List<CredentialBinding> definitions() {
		return this.bindings;
	}

	public Readiness readiness(UUID id, long revision, String role) {
		return new Readiness(id, revision, role, resolve(id, revision, role, secret -> Boolean.TRUE).status(),
				this.clock.instant());
	}

	/**
	 * A consumer must not retain, log or serialize the supplied secret or its derived
	 * authorization.
	 */
	public <T> Resolution<T> resolve(UUID id, long revision, String role, Function<JsonNode, T> consumer) {
		var binding = this.bindings.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
		if (binding == null) {
			return new Resolution<>(Status.MISSING, Optional.empty());
		}
		if (binding.revision() != revision || !binding.role().equals(role)) {
			return new Resolution<>(Status.INVALID, Optional.empty());
		}
		if (binding.validatedAt().isAfter(this.clock.instant())) {
			return new Resolution<>(Status.INVALID, Optional.empty());
		}
		if (binding.validUntil() != null && !binding.validUntil().isAfter(this.clock.instant())) {
			return new Resolution<>(Status.EXPIRED, Optional.empty());
		}
		JsonNode secret;
		try {
			var request = HttpRequest
				.newBuilder(
						this.endpoint.resolve("/v1/" + this.mount + "/data/" + binding.path() + "?version=" + revision))
				.timeout(Duration.ofSeconds(5))
				.header("X-Vault-Token", Files.readString(this.tokenFile).strip())
				.GET()
				.build();
			var response = this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() == 404) {
				return new Resolution<>(Status.MISSING, Optional.empty());
			}
			if (response.statusCode() == 403) {
				return new Resolution<>(Status.INVALID, Optional.empty());
			}
			if (response.statusCode() != 200) {
				return new Resolution<>(Status.UNAVAILABLE, Optional.empty());
			}
			byte[] bytes = response.body();
			if (bytes.length > 1024 * 1024) {
				return new Resolution<>(Status.INVALID, Optional.empty());
			}
			var data = JSON.readTree(bytes).path("data");
			var metadata = data.path("metadata");
			if (metadata.path("version").asLong() != revision || metadata.path("destroyed").asBoolean()
					|| !metadata.path("deletion_time").asText("").isEmpty()) {
				return new Resolution<>(Status.INVALID, Optional.empty());
			}
			secret = data.path("data");
			if (!validShape(binding, secret)) {
				return new Resolution<>(Status.INVALID, Optional.empty());
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new Resolution<>(Status.UNAVAILABLE, Optional.empty());
		}
		catch (IOException exception) {
			return new Resolution<>(Status.UNAVAILABLE, Optional.empty());
		}
		catch (RuntimeException exception) {
			return new Resolution<>(Status.INVALID, Optional.empty());
		}
		if (binding.validUntil() != null && !binding.validUntil().isAfter(this.clock.instant())) {
			return new Resolution<>(Status.EXPIRED, Optional.empty());
		}
		// Do not attach provider or parser exceptions, which can contain submitted
		// values.
		try {
			var value = consumer.apply(secret);
			if (binding.validUntil() != null && !binding.validUntil().isAfter(this.clock.instant())) {
				return new Resolution<>(Status.EXPIRED, Optional.empty());
			}
			return new Resolution<>(Status.READY, Optional.ofNullable(value));
		}
		catch (RuntimeException exception) {
			return new Resolution<>(Status.INVALID, Optional.empty());
		}
	}

	private static boolean validShape(CredentialBinding binding, JsonNode value) {
		var kind = binding.kind();
		var fields = switch (kind) {
			case S3 -> List.of("accessKeyId", "secretAccessKey");
			case GHCR -> List.of("username", "token");
			case KUBERNETES -> List.of("kubeconfig");
			case SKYPILOT -> List.of("token");
		};
		boolean session = kind == CredentialBinding.Kind.S3 && value.has("sessionToken");
		if (!value.isObject() || value.size() != fields.size() + (session ? 1 : 0) || !fields.stream()
			.allMatch(field -> value.path(field).isString() && !value.path(field).asText().isBlank())) {
			return false;
		}
		if (session && (binding.nonExpiring() || !value.path("sessionToken").isString()
				|| value.path("sessionToken").asText().isBlank())) {
			return false;
		}
		if (kind == CredentialBinding.Kind.KUBERNETES) {
			var config = JSON.readTree(value.path("kubeconfig").asText());
			if (!"v1".equals(config.path("apiVersion").asText()) || !"Config".equals(config.path("kind").asText())
					|| config.path("clusters").size() != 1 || config.path("users").size() != 1
					|| config.path("contexts").size() != 1) {
				return false;
			}
			var user = config.path("users").get(0).path("user");
			var cluster = config.path("clusters").get(0).path("cluster");
			var context = config.path("contexts").get(0);
			return user.size() == 1 && user.path("token").isString() && !user.path("token").asText().isBlank()
					&& cluster.size() == 2 && cluster.path("server").asText().equals(binding.resource())
					&& cluster.path("certificate-authority-data").isString()
					&& !cluster.path("certificate-authority-data").asText().isBlank()
					&& !cluster.path("insecure-skip-tls-verify").asBoolean()
					&& config.path("current-context").asText().equals(context.path("name").asText())
					&& context.path("context")
						.path("user")
						.asText()
						.equals(config.path("users").get(0).path("name").asText())
					&& context.path("context")
						.path("cluster")
						.asText()
						.equals(config.path("clusters").get(0).path("name").asText());
		}
		return true;
	}

	/** Intentionally has no value-bearing toString or bean getters. */
	public static final class Resolution<T> {

		private final Status status;

		private final Optional<T> value;

		Resolution(Status status, Optional<T> value) {
			this.status = status;
			this.value = value;
		}

		public Status status() {
			return this.status;
		}

		public Optional<T> value() {
			return this.value;
		}

		@Override
		public String toString() {
			return "Credential resolution: " + this.status;
		}

	}

}
