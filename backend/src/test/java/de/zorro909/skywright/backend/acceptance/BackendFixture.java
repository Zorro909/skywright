package de.zorro909.skywright.backend.acceptance;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class BackendFixture implements AutoCloseable {

	private final ConfigurableApplicationContext application;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final URI baseUri;

	private BackendFixture(ConfigurableApplicationContext application) {
		this.application = application;
		var server = (WebServerApplicationContext) application;
		this.baseUri = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
	}

	static BackendFixture start() {
		return start(new SpringApplicationBuilder(SkywrightBackendApplication.class));
	}

	static BackendFixture start(BuildProperties buildProperties) {
		var builder = new SpringApplicationBuilder(SkywrightBackendApplication.class).initializers(
				application -> application.getBeanFactory().registerSingleton("buildProperties", buildProperties));
		return start(builder);
	}

	private static BackendFixture start(SpringApplicationBuilder builder) {
		var application = builder.web(WebApplicationType.SERVLET)
			.properties("server.port=0", "skywright.deployment.environment=test")
			.run();
		return new BackendFixture(application);
	}

	HttpResponse<String> get(String path) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(baseUri.resolve(path)).GET().build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Override
	public void close() {
		application.close();
	}

}
