package de.zorro909.skywright.backend.runlog;

/** Bounded immutable objects; no payload or history lives in database state. */
interface ArchiveObjects extends AutoCloseable {

	byte[] read(String relativeKey, int limit);

	/** Return the bytes that won the immutable publication, including a prior write. */
	byte[] publish(String relativeKey, byte[] bytes, String kind);

	java.util.List<String> keysAfter(String prefix, String after, int limit);

	@Override
	void close();

}
