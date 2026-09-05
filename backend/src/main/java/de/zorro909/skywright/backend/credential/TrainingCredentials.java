package de.zorro909.skywright.backend.credential;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Transient launch-channel material. Never serialize this object into a task or record.
 */
public final class TrainingCredentials implements AutoCloseable {

	private final Map<String, String> values;

	private boolean closed;

	TrainingCredentials(Map<String, String> values) {
		this.values = new LinkedHashMap<>(values);
	}

	/** The transport must not log, persist, or retain the supplied map. */
	public synchronized <T> T send(Function<Map<String, String>, T> transport) {
		if (this.closed) {
			throw new IllegalStateException("Credential Projection has been released");
		}
		try {
			return transport.apply(Map.copyOf(this.values));
		}
		catch (RuntimeException failure) {
			throw new IllegalStateException("Credential Projection transport failed");
		}
	}

	@Override
	public synchronized void close() {
		this.closed = true;
		this.values.clear();
	}

	@Override
	public String toString() {
		return "Training Process Credential Projection";
	}

}
