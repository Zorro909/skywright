package de.zorro909.skywright.backend.runstore;

import java.util.List;

/** One provider listing page without object bodies. */
public record RunStoreObjectPage(List<Entry> entries, String continuation) {

	public RunStoreObjectPage {
		entries = List.copyOf(entries);
	}
	public record Entry(String key, long size) {
	}
}
