package de.zorro909.skywright.deployment;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Read the production overlay so image tests exercise its actual writable-storage budget.
 */
final class BackendPodBudget {

	private BackendPodBudget() {
	}

	static String temporaryMount() throws Exception {
		Path root = Path.of(System.getProperty("repository.root"));
		Path output = Files.createTempFile("skywright-production-overlay-", ".yaml");
		String rendered;
		try {
			Process render = new ProcessBuilder("kubectl", "kustomize",
					root.resolve("deployment/overlays/production").toString())
				.redirectErrorStream(true)
				.redirectOutput(output.toFile())
				.start();
			if (!render.waitFor(30, TimeUnit.SECONDS)) {
				render.destroyForcibly();
				throw new IllegalStateException("Production overlay rendering timed out");
			}
			rendered = Files.readString(output);
			if (render.exitValue() != 0) {
				throw new IllegalStateException(rendered);
			}
		}
		finally {
			Files.deleteIfExists(output);
		}
		for (String document : rendered.split("\\n---\\n")) {
			if (document.contains("kind: Deployment\n") && document.contains("\n  name: skywright-backend\n")) {
				if (!document.contains("readOnlyRootFilesystem: true") || !document.contains("medium: Memory")
						|| !document.contains("runAsUser: 10001") || !document.contains("mountPath: /tmp")) {
					throw new IllegalStateException(
							"Backend pod no longer has the qualified temporary-storage contract");
				}
				var size = Pattern.compile("sizeLimit: ([0-9]+)Mi").matcher(document);
				if (!size.find()) {
					throw new IllegalStateException("Backend temporary volume has no MiB budget");
				}
				long bytes = Math.multiplyExact(Long.parseLong(size.group(1)), 1024L * 1024);
				if (size.find()) {
					throw new IllegalStateException("Ambiguous backend temporary volume budget");
				}
				return "/tmp:rw,exec,nosuid,size=" + bytes;
			}
		}
		throw new IllegalStateException("Production profile has no backend Deployment");
	}

}
