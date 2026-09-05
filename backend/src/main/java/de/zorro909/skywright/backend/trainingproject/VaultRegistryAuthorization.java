package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.credential.CredentialBinding;
import de.zorro909.skywright.backend.credential.VaultBindings;
import de.zorro909.skywright.backend.projectversion.RegistryAuthorization;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import java.util.Base64;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Selects the registered resolver binding, never the target's image-pull identity. */
class VaultRegistryAuthorization implements RegistryAuthorization {

	private final TrainingProjectRepository projects;

	private final VaultBindings vault;

	private final URI tokenEndpoint;

	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(3))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	VaultRegistryAuthorization(TrainingProjectRepository projects, VaultBindings vault) {
		this(projects, vault, URI.create("https://ghcr.io/token"));
	}

	VaultRegistryAuthorization(TrainingProjectRepository projects, VaultBindings vault, URI tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
		this.projects = projects;
		this.vault = vault;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> authorization(String repository) {
		var references = this.projects.findAll()
			.stream()
			.flatMap(p -> p.view().bindingHistory().stream())
			.filter(b -> repository.equals(b.repository())
					&& ("active".equals(b.state()) || "candidate".equals(b.state())))
			.toList();
		if (references.isEmpty() || references.stream().allMatch(b -> b.accessMode() == RegistryAccessMode.PUBLIC)) {
			return Optional.empty();
		}
		if (references.size() != 1) {
			throw unavailable();
		}
		var reference = references.getFirst();
		var binding = this.vault.definitions()
			.stream()
			.filter(b -> b.id().equals(reference.resolverCredentialBindingId())
					&& b.kind() == CredentialBinding.Kind.GHCR && b.resource().equals(repository)
					&& b.role().equals("backend-resolver"))
			.findFirst()
			.orElseThrow(VaultRegistryAuthorization::unavailable);
		return Optional
			.of(this.vault
				.resolve(binding.id(), binding.revision(), "backend-resolver",
						secret -> exchange(repository, "Basic " + Base64.getEncoder()
							.encodeToString((secret.path("username").asText() + ":" + secret.path("token").asText())
								.getBytes(StandardCharsets.UTF_8))))
				.value()
				.orElseThrow(VaultRegistryAuthorization::unavailable));
	}

	private String exchange(String repository, String authorization) {
		if (!repository.matches("ghcr\\.io/[a-z0-9._-]+/[a-z0-9._/-]+")) {
			throw unavailable();
		}
		String scope = URLEncoder.encode("repository:" + repository.substring("ghcr.io/".length()) + ":pull",
				StandardCharsets.UTF_8);
		try {
			var request = HttpRequest.newBuilder(URI.create(this.tokenEndpoint + "?service=ghcr.io&scope=" + scope))
				.timeout(Duration.ofSeconds(5))
				.header("Authorization", authorization)
				.GET()
				.build();
			var response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw unavailable();
			}
			var data = JsonMapper.builder().build().readTree(response.body());
			String token = data.path("token").asText(data.path("access_token").asText(""));
			if (token.isBlank() || token.chars().anyMatch(Character::isISOControl)) {
				throw unavailable();
			}
			return "Bearer " + token;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (Exception exception) {
			throw unavailable();
		}
	}

	private static TrainingProjectException unavailable() {
		return new TrainingProjectException("REGISTRY_CREDENTIALS_UNAVAILABLE",
				"The registry resolver binding is unavailable.");
	}

}
