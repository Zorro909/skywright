package de.zorro909.skywright.backend.runstore;

/** Persisted object bytes or metadata failed Run Store integrity validation. */
public final class RunStoreIntegrityException extends RuntimeException {

	public RunStoreIntegrityException(String message) {
		super(message);
	}

}
