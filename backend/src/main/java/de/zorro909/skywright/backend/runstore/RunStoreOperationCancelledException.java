package de.zorro909.skywright.backend.runstore;

/** Caller cancellation observed before a Java Run Store provider request. */
public final class RunStoreOperationCancelledException extends RuntimeException {

	public RunStoreOperationCancelledException() {
		super("Run Store operation was cancelled by its caller");
	}

}
