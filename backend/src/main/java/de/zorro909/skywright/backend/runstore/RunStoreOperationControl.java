package de.zorro909.skywright.backend.runstore;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Caller-owned cancellation and per-request deadline for Java Run Store access. */
public record RunStoreOperationControl(Duration requestTimeout, BooleanSupplier cancellationRequested) {

	public RunStoreOperationControl {
		if (requestTimeout.isNegative() || requestTimeout.isZero()) {
			throw new IllegalArgumentException("Run Store request timeout must be positive");
		}
	}

	public static RunStoreOperationControl defaults() {
		return new RunStoreOperationControl(Duration.ofSeconds(30), () -> false);
	}

}
