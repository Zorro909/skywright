package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("real-service")
final class PackagedSkyPilotShutdownIT {

	@Test
	@Timeout(180)
	void environmentProbeReturnsAndAllowsJvmShutdownHooks(@TempDir Path temporary) throws Exception {
		var repository = Path.of(System.getProperty("repository.root"));
		Files.copy(repository.resolve("graalpy-environment/VerifyEnvironment.java"),
				temporary.resolve("VerifyEnvironment.java"));
		var launcher = temporary.resolve("EnvironmentShutdown.java");
		Files.writeString(launcher, """
				public class EnvironmentShutdown {
				    public static void main(String[] arguments) {
				        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("SHUTDOWN_HOOK")));
				        VerifyEnvironment.main(arguments);
				        System.out.println("PROBE_RETURNED");
				    }
				}
				""");
		var log = temporary.resolve("environment.log");
		var receipt = temporary.resolve("observation.json");
		try (var engine = Engine.create()) {
			var builder = new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED",
					"--sun-misc-unsafe-memory-access=allow", "-Xss16m", "-cp", System.getProperty("java.class.path"),
					launcher.toString(), System.getProperty("graalpy.external.directory"), receipt.toString(),
					engine.getVersion(), SkyPilotBridgeSettings.SKY_PILOT_VERSION);
			assertThat(runToExit(builder, log)).contains("PROBE_RETURNED", "SHUTDOWN_HOOK");
			assertThat(receipt).isRegularFile();
		}
	}

	@Test
	@Timeout(240)
	void nativeSdkCallerCanExitAfterClosingThePackagedClient(@TempDir Path temporary) throws Exception {
		try (var server = SkyPilotApiServerFixture.start()) {
			var log = temporary.resolve("shutdown.log");
			// sdk-status imports and calls the SDK on main, then closes the client
			// before that same host thread exits. In-process assertions miss a crash
			// in its native thread-local destructors after Context.close returns.
			var builder = PackagedSkyPilotFixture.process("sdk-status", server.endpoint());
			assertThat(runToExit(builder, log)).contains("\"sdk_status_completed\":true");
		}
	}

	private static String runToExit(ProcessBuilder builder, Path log) throws Exception {
		var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("Process shutdown: %s", Files.readString(log))
				.isTrue();
			var output = Files.readString(log);
			assertThat(process.exitValue()).as(output).isZero();
			assertThat(output).doesNotContain("SIGSEGV", "A fatal error has been detected");
			return output;
		}
		finally {
			process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
		}
	}

}
