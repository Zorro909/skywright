package de.zorro909.skywright.backend.runstore;

import java.util.Locale;
import java.util.Map;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Applies the allowlisted S3 compatibility options shared by qualification and I/O. */
public final class RunStoreS3Compatibility {

	private RunStoreS3Compatibility() {
	}

	public static S3Configuration configuration(boolean pathStyleAccess, Map<String, String> options) {
		return S3Configuration.builder()
			.pathStyleAccessEnabled(pathStyleAccess)
			.chunkedEncodingEnabled(booleanOption(options, "chunkedEncoding", false))
			.build();
	}

	public static RequestChecksumCalculation checksumCalculation(Map<String, String> options) {
		String value = options.getOrDefault("checksumCalculation", "when-required")
			.replace('-', '_')
			.toUpperCase(Locale.ROOT);
		try {
			return RequestChecksumCalculation.valueOf(value);
		}
		catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException("Unsupported checksumCalculation option", failure);
		}
	}

	private static boolean booleanOption(Map<String, String> options, String name, boolean defaultValue) {
		String value = options.get(name);
		if (value == null) {
			return defaultValue;
		}
		if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
			return Boolean.parseBoolean(value);
		}
		throw new IllegalArgumentException(name + " must be true or false");
	}

}
