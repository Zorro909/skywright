package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class TargetStorageEncoding {

	private static final String FIELD = "\\|";

	private static final String ITEM = "\\^";

	private TargetStorageEncoding() {
	}

	static String configuration(long revision, TargetStorageConfiguration configuration) {
		return revision + "|" + TargetStorageEncoding.encode(configuration.endpoint().toString()) + "|"
				+ TargetStorageEncoding.encode(configuration.region()) + "|" + configuration.pathStyleAccess() + "|"
				+ TargetStorageEncoding.encodeMap(configuration.compatibilityOptions());
	}

	static DecodedConfiguration configuration(String encoded) {
		String[] fields = encoded.split(FIELD, -1);
		return new DecodedConfiguration(Long.parseLong(fields[0]),
				new TargetStorageConfiguration(URI.create(TargetStorageEncoding.decode(fields[1])),
						TargetStorageEncoding.decode(fields[2]), Boolean.parseBoolean(fields[3]),
						TargetStorageEncoding.decodeMap(fields[4])));
	}

	static String binding(TargetStorageBinding binding) {
		return binding.role() + "|" + binding.bindingId() + "|" + binding.bindingRevision() + "|" + binding.readiness();
	}

	static TargetStorageBinding binding(String encoded) {
		String[] fields = encoded.split(FIELD, -1);
		return new TargetStorageBinding(TargetStorageRole.valueOf(fields[0]), UUID.fromString(fields[1]),
				Long.parseLong(fields[2]), BindingReadiness.valueOf(fields[3]));
	}

	static String assessment(TargetStorageAssessment assessment) {
		String bindings = assessment.bindingRevisions()
			.stream()
			.map(TargetStorageEncoding::binding)
			.map(TargetStorageEncoding::encode)
			.collect(Collectors.joining("~"));
		String capabilities = assessment.capabilities()
			.stream()
			.map(TargetStorageEncoding::capability)
			.collect(Collectors.joining("^"));
		return assessment.id() + "|" + assessment.configurationRevision() + "|" + assessment.observedFrom() + "|"
				+ assessment.observedUntil() + "|" + assessment.availability() + "|" + bindings + "|" + capabilities;
	}

	static TargetStorageAssessment assessment(String encoded) {
		String[] fields = encoded.split(FIELD, 7);
		List<TargetStorageBinding> bindings = List.of(fields[5].split("~", -1))
			.stream()
			.map(TargetStorageEncoding::decode)
			.map(TargetStorageEncoding::binding)
			.toList();
		List<TargetStorageCapabilityResult> capabilities = List.of(fields[6].split(ITEM, -1))
			.stream()
			.map(TargetStorageEncoding::capability)
			.toList();
		return new TargetStorageAssessment(UUID.fromString(fields[0]), Long.parseLong(fields[1]),
				Instant.parse(fields[2]), Instant.parse(fields[3]), CapabilityAvailability.valueOf(fields[4]), bindings,
				capabilities);
	}

	private static String capability(TargetStorageCapabilityResult result) {
		return TargetStorageEncoding.encode(result.capability()) + "," + result.succeeded() + ","
				+ TargetStorageEncoding.nullable(result.failureCode()) + ","
				+ TargetStorageEncoding.nullable(result.summary()) + ","
				+ TargetStorageEncoding.encodeMap(result.observations());
	}

	private static TargetStorageCapabilityResult capability(String encoded) {
		String[] fields = encoded.split(",", 5);
		return new TargetStorageCapabilityResult(TargetStorageEncoding.decode(fields[0]),
				Boolean.parseBoolean(fields[1]), TargetStorageEncoding.decodeNullable(fields[2]),
				TargetStorageEncoding.decodeNullable(fields[3]), TargetStorageEncoding.decodeMap(fields[4]));
	}

	private static String encodeMap(Map<String, String> value) {
		return value.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> TargetStorageEncoding.encode(entry.getKey()) + ":"
					+ TargetStorageEncoding.encode(entry.getValue()))
			.collect(Collectors.joining(";"));
	}

	private static Map<String, String> decodeMap(String value) {
		if (value.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		for (String entry : value.split(";")) {
			String[] fields = entry.split(":", 2);
			result.put(TargetStorageEncoding.decode(fields[0]), TargetStorageEncoding.decode(fields[1]));
		}
		return Map.copyOf(result);
	}

	private static String nullable(String value) {
		return value == null ? "-" : TargetStorageEncoding.encode(value);
	}

	private static String decodeNullable(String value) {
		return "-".equals(value) ? null : TargetStorageEncoding.decode(value);
	}

	private static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String value) {
		return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
	}

	record DecodedConfiguration(long revision, TargetStorageConfiguration configuration) {
	}

}
