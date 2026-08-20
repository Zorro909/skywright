package de.zorro909.skywright.backend.projectversion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OCI Distribution adapter for live pull-only GHCR discovery and digest resolution. */
public final class GhcrProjectVersionRegistry implements ProjectVersionRegistry {

	private static final String MANIFEST_ACCEPT = String.join(", ", "application/vnd.oci.image.manifest.v1+json",
			"application/vnd.oci.artifact.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json");

	private static final Pattern VERSION_LABEL = Pattern.compile("[0-9a-f]{40}-[A-Za-z0-9][A-Za-z0-9._-]{0,86}");

	private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient client;

	private final URI endpoint;

	private final RegistryAuthorization authorization;

	public GhcrProjectVersionRegistry() {
		this(HttpClient.newHttpClient(), URI.create("https://ghcr.io"), repository -> Optional.empty());
	}

	public GhcrProjectVersionRegistry(RegistryAuthorization authorization) {
		this(HttpClient.newHttpClient(), URI.create("https://ghcr.io"), authorization);
	}

	GhcrProjectVersionRegistry(HttpClient client, URI endpoint, RegistryAuthorization authorization) {
		this.client = client;
		this.endpoint = endpoint;
		this.authorization = authorization;
	}

	@Override
	public List<ProjectVersionReference> listVersions(String repository) {
		String name = name(repository);
		List<ProjectVersionReference> versions = new ArrayList<>();
		URI page = this.endpoint.resolve("/v2/" + name + "/tags/list?n=100");
		while (page != null) {
			HttpResponse<String> tags = send(request(page, repository).GET().build(), 200);
			for (JsonNode tagNode : json(tags.body()).path("tags")) {
				String tag = tagNode.asText();
				if (!VERSION_LABEL.matcher(tag).matches()) {
					continue;
				}
				HttpResponse<String> manifest = send(request("/v2/" + name + "/manifests/" + tag, repository)
					.method("HEAD", HttpRequest.BodyPublishers.noBody())
					.header("Accept", MANIFEST_ACCEPT)
					.build(), 200);
				String digest = requireDigest(manifest.headers()
					.firstValue("Docker-Content-Digest")
					.orElseThrow(() -> new ProjectVersionException(
							new ProjectVersionFailure("PROJECT_REGISTRY_RESPONSE_INVALID", ""))));
				versions.add(new ProjectVersionReference(tag, digest));
			}
			page = nextPage(tags);
		}
		return versions;
	}

	@Override
	public Optional<RegistryArtifact> pullArtifact(String repository, String reference) {
		String name = name(repository);
		HttpResponse<String> manifest = sendAllowMissing(
				request("/v2/" + name + "/manifests/" + reference, repository).GET()
					.header("Accept", MANIFEST_ACCEPT)
					.build());
		if (manifest.statusCode() == 404) {
			return Optional.empty();
		}
		requireStatus(manifest, 200);
		String digest = requireDigest(manifest.headers()
			.firstValue("Docker-Content-Digest")
			.orElseThrow(() -> new ProjectVersionException(
					new ProjectVersionFailure("PROJECT_REGISTRY_RESPONSE_INVALID", ""))));
		JsonNode descriptor = contentDescriptor(json(manifest.body()));
		String blobDigest = requireDigest(descriptor.path("digest").asText());
		HttpResponse<String> blob = send(request("/v2/" + name + "/blobs/" + blobDigest, repository).GET().build(),
				200);
		return Optional.of(new RegistryArtifact(digest, blob.body()));
	}

	@Override
	public boolean imageAvailable(String repository, String digest) {
		String name = name(repository);
		HttpResponse<String> response = sendAllowMissing(request("/v2/" + name + "/manifests/" + digest, repository)
			.method("HEAD", HttpRequest.BodyPublishers.noBody())
			.header("Accept", MANIFEST_ACCEPT)
			.build());
		if (response.statusCode() == 404) {
			return false;
		}
		requireStatus(response, 200);
		return digest.equals(requireDigest(response.headers()
			.firstValue("Docker-Content-Digest")
			.orElseThrow(() -> new ProjectVersionException(
					new ProjectVersionFailure("PROJECT_REGISTRY_RESPONSE_INVALID", "")))));
	}

	private HttpRequest.Builder request(String path, String repository) {
		return request(this.endpoint.resolve(path), repository);
	}

	private HttpRequest.Builder request(URI uri, String repository) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri);
		this.authorization.authorization(repository).ifPresent(value -> request.header("Authorization", value));
		return request;
	}

	private URI nextPage(HttpResponse<?> response) {
		return response.headers().firstValue("Link").flatMap(value -> {
			int opening = value.indexOf('<');
			int closing = value.indexOf('>', opening + 1);
			if (opening < 0 || closing < 0 || !value.substring(closing).contains("rel=\"next\"")) {
				return Optional.empty();
			}
			return Optional.of(this.endpoint.resolve(value.substring(opening + 1, closing)));
		}).orElse(null);
	}

	private HttpResponse<String> send(HttpRequest request, int expected) {
		HttpResponse<String> response = sendAllowMissing(request);
		requireStatus(response, expected);
		return response;
	}

	private HttpResponse<String> sendAllowMissing(HttpRequest request) {
		try {
			return this.client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException error) {
			throw new IllegalStateException("registry unavailable", error);
		}
		catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("registry request interrupted", error);
		}
	}

	private static String requireDigest(String value) {
		if (!DIGEST.matcher(value).matches()) {
			throw new ProjectVersionException(new ProjectVersionFailure("PROJECT_REGISTRY_RESPONSE_INVALID", ""));
		}
		return value;
	}

	private static void requireStatus(HttpResponse<?> response, int expected) {
		if (response.statusCode() != expected) {
			throw new IllegalStateException("registry returned HTTP " + response.statusCode());
		}
	}

	private static JsonNode json(String value) {
		try {
			return JSON.readTree(value);
		}
		catch (RuntimeException error) {
			throw new IllegalStateException("registry returned invalid JSON", error);
		}
	}

	private static JsonNode contentDescriptor(JsonNode manifest) {
		JsonNode blobs = manifest.path("blobs");
		return blobs.isArray() && !blobs.isEmpty() ? blobs.get(0) : manifest.path("layers").path(0);
	}

	private static String name(String repository) {
		if (!repository.startsWith("ghcr.io/")) {
			throw new IllegalArgumentException("repository must use ghcr.io");
		}
		return repository.substring("ghcr.io/".length());
	}

}
