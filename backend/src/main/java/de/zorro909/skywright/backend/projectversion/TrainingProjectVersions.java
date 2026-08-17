package de.zorro909.skywright.backend.projectversion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import de.zorro909.skywright.backend.configurationcontract.ConfigurationContract;
import de.zorro909.skywright.backend.configurationcontract.ConfigurationContractException;
import de.zorro909.skywright.backend.configurationcontract.ConfigurationContracts;
import de.zorro909.skywright.backend.metriccontract.MetricCatalog;
import de.zorro909.skywright.backend.metriccontract.MetricContractAssessment;
import de.zorro909.skywright.backend.metriccontract.MetricContracts;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pulls and independently verifies complete Training Project Version publications. */
public final class TrainingProjectVersions {

	private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

	private static final Pattern PROFILE = Pattern.compile("[^@\\s]+@sha256:[0-9a-f]{64}");

	private static final Pattern LABEL = Pattern.compile("[0-9a-f]{40}-[A-Za-z0-9][A-Za-z0-9._-]{0,86}");

	private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern PIPELINE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,86}");

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private final ProjectVersionRegistry registry;

	private final ConfigurationContracts configurationContracts;

	private final MetricContracts metricContracts;

	public TrainingProjectVersions(ProjectVersionRegistry registry, ConfigurationContracts configurationContracts,
			MetricContracts metricContracts) {
		this.registry = registry;
		this.configurationContracts = configurationContracts;
		this.metricContracts = metricContracts;
	}

	public List<ProjectVersionAssessment> discoverAvailable(TrainingProjectBinding project) {
		try {
			List<ProjectVersionAssessment> assessments = this.registry
				.listVersionArtifactDigests(project.registryRepository())
				.stream()
				.map(reference -> discover(project, reference))
				.toList();
			Map<String, Long> labelCounts = assessments.stream()
				.filter(ProjectVersionAssessment::runnable)
				.collect(java.util.stream.Collectors.groupingBy(assessment -> assessment.version().versionLabel(),
						java.util.stream.Collectors.counting()));
			return assessments.stream()
				.map(assessment -> assessment.runnable() && labelCounts.get(assessment.version().versionLabel()) > 1
						? failed("PROJECT_VERSION_LABEL_COLLISION", "/versionLabel") : assessment)
				.toList();
		}
		catch (RuntimeException error) {
			return List.of(failed("PROJECT_REGISTRY_UNAVAILABLE", ""));
		}
	}

	public ProjectVersionAssessment discover(TrainingProjectBinding project, String manifestArtifactDigest) {
		Optional<RegistryArtifact> pulled;
		try {
			pulled = this.registry.pullArtifact(project.registryRepository(), manifestArtifactDigest);
		}
		catch (RuntimeException error) {
			return failed("PROJECT_REGISTRY_UNAVAILABLE", "");
		}
		if (pulled.isEmpty()) {
			return failed("PROJECT_VERSION_MISSING", "");
		}
		ObjectNode manifest;
		try {
			RegistryArtifact artifact = pulled.get();
			if (!DIGEST.matcher(manifestArtifactDigest).matches()
					|| !manifestArtifactDigest.equals(artifact.manifestDigest())) {
				return failed("PROJECT_VERSION_DIGEST_MISMATCH", "");
			}
			JsonNode parsed = JSON.readTree(artifact.content());
			if (!parsed.isObject()) {
				return failed("PROJECT_MANIFEST_INVALID", "");
			}
			manifest = (ObjectNode) parsed;
		}
		catch (JacksonException error) {
			return failed("PROJECT_MANIFEST_INVALID", "");
		}

		List<ProjectVersionFailure> errors = new ArrayList<>();
		if (manifest.path("manifestVersion").asInt(-1) != 1) {
			errors.add(failure("PROJECT_MANIFEST_VERSION_UNSUPPORTED", "/manifestVersion"));
		}
		String manifestLabel = manifest.path("versionLabel").asText();
		String sourceRevision = manifest.path("sourceRevision").asText();
		String pipeline = manifest.path("pipeline").asText();
		if (!LABEL.matcher(manifestLabel).matches() || !REVISION.matcher(sourceRevision).matches()
				|| !PIPELINE.matcher(pipeline).matches() || !manifestLabel.equals(sourceRevision + "-" + pipeline)) {
			errors.add(failure("PROJECT_VERSION_LABEL_INVALID", "/versionLabel"));
		}
		String projectIdentity = manifest.path("projectIdentity").asText();
		if (!project.projectIdentity().equals(projectIdentity)) {
			errors.add(failure("PROJECT_IDENTITY_INVALID", "/projectIdentity"));
		}

		List<String> backends = backends(manifest.path("acceleratorBackends"), errors);
		ObjectNode imageNode = object(manifest.get("images"), "/images", errors);
		ObjectNode profileNode = object(manifest.get("environmentProfiles"), "/environmentProfiles", errors);
		ObjectNode artifactNode = object(manifest.get("contractArtifacts"), "/contractArtifacts", errors);
		Map<String, String> images = new HashMap<>();
		Map<String, String> profiles = new HashMap<>();
		Map<String, String> configurationArtifacts = new HashMap<>();
		Map<String, String> metricArtifacts = new HashMap<>();
		for (String backend : backends) {
			String image = imageNode.path(backend).asText();
			Boolean available = DIGEST.matcher(image).matches()
					? imageAvailable(project.registryRepository(), image, errors, backend) : Boolean.FALSE;
			if (Boolean.FALSE.equals(available)) {
				errors.add(failure("PROJECT_IMAGE_MISSING", "/images/" + backend));
			}
			else if (Boolean.TRUE.equals(available)) {
				images.put(backend, image);
			}
			String profile = profileNode.path(backend).asText();
			if (!PROFILE.matcher(profile).matches()) {
				errors.add(failure("PROJECT_PROFILE_NOT_DIGEST_PINNED", "/environmentProfiles/" + backend));
			}
			else {
				profiles.put(backend, profile);
			}
			JsonNode backendArtifacts = artifactNode.path(backend);
			pullContract(project.registryRepository(), backendArtifacts.path("configuration").asText(), backend,
					"configuration", configurationArtifacts, errors);
			pullContract(project.registryRepository(), backendArtifacts.path("metrics").asText(), backend, "metrics",
					metricArtifacts, errors);
		}
		checkExactKeys(imageNode, backends, "/images", errors);
		checkExactKeys(profileNode, backends, "/environmentProfiles", errors);
		checkExactKeys(artifactNode, backends, "/contractArtifacts", errors);

		String configurationDigest = manifest.path("configurationContract").path("digest").asText();
		String metricDigest = manifest.path("metricContract").path("digest").asText();
		ConfigurationContract configurationContract = validateConfiguration(configurationArtifacts, configurationDigest,
				errors);
		MetricCatalog metricCatalog = validateMetrics(metricArtifacts, metricDigest, projectIdentity, manifestLabel,
				errors);
		verifySchemaIdentity(manifest.path("configurationContract").path("skywrightSchema"),
				this.configurationContracts.skywrightSchemaIdentityJson(), "/configurationContract/skywrightSchema",
				errors);
		verifySchemaIdentity(manifest.path("metricContract").path("skywrightSchema"),
				this.metricContracts.skywrightSchemaIdentityJson(), "/metricContract/skywrightSchema", errors);

		if (!errors.isEmpty()) {
			return new ProjectVersionAssessment(false, null, errors.stream().distinct().sorted().toList());
		}
		return new ProjectVersionAssessment(true,
				new TrainingProjectVersion(projectIdentity, manifestLabel, manifestArtifactDigest, sourceRevision,
						pipeline, images, profiles, configurationDigest, metricDigest, configurationContract,
						metricCatalog),
				List.of());
	}

	private Boolean imageAvailable(String repository, String digest, List<ProjectVersionFailure> errors,
			String backend) {
		try {
			return this.registry.imageAvailable(repository, digest);
		}
		catch (RuntimeException error) {
			errors.add(failure("PROJECT_REGISTRY_UNAVAILABLE", "/images/" + backend));
			return null;
		}
	}

	private void pullContract(String repository, String reference, String backend, String kind,
			Map<String, String> artifacts, List<ProjectVersionFailure> errors) {
		String pointer = "/contractArtifacts/" + backend + "/" + kind;
		if (!DIGEST.matcher(reference).matches()) {
			errors.add(failure("PROJECT_CONTRACT_ARTIFACT_MISSING", pointer));
			return;
		}
		try {
			Optional<RegistryArtifact> content = this.registry.pullArtifact(repository, reference);
			if (content.isEmpty()) {
				errors.add(failure("PROJECT_CONTRACT_ARTIFACT_MISSING", pointer));
			}
			else {
				RegistryArtifact artifact = content.get();
				if (!reference.equals(artifact.manifestDigest())) {
					errors.add(failure("PROJECT_CONTRACT_ARTIFACT_DIGEST_MISMATCH", pointer));
				}
				else {
					artifacts.put(backend, artifact.content());
				}
			}
		}
		catch (RuntimeException error) {
			errors.add(failure("PROJECT_REGISTRY_UNAVAILABLE", pointer));
		}
	}

	private ConfigurationContract validateConfiguration(Map<String, String> artifacts, String expectedDigest,
			List<ProjectVersionFailure> errors) {
		if (artifacts.isEmpty() || new HashSet<>(artifacts.values()).size() != 1
				|| !DIGEST.matcher(expectedDigest).matches()) {
			errors.add(failure("PROJECT_CONFIGURATION_CONTRACT_INVALID", "/configurationContract"));
			return null;
		}
		String content = artifacts.values().iterator().next();
		if (!digest(content).equals(expectedDigest)) {
			errors.add(failure("PROJECT_CONFIGURATION_CONTRACT_DIGEST_MISMATCH", "/configurationContract/digest"));
			return null;
		}
		try {
			return this.configurationContracts.compile(content);
		}
		catch (ConfigurationContractException error) {
			errors.add(failure("PROJECT_CONFIGURATION_CONTRACT_INVALID", "/configurationContract"));
			return null;
		}
	}

	private MetricCatalog validateMetrics(Map<String, String> artifacts, String expectedDigest, String projectIdentity,
			String versionLabel, List<ProjectVersionFailure> errors) {
		if (artifacts.isEmpty() || new HashSet<>(artifacts.values()).size() != 1
				|| !DIGEST.matcher(expectedDigest).matches()) {
			errors.add(failure("PROJECT_METRIC_CONTRACT_INVALID", "/metricContract"));
			return null;
		}
		MetricContractAssessment assessment = this.metricContracts.assess(artifacts.values().iterator().next(),
				expectedDigest, projectIdentity, versionLabel);
		if (!assessment.runnable()) {
			errors.add(failure("PROJECT_METRIC_CONTRACT_INVALID", "/metricContract"));
			return null;
		}
		return assessment.catalog();
	}

	private static List<String> backends(JsonNode node, List<ProjectVersionFailure> errors) {
		if (!node.isArray() || node.isEmpty()) {
			errors.add(failure("PROJECT_BACKENDS_EMPTY", "/acceleratorBackends"));
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			String backend = item.asText();
			if (!Set.of("cuda", "rocm").contains(backend) || values.contains(backend)) {
				errors.add(failure("PROJECT_BACKEND_UNSUPPORTED", "/acceleratorBackends"));
			}
			else {
				values.add(backend);
			}
		}
		return values;
	}

	private static ObjectNode object(JsonNode node, String pointer, List<ProjectVersionFailure> errors) {
		if (node == null || !node.isObject()) {
			errors.add(failure("PROJECT_MANIFEST_INVALID", pointer));
			return JSON.createObjectNode();
		}
		return (ObjectNode) node;
	}

	private static void checkExactKeys(ObjectNode node, List<String> backends, String pointer,
			List<ProjectVersionFailure> errors) {
		Set<String> keys = node.propertyStream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
		if (!keys.equals(Set.copyOf(backends))) {
			errors.add(failure("PROJECT_BACKEND_MAP_INCOMPLETE", pointer));
		}
	}

	private static void verifySchemaIdentity(JsonNode actual, String expectedJson, String pointer,
			List<ProjectVersionFailure> errors) {
		try {
			if (!JSON.readTree(expectedJson).equals(actual)) {
				errors.add(failure("PROJECT_SCHEMA_UNSUPPORTED", pointer));
			}
		}
		catch (JacksonException error) {
			throw new IllegalStateException(error);
		}
	}

	private static String digest(String content) {
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private static ProjectVersionAssessment failed(String code, String pointer) {
		return new ProjectVersionAssessment(false, null, List.of(failure(code, pointer)));
	}

	private static ProjectVersionFailure failure(String code, String pointer) {
		return new ProjectVersionFailure(code, pointer);
	}

}
