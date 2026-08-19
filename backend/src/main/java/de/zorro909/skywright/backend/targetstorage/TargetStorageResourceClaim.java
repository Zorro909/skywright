package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

record TargetStorageResourceClaim(UUID storageId, TargetStoragePurpose purpose) {

	static String resourceKey(URI endpoint, String bucket) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of()
				.formatHex(
						digest.digest((canonicalEndpoint(endpoint) + '\0' + bucket).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	static String canonicalEndpoint(URI endpoint) {
		String scheme = endpoint.getScheme().toLowerCase(java.util.Locale.ROOT);
		String host = endpoint.getHost().toLowerCase(java.util.Locale.ROOT);
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		int port = endpoint.getPort();
		if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
			port = -1;
		}
		String path = TargetStorageResourceClaim.canonicalPath(endpoint.getRawPath());
		if (path == null || "/".equals(path)) {
			path = "";
		}
		try {
			return new URI(scheme, null, host, port, null, null, null).toASCIIString() + path;
		}
		catch (URISyntaxException impossible) {
			throw new IllegalArgumentException("endpoint cannot be canonicalized", impossible);
		}
	}

	private static String canonicalPath(String rawPath) {
		if (rawPath == null || rawPath.isEmpty()) {
			return "";
		}
		StringBuilder canonical = new StringBuilder(rawPath.length());
		for (int index = 0; index < rawPath.length(); ++index) {
			char value = rawPath.charAt(index);
			if (value != '%') {
				canonical.append(value);
				continue;
			}
			int octet = Integer.parseInt(rawPath.substring(index + 1, index + 3), 16);
			char decoded = (char) octet;
			if (TargetStorageResourceClaim.isUnreserved(decoded)) {
				canonical.append(decoded);
			}
			else {
				canonical.append('%');
				canonical.append(Character.toUpperCase(rawPath.charAt(index + 1)));
				canonical.append(Character.toUpperCase(rawPath.charAt(index + 2)));
			}
			index += 2;
		}
		return TargetStorageResourceClaim.removeDotSegments(canonical.toString());
	}

	private static String removeDotSegments(String path) {
		List<String> segments = new ArrayList<>();
		String[] values = path.split("/", -1);
		for (String value : values) {
			if (".".equals(value)) {
				continue;
			}
			if ("..".equals(value)) {
				if (segments.size() > 1) {
					segments.removeLast();
				}
				continue;
			}
			segments.add(value);
		}
		if (path.endsWith("/.") || path.endsWith("/..")) {
			segments.add("");
		}
		return String.join("/", segments);
	}

	private static boolean isUnreserved(char value) {
		return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
				|| value == '-' || value == '.' || value == '_' || value == '~';
	}
}
