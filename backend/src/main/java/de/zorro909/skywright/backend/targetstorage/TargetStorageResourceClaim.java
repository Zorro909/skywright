package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
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
				.formatHex(digest.digest((endpoint.toString() + '\0' + bucket).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
