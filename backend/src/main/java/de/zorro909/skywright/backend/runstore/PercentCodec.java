package de.zorro909.skywright.backend.runstore;

import java.nio.charset.StandardCharsets;

/** Canonical uppercase RFC 3986 encoding for one UTF-8 protocol component. */
final class PercentCodec {

	private PercentCodec() {
	}

	static String encode(String value) {
		StringBuilder result = new StringBuilder();
		for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
			int octet = item & 0xff;
			if (octet >= 'A' && octet <= 'Z' || octet >= 'a' && octet <= 'z' || octet >= '0' && octet <= '9'
					|| octet == '-' || octet == '.' || octet == '_' || octet == '~') {
				result.append((char) octet);
			}
			else {
				result.append("%%%02X".formatted(octet));
			}
		}
		return result.toString();
	}

}
