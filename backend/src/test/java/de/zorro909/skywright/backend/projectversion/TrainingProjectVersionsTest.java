package de.zorro909.skywright.backend.projectversion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.zorro909.skywright.backend.configurationcontract.ConfigurationContracts;
import de.zorro909.skywright.backend.metriccontract.MetricContracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TrainingProjectVersionsTest {

	private final ConfigurationContracts configuration = new ConfigurationContracts();

	private final MetricContracts metrics = new MetricContracts();

	@Test
	void discoversAndIndependentlyVerifiesACompleteVersion() {
		String configurationArtifact = configurationContract();
		String metricArtifact = metricContract();
		String configurationDigest = digest(configurationArtifact);
		String metricDigest = digest(metricArtifact);
		String configurationOciDigest = "sha256:" + "e".repeat(64);
		String metricOciDigest = "sha256:" + "f".repeat(64);
		String cudaImage = "sha256:" + "a".repeat(64);
		String rocmImage = "sha256:" + "b".repeat(64);
		String versionOciDigest = "sha256:" + "9".repeat(64);
		String label = "1".repeat(40) + "-github-123-1";
		String manifest = manifest(label, configurationDigest, metricDigest, configurationOciDigest, metricOciDigest,
				cudaImage, rocmImage);
		FakeRegistry registry = new FakeRegistry().versionArtifact(versionOciDigest, manifest)
			.artifact(configurationOciDigest, configurationArtifact)
			.artifact(metricOciDigest, metricArtifact)
			.image(cudaImage)
			.image(rocmImage)
			.image("sha256:" + "c".repeat(64))
			.image("sha256:" + "d".repeat(64));

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"), versionOciDigest);

		assertThat(assessment.runnable()).as(assessment.errors().toString()).isTrue();
		assertThat(assessment.errors()).isEmpty();
		assertThat(assessment.version().projectIdentity()).isEqualTo("stable-project");
		assertThat(assessment.version().manifestArtifactDigest()).isEqualTo(versionOciDigest);
		assertThat(assessment.version().images()).containsEntry("cuda", cudaImage).containsEntry("rocm", rocmImage);
		assertThat(assessment.version().imageFor("cuda")).isEqualTo(cudaImage);
		assertThatExceptionOfType(ProjectVersionException.class).isThrownBy(() -> assessment.version().imageFor("tpu"))
			.satisfies(error -> assertThat(error.failure().code()).isEqualTo("PROJECT_CAPABILITIES_INCOMPATIBLE"));
		assertThat(assessment.version().configurationContract()).isNotNull();
		assertThat(assessment.version().metricCatalog().projectContractDigest()).isEqualTo(metricDigest);
		assertThat(registry.availabilityRequests).contains("ghcr.io/example/environment@sha256:" + "c".repeat(64),
				"ghcr.io/example/environment@sha256:" + "d".repeat(64));
		assertThat(new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("another-project", "ghcr.io/example/project"), versionOciDigest)
			.errors()).extracting(ProjectVersionFailure::code).containsExactly("PROJECT_IDENTITY_INVALID");
	}

	@Test
	void rejectsMalformedAndUnavailableEnvironmentProfiles() {
		String configurationArtifact = configurationContract();
		String metricArtifact = metricContract();
		String configurationOciDigest = "sha256:" + "e".repeat(64);
		String metricOciDigest = "sha256:" + "f".repeat(64);
		String cudaImage = "sha256:" + "a".repeat(64);
		String rocmImage = "sha256:" + "b".repeat(64);
		String versionOciDigest = "sha256:" + "9".repeat(64);
		String label = "1".repeat(40) + "-github-123-1";
		String manifest = manifest(label, digest(configurationArtifact), digest(metricArtifact), configurationOciDigest,
				metricOciDigest, cudaImage, rocmImage)
			.replace("ghcr.io/example/environment:1-cuda@", "not-a-repository@");
		FakeRegistry registry = new FakeRegistry().versionArtifact(versionOciDigest, manifest)
			.artifact(configurationOciDigest, configurationArtifact)
			.artifact(metricOciDigest, metricArtifact)
			.image(cudaImage)
			.image(rocmImage);

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"), versionOciDigest);

		assertThat(assessment.runnable()).isFalse();
		assertThat(assessment.errors()).extracting(ProjectVersionFailure::code)
			.contains("PROJECT_PROFILE_NOT_DIGEST_PINNED", "PROJECT_PROFILE_MISSING");
	}

	@Test
	void rejectsACombinedLabelThatHidesAMalformedSourceRevision() {
		String configurationArtifact = configurationContract();
		String metricArtifact = metricContract();
		String configurationOciDigest = "sha256:" + "e".repeat(64);
		String metricOciDigest = "sha256:" + "f".repeat(64);
		String versionOciDigest = "sha256:" + "9".repeat(64);
		String label = "1".repeat(40) + "-extra-github-123-1";
		String manifest = manifest(label, digest(configurationArtifact), digest(metricArtifact), configurationOciDigest,
				metricOciDigest, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64))
			.replace("\"sourceRevision\":\"" + "1".repeat(40) + "\"",
					"\"sourceRevision\":\"" + "1".repeat(40) + "-extra\"");
		FakeRegistry registry = new FakeRegistry().versionArtifact(versionOciDigest, manifest)
			.artifact(configurationOciDigest, configurationArtifact)
			.artifact(metricOciDigest, metricArtifact)
			.image("sha256:" + "a".repeat(64))
			.image("sha256:" + "b".repeat(64));

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"), versionOciDigest);

		assertThat(assessment.errors()).extracting(ProjectVersionFailure::code)
			.contains("PROJECT_VERSION_LABEL_INVALID");
	}

	@Test
	void rejectsRegistryDigestSubstitution() {
		String requestedDigest = "sha256:" + "9".repeat(64);
		FakeRegistry registry = new FakeRegistry().substitutedArtifact(requestedDigest, "sha256:" + "8".repeat(64),
				"{}");

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"), requestedDigest);

		assertThat(assessment.errors()).extracting(ProjectVersionFailure::code)
			.containsExactly("PROJECT_VERSION_DIGEST_MISMATCH");
	}

	@Test
	void enumeratesAgeBearingReferencesWithoutResolvingThem() {
		String first = "sha256:" + "8".repeat(64);
		String second = "sha256:" + "9".repeat(64);
		Instant observedAt = Instant.parse("2026-08-18T00:00:00Z");
		String firstLabel = "1".repeat(40) + "-github-1-1";
		String secondLabel = "2".repeat(40) + "-github-2-1";
		FakeRegistry registry = new FakeRegistry().versionReference(firstLabel, first)
			.versionReference(secondLabel, second);

		ProjectVersionDiscovery discovery = new TrainingProjectVersions(registry, this.configuration, this.metrics,
				Clock.fixed(observedAt, ZoneOffset.UTC))
			.discoverAvailable(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"));

		assertThat(discovery.registryAvailable()).isTrue();
		assertThat(discovery.versions()).containsExactly(new ProjectVersionReference(firstLabel, first),
				new ProjectVersionReference(secondLabel, second));
		assertThat(discovery.observedAt()).isEqualTo(observedAt);
		assertThat(registry.pullCount).isZero();
	}

	@Test
	void reportsEveryMissingOrIncompatiblePieceWithoutNarrowingSupport() {
		String configurationArtifact = configurationContract();
		String metricArtifact = metricContract();
		String label = "1".repeat(40) + "-github-123-1";
		String configurationOciDigest = "sha256:" + "e".repeat(64);
		String metricOciDigest = "sha256:" + "f".repeat(64);
		String cudaImage = "sha256:" + "a".repeat(64);
		String rocmImage = "sha256:" + "b".repeat(64);
		String versionOciDigest = "sha256:" + "9".repeat(64);
		String manifest = manifest(label, digest(configurationArtifact), digest(metricArtifact), configurationOciDigest,
				metricOciDigest, cudaImage, rocmImage);
		FakeRegistry registry = new FakeRegistry().versionArtifact(versionOciDigest, manifest)
			.artifact(configurationOciDigest, configurationArtifact);

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover(new TrainingProjectBinding("stable-project", "ghcr.io/example/project"), versionOciDigest);

		assertThat(assessment.runnable()).isFalse();
		assertThat(assessment.version()).isNull();
		assertThat(assessment.errors()).extracting(ProjectVersionFailure::code)
			.contains("PROJECT_IMAGE_MISSING", "PROJECT_CONTRACT_ARTIFACT_MISSING");
	}

	private String configurationContract() {
		return """
				{"contractVersion":1,"skywrightSchema":%s,"projectSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{}},"defaults":{},"defaultsCompletionWitness":{},"references":{}}
				"""
			.formatted(this.configuration.skywrightSchemaIdentityJson())
			.trim();
	}

	private String metricContract() {
		String artifact = """
				{"contractVersion":1,"definitions":[],"skywrightSchema":%s}
				""".formatted(this.metrics.skywrightSchemaIdentityJson()).trim();
		return this.metrics.compile(artifact).canonicalJson();
	}

	private static String manifest(String label, String configurationDigest, String metricDigest,
			String configurationOciDigest, String metricOciDigest, String cudaImage, String rocmImage) {
		return """
				{"manifestVersion":1,"projectIdentity":"stable-project","versionLabel":"%s","sourceRevision":"%s","pipeline":"github-123-1","acceleratorBackends":["cuda","rocm"],"images":{"cuda":"%s","rocm":"%s"},"environmentProfiles":{"cuda":"ghcr.io/example/environment:1-cuda@sha256:%s","rocm":"ghcr.io/example/environment:1-rocm@sha256:%s"},"configurationContract":{"digest":"%s","skywrightSchema":%s},"metricContract":{"digest":"%s","skywrightSchema":%s},"contractArtifacts":{"cuda":{"configuration":"%s","metrics":"%s"},"rocm":{"configuration":"%s","metrics":"%s"}}}
				"""
			.formatted(label, "1".repeat(40), cudaImage, rocmImage, "c".repeat(64), "d".repeat(64), configurationDigest,
					new ConfigurationContracts().skywrightSchemaIdentityJson(), metricDigest,
					new MetricContracts().skywrightSchemaIdentityJson(), configurationOciDigest, metricOciDigest,
					configurationOciDigest, metricOciDigest)
			.trim();
	}

	private static String digest(String content) {
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static final class FakeRegistry implements ProjectVersionRegistry {

		private final Map<String, RegistryArtifact> artifacts = new HashMap<>();

		private final java.util.List<ProjectVersionReference> versions = new java.util.ArrayList<>();

		private final java.util.Set<String> images = new java.util.HashSet<>();

		private final java.util.List<String> availabilityRequests = new java.util.ArrayList<>();

		private int pullCount;

		FakeRegistry artifact(String reference, String content) {
			this.artifacts.put(reference, new RegistryArtifact(reference, content));
			return this;
		}

		FakeRegistry substitutedArtifact(String reference, String resolvedDigest, String content) {
			this.artifacts.put(reference, new RegistryArtifact(resolvedDigest, content));
			return this;
		}

		FakeRegistry versionArtifact(String reference, String content) {
			return artifact(reference, content);
		}

		FakeRegistry versionReference(String label, String digest) {
			this.versions.add(new ProjectVersionReference(label, digest));
			return this;
		}

		FakeRegistry image(String digest) {
			this.images.add(digest);
			return this;
		}

		@Override
		public java.util.List<ProjectVersionReference> listVersions(String repository) {
			return java.util.List.copyOf(this.versions);
		}

		@Override
		public Optional<RegistryArtifact> pullArtifact(String repository, String reference) {
			this.pullCount++;
			return Optional.ofNullable(this.artifacts.get(reference));
		}

		@Override
		public boolean imageAvailable(String repository, String digest) {
			this.availabilityRequests.add(repository + "@" + digest);
			return this.images.contains(digest);
		}

	}

}
