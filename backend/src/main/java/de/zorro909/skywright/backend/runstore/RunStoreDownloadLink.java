package de.zorro909.skywright.backend.runstore;

import java.net.URI;

/** Raw downloads must verify expected size and SHA-256 before accepting bytes. */
public record RunStoreDownloadLink(URI url, String key, long size, String digest, Verification verification) {
	public enum Verification {

		NOT_RECORDED

	}
}
