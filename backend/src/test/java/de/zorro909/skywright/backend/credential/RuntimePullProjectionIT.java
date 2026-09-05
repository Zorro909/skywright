package de.zorro909.skywright.backend.credential;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@Tag("real-service")
class RuntimePullProjectionIT {

	@TempDir
	Path directory;

	@Test
	void containerRuntimePullsPrivateImageWithoutExposingRegistryIdentityToProcess() throws Exception {
		// Local Distribution stands in for GHCR; only the registry hostname is
		// substituted.
		String htpasswd = "fixture:$2b$12$QTRMVnwjUJSKuh30YvVW9.8R.AHoNL6ZB/ebQV3puxzlsV4Ndodfq\n";
		try (var registry = new GenericContainer<>("registry:2.8.3").withEnv("REGISTRY_AUTH", "htpasswd")
			.withEnv("REGISTRY_AUTH_HTPASSWD_REALM", "fixture")
			.withEnv("REGISTRY_AUTH_HTPASSWD_PATH", "/auth/htpasswd")
			.withCopyToContainer(Transferable.of(htpasswd), "/auth/htpasswd")
			.withExposedPorts(5000)
			.waitingFor(Wait.forHttp("/v2/").forStatusCode(401));
				var projection = new RuntimePullProjection(directory, "fixture", "pull-fixture")) {
			registry.start();
			String address = "127.0.0.1:" + registry.getMappedPort(5000);
			String image = address + "/skywright-private:fixture";
			var auth = directory.resolve("config.json");
			Files.writeString(auth, Files.readString(projection.file()).replace("ghcr.io", address));
			Files.setPosixFilePermissions(auth, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
			boolean podman = command(null, "docker", "--version").output().toLowerCase().contains("podman");
			var tls = podman ? List.of("--tls-verify=false") : List.<String>of();
			try {
				require(command(null, "docker", "pull", "docker.io/library/busybox:1.37.0"));
				require(command(null, "docker", "tag", "docker.io/library/busybox:1.37.0", image));
				require(imageCommand(auth, "push", tls, image));
				require(command(null, "docker", "rmi", image));
				assertThat(imageCommand(null, "pull", tls, image).exitCode()).isNotZero();
				require(imageCommand(auth, "pull", tls, image));
				var process = command(null, "docker", "run", "--rm", image, "env");
				require(process);
				assertThat(process.output()).doesNotContain("pull-fixture", "REGISTRY_AUTH", "DOCKER_CONFIG", "VAULT");
			}
			finally {
				command(null, "docker", "rmi", "-f", image);
				Files.deleteIfExists(auth);
			}
		}
	}

	private Result imageCommand(Path auth, String action, List<String> options, String image) throws Exception {
		var args = new ArrayList<>(List.of("docker", action));
		args.addAll(options);
		args.add(image);
		return command(auth, args.toArray(String[]::new));
	}

	private Result command(Path auth, String... args) throws Exception {
		var process = new ProcessBuilder(args).redirectErrorStream(true);
		process.environment().put("DOCKER_CONFIG", directory.toString());
		process.environment()
			.put("REGISTRY_AUTH_FILE", auth == null ? directory.resolve("empty.json").toString() : auth.toString());
		if (auth == null) {
			Files.writeString(directory.resolve("empty.json"), "{\"auths\":{}}");
			process.environment().put("DOCKER_CONFIG", directory.resolve("empty").toString());
		}
		var log = Files.createTempFile(directory, "runtime-", ".log");
		var running = process.redirectOutput(log.toFile()).start();
		if (!running.waitFor(Duration.ofMinutes(2).toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
			running.destroyForcibly();
			throw new AssertionError("Container runtime qualification timed out");
		}
		return new Result(running.exitValue(), Files.readString(log));
	}

	private static void require(Result result) {
		assertThat(result.exitCode()).as(result.output()).isZero();
	}

	private record Result(int exitCode, String output) {
	}

}
