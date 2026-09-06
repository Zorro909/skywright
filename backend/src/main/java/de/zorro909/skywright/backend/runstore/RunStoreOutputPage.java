package de.zorro909.skywright.backend.runstore;

import java.util.List;

/** One bounded page in provider key order for one output kind. */
public record RunStoreOutputPage(List<RunStoreOutput> outputs, String continuation) {
	public RunStoreOutputPage {
		outputs = List.copyOf(outputs);
	}
}
