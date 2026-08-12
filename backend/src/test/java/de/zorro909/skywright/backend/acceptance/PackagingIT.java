package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

final class PackagingIT {

	@Test
	void executableJarIsLayeredAndCarriesBuildIdentity() throws Exception {
		try (var executable = new JarFile(System.getProperty("backend.executable"))) {
			assertThat(executable.getJarEntry("BOOT-INF/layers.idx")).isNotNull();

			var buildInformation = executable.getJarEntry("META-INF/build-info.properties");
			assertThat(buildInformation).isNotNull();
			var properties = new Properties();
			try (InputStream input = executable.getInputStream(buildInformation)) {
				properties.load(input);
			}

			assertThat(properties.getProperty("build.version")).isEqualTo("0.1.0-SNAPSHOT");
			assertThat(properties.getProperty("build.sourceRevision")).matches("[0-9a-f]{40}");
			assertThat(Instant.parse(properties.getProperty("build.time"))).isNotNull();
		}
	}

}
