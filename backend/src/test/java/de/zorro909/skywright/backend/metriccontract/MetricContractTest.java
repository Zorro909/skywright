package de.zorro909.skywright.backend.metriccontract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MetricContractTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MetricContracts contracts = new MetricContracts();

	@Test
	void compilesCanonicalContentAddressedCatalog() {
		String artifact = contract(
				"""
						[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean","bounds":{"minimum":0},"displayName":"Training loss"}]
						""");

		MetricContract compiled = this.contracts.compile(artifact);
		MetricCatalog catalog = compiled.catalog("stable-project", "project@revision");

		assertThat(compiled.digest()).startsWith("sha256:");
		assertThat(compiled.canonicalJson()).doesNotContain("\n");
		assertThat(catalog.projectIdentity()).isEqualTo("stable-project");
		assertThat(catalog.projectVersion()).isEqualTo("project@revision");
		assertThat(catalog.projectContractDigest()).isEqualTo(compiled.digest());
		assertThat(catalog.skywrightSchema()).isEqualTo(this.contracts.skywrightSchemaIdentity());
		assertThat(catalog.units()).contains("dimensionless", "ratio", "count", "bytes", "seconds");
		assertThat(catalog.projectDefinitions()).extracting(MetricDefinition::name).containsExactly("train/loss");
		assertThat(catalog.systemDefinitions()).extracting(MetricDefinition::name)
			.contains("skywright/system/throughput", "skywright/system/data_loading_wait",
					"skywright/system/memory_used");
	}

	@Test
	void reportsWhetherATrainingProjectVersionIsRunnable() {
		String validArtifact = contract(
				"""
						[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean"}]
						""");
		String expectedDigest = this.contracts.compile(validArtifact).digest();
		MetricContractAssessment runnable = this.contracts.assess(validArtifact, expectedDigest, "stable-project",
				"project@revision");
		MetricContractAssessment invalid = this.contracts.assess(
				contract(
						"""
								[{"name":"train/count","numericKind":"integer","unit":"count","recordingBasis":"step","comparison":"maximize","stepReduction":"mean"}]
								"""),
				expectedDigest, "stable-project", "project@revision");
		MetricContractAssessment digestMismatch = this.contracts.assess(
				contract(
						"""
								[{"name":"train/accuracy","numericKind":"real","unit":"ratio","recordingBasis":"step","comparison":"maximize","stepReduction":"last"}]
								"""),
				expectedDigest, "stable-project", "project@revision");

		assertThat(runnable.runnable()).isTrue();
		assertThat(runnable.catalog().projectContractDigest())
			.isEqualTo("sha256:c7362328bdffe43207f2422e1fdeb32fd4996edfeee0112c6f6b745d419464d3");
		assertThat(runnable.catalog()).isNotNull();
		assertThat(runnable.errors()).isEmpty();
		assertThat(invalid.runnable()).isFalse();
		assertThat(invalid.catalog()).isNull();
		assertThat(invalid.errors()).containsExactly(
				new MetricError("METRIC_INTEGER_MEAN", "project-contract", "/definitions/0/stepReduction", "semantic"));
		assertThat(digestMismatch.runnable()).isFalse();
		assertThat(digestMismatch.errors())
			.containsExactly(new MetricError("METRIC_CONTRACT_DIGEST_MISMATCH", "project-contract", "", "digest"));
	}

	@Test
	void comparesProjectMetricsByStableIdentityAndSemanticFieldsOnly() {
		MetricCatalog original = this.contracts
			.compile(contract(
					"""
							[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean","displayName":"Loss"}]
							"""))
			.catalog("stable-project", "project@one");
		MetricCatalog renamed = this.contracts
			.compile(contract(
					"""
							[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean","displayName":"Objective","description":"New wording"}]
							"""))
			.catalog("stable-project", "project@two");
		MetricCatalog changed = this.contracts
			.compile(contract(
					"""
							[{"name":"train/loss","numericKind":"real","unit":"ratio","recordingBasis":"step","comparison":"minimize","stepReduction":"mean"}]
							"""))
			.catalog("stable-project", "project@three");
		MetricCatalog anotherProject = this.contracts
			.compile(contract(
					"""
							[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean"}]
							"""))
			.catalog("another-project", "project@two");

		assertThat(this.contracts.projectMetricsComparable(original, renamed, "train/loss")).isTrue();
		assertThat(this.contracts.projectMetricsComparable(original, changed, "train/loss")).isFalse();
		assertThat(this.contracts.projectMetricsComparable(original, anotherProject, "train/loss")).isFalse();
	}

	@Test
	void canonicalizesScientificNotationAsPlainExactDecimals() {
		MetricContract compiled = this.contracts.compile(contract(
				"""
						[{"name":"train/loss","numericKind":"real","unit":"dimensionless","recordingBasis":"step","comparison":"minimize","stepReduction":"mean","bounds":{"minimum":1e2}}]
						"""));

		assertThat(compiled.canonicalJson()).contains("\"bounds\":{\"minimum\":100}");
	}

	@Test
	void passesVersionedSharedConformanceCorpus() throws IOException {
		JsonNode corpus = JSON
			.readTree(MetricContractTest.class.getResourceAsStream("/META-INF/skywright/metrics/corpus.json"));
		for (JsonNode fixture : corpus.path("cases")) {
			String artifact = contract(fixture.path("definitions").toString());
			if (fixture.has("errors")) {
				assertThatExceptionOfType(MetricContractException.class).as(fixture.path("name").asText())
					.isThrownBy(() -> this.contracts.compile(artifact))
					.satisfies(error -> assertThat(error.errors()).containsExactlyElementsOf(errors(fixture)));
			}
			else {
				assertThat(this.contracts.compile(artifact).catalog("project", "project@version").projectDefinitions())
					.isNotEmpty();
			}
		}
	}

	private List<MetricError> errors(JsonNode fixture) {
		List<MetricError> errors = new ArrayList<>();
		fixture.path("errors")
			.forEach(error -> errors.add(new MetricError(error.path("code").asText(), error.path("source").asText(),
					error.path("pointer").asText(), error.path("keyword").asText())));
		return errors;
	}

	private String contract(String definitions) {
		return """
				{"contractVersion":1,"skywrightSchema":%s,"definitions":%s}
				""".formatted(this.contracts.skywrightSchemaIdentityJson(), definitions);
	}

}
