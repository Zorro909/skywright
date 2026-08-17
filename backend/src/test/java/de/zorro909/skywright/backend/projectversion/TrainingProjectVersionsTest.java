package de.zorro909.skywright.backend.projectversion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
		String label = "1".repeat(40) + "-github-123-1";
		String manifest = manifest(label, configurationDigest, metricDigest, configurationOciDigest, metricOciDigest,
				cudaImage, rocmImage);
		FakeRegistry registry = new FakeRegistry().artifact(label, manifest)
			.artifact(configurationOciDigest, configurationArtifact)
			.artifact(metricOciDigest, metricArtifact)
			.image(cudaImage)
			.image(rocmImage);

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover("ghcr.io/example/project", label);

		assertThat(assessment.runnable()).as(assessment.errors().toString()).isTrue();
		assertThat(assessment.errors()).isEmpty();
		assertThat(assessment.version().projectIdentity()).isEqualTo("stable-project");
		assertThat(assessment.version().images()).containsEntry("cuda", cudaImage).containsEntry("rocm", rocmImage);
		assertThat(assessment.version().imageFor("cuda")).isEqualTo(cudaImage);
		assertThatExceptionOfType(ProjectVersionException.class).isThrownBy(() -> assessment.version().imageFor("tpu"))
			.satisfies(error -> assertThat(error.failure().code()).isEqualTo("PROJECT_CAPABILITIES_INCOMPATIBLE"));
		assertThat(assessment.version().configurationContract()).isNotNull();
		assertThat(assessment.version().metricCatalog().projectContractDigest()).isEqualTo(metricDigest);
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
		String manifest = manifest(label, digest(configurationArtifact), digest(metricArtifact), configurationOciDigest,
				metricOciDigest, cudaImage, rocmImage);
		FakeRegistry registry = new FakeRegistry().artifact(label, manifest)
			.artifact(configurationOciDigest, configurationArtifact);

		ProjectVersionAssessment assessment = new TrainingProjectVersions(registry, this.configuration, this.metrics)
			.discover("ghcr.io/example/project", label);

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

		private final Map<String, String> artifacts = new HashMap<>();

		private final java.util.Set<String> images = new java.util.HashSet<>();

		FakeRegistry artifact(String reference, String content) {
			this.artifacts.put(reference, content);
			return this;
		}

		FakeRegistry image(String digest) {
			this.images.add(digest);
			return this;
		}

		@Override
		public Optional<String> pullArtifact(String repository, String reference) {
			return Optional.ofNullable(this.artifacts.get(reference));
		}

		@Override
		public boolean imageAvailable(String repository, String digest) {
			return this.images.contains(digest);
		}

	}

}
