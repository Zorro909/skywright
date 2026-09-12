package de.zorro909.skywright.backend.runtimeassembly;

import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** Validates pinned workload materials and delivers them unchanged on either target. */
final class RuntimeDelivery {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static String command(RunDefinition definition, RuntimeMaterials materials, String acceleratorBackend) {
		var value = definition.value();
		var version = value.path("trainingProjectVersion");
		String image = materials.image();
		if (image == null || !image.matches("[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}")
				|| !image.endsWith("@" + version.path("images").path(acceleratorBackend).asText()))
			throw new IllegalArgumentException("Missing digest-pinned target image");
		if (!digest(materials.configurationContract()).equals(version.at("/configurationContract/digest").asText())
				|| !digest(materials.metricContract()).equals(version.at("/metricContract/digest").asText()))
			throw new IllegalArgumentException("Pinned project contract is missing or differs");
		var dataset = materials.dataset();
		if (!dataset.datasetIdentity().equals(value.at("/datasetDefinition/datasetIdentity").asText())
				|| !dataset.version().equals(value.at("/datasetDefinition/version").asText())
				|| !dataset.contentFingerprint().equals(value.at("/datasetDefinition/contentFingerprint").asText()))
			throw new IllegalArgumentException("Dataset materials differ from the accepted definition");
		String directory = "/tmp/skywright-runtime-" + materials.runId();
		String command = "set -eu\numask 077\npython - <<'SKYWRIGHT_DELIVERY'\nimport base64,pathlib\np=pathlib.Path('"
				+ directory + "');p.mkdir(exist_ok=True)\n(p/'definition.json').write_bytes(base64.b64decode('"
				+ encoded(definition.encode()) + "'))\n(p/'materials.json').write_bytes(base64.b64decode('"
				+ encoded(JSON.writeValueAsString(materials))
				+ "'))\nSKYWRIGHT_DELIVERY\ncd /workspace\nexec python -m skywright._runtime --definition '" + directory
				+ "/definition.json' --materials '" + directory + "/materials.json' --cache-directory '" + directory
				+ "/dataset-cache'";
		return command;
	}

	private static String encoded(String value) {
		if (value.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
			throw new IllegalArgumentException("Runtime delivery document exceeds its byte limit");
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String digest(String value) {
		if (value == null)
			throw new IllegalArgumentException("Missing project contract artifact");
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
