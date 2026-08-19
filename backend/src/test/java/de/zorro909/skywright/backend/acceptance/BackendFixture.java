package de.zorro909.skywright.backend.acceptance;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class BackendFixture implements AutoCloseable {

	private final ConfigurableApplicationContext application;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final URI baseUri;

	private final PostgreSqlFixture.Database database;

	private BackendFixture(ConfigurableApplicationContext application, PostgreSqlFixture.Database database) {
		this.application = application;
		this.database = database;
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

	static BackendFixture start(Map<String, Object> singletons) {
		var builder = new SpringApplicationBuilder(SkywrightBackendApplication.class)
			.initializers(application -> singletons
				.forEach((name, bean) -> application.getBeanFactory().registerSingleton(name, bean)));
		return start(builder);
	}

	private static BackendFixture start(SpringApplicationBuilder builder) {
		try {
			var database = PostgreSqlFixture.freshDatabase();
			try {
				var properties = new java.util.ArrayList<>(java.util.List.of(database.springProperties()));
				properties.add("server.port=0");
				properties.add("skywright.deployment.environment=test");
				var arguments = properties.stream().map(property -> "--" + property).toArray(String[]::new);
				var application = builder.web(WebApplicationType.SERVLET).run(arguments);
				return new BackendFixture(application, database);
			}
			catch (RuntimeException exception) {
				database.close();
				throw exception;
			}
		}
		catch (java.sql.SQLException exception) {
			throw new IllegalStateException("Could not provision the test database", exception);
		}
	}

	HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return request("GET", path, null);
	}

	HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
		var builder = HttpRequest.newBuilder(baseUri.resolve(path));
		if (body == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			builder.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		var request = builder.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	<T> T bean(Class<T> type) {
		return this.application.getBean(type);
	}

	@Override
	public void close() throws Exception {
		application.close();
		database.close();
	}

}
