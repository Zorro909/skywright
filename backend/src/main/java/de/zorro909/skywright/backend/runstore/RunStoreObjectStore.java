package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.util.List;

/** Provider boundary hidden beneath Java Run Store discovery and download access. */
public interface RunStoreObjectStore {

	List<RunStoreObject> list(String prefix);

	RunStoreObject get(String key);

	URI presignGet(String key, int expiresInSeconds, String contentType, String filename);

}
