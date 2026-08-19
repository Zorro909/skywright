package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
		int port = endpoint.getPort();
		if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
			port = -1;
		}
		String path = endpoint.normalize().getPath();
		if (path == null || "/".equals(path)) {
			path = "";
		}
		try {
			return new URI(scheme, null, host, port, path, null, null).toASCIIString();
		}
		catch (URISyntaxException impossible) {
			throw new IllegalArgumentException("endpoint cannot be canonicalized", impossible);
		}
	}
}
