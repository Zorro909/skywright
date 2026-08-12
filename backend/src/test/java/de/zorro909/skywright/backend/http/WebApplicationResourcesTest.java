package de.zorro909.skywright.backend.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WebApplicationResourcesTest {

	@ParameterizedTest
	@ValueSource(strings = { "", "/", "about", "/about", "/missing/nested" })
	void extensionlessApplicationRoutesUseTheShell(String path) {
		assertThat(WebApplicationResources.isApplicationRoute(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/missing", "/openapi/missing", "/livez", "/readyz", "/actuator/missing",
			"/assets/missing", "/proxy/missing", "/missing.js", "/nested/file.json" })
	void backendNamespacesAndFileRequestsKeepTheirNormalHandling(String path) {
		assertThat(WebApplicationResources.isApplicationRoute(path)).isFalse();
	}

}
