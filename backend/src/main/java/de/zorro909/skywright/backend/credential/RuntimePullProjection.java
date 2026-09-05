package de.zorro909.skywright.backend.credential;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Owner-only, read-only runtime input. The Training Process never mounts this file. */
public final class RuntimePullProjection implements AutoCloseable {

	private final Path directory;

	private final Path file;

	RuntimePullProjection(Path parent, String username, String token) {
		Path created = null;
		try {
			created = Files.createTempDirectory(parent, "pull-",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			this.directory = created;
			this.file = created.resolve("config.json");
			var auth = Base64.getEncoder()
				.encodeToString((username + ":" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			var json = JsonMapper.builder()
				.build()
				.writeValueAsString(Map.of("auths", Map.of("ghcr.io", Map.of("auth", auth))));
			Files.createFile(this.file,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.writeString(this.file, json);
			Files.setPosixFilePermissions(this.file, PosixFilePermissions.fromString("r--------"));
		}
		catch (Exception failure) {
			if (created != null) {
				try {
					Files.deleteIfExists(created.resolve("config.json"));
					Files.deleteIfExists(created);
				}
				catch (IOException cleanupFailure) {
					throw new IllegalStateException("Runtime pull projection cleanup failed");
				}
			}
			throw new IllegalStateException("Runtime pull projection failed");
		}
	}

	public Path file() {
		return this.file;
	}

	@Override
	public void close() {
		try {
			Files.deleteIfExists(this.file);
			Files.deleteIfExists(this.directory);
		}
		catch (IOException failure) {
			throw new IllegalStateException("Runtime pull projection cleanup failed");
		}
	}

	@Override
	public String toString() {
		return "Runtime pull Credential Projection";
	}

}
