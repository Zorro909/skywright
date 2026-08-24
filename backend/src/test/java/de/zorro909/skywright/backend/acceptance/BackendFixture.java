package de.zorro909.skywright.backend.acceptance;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import de.zorro909.skywright.backend.targetstorage.TargetStorageIntegrationTestConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
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

	private boolean ownsDatabase = true;

	private BackendFixture(ConfigurableApplicationContext application, PostgreSqlFixture.Database database) {
		this.application = application;
		this.database = database;
		var server = (WebServerApplicationContext) application;
		this.baseUri = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
	}

	static BackendFixture start() {
		return start(new SpringApplicationBuilder(SkywrightBackendApplication.class));
	}

	static BackendFixture startWithTargetStorageIntegration() {
		return start(new SpringApplicationBuilder(SkywrightBackendApplication.class,
				TargetStorageIntegrationTestConfiguration.class)
			.profiles("target-storage-integration"));
	}

	static BackendFixture start(BuildProperties buildProperties) {
		var builder = new SpringApplicationBuilder(SkywrightBackendApplication.class).initializers(
				application -> application.getBeanFactory().registerSingleton("buildProperties", buildProperties));
		return start(builder);
	}

	private static BackendFixture start(SpringApplicationBuilder builder) {
		try {
			var database = PostgreSqlFixture.freshDatabase();
			try {
				return start(builder, database);
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

	private static BackendFixture start(SpringApplicationBuilder builder, PostgreSqlFixture.Database database) {
		var properties = new java.util.ArrayList<>(java.util.List.of(database.springProperties()));
		properties.add("server.port=0");
		properties.add("skywright.deployment.environment=test");
		var arguments = properties.stream().map(property -> "--" + property).toArray(String[]::new);
		var application = builder.web(WebApplicationType.SERVLET).run(arguments);
		return new BackendFixture(application, database);
	}

	BackendFixture restartWithTargetStorageIntegration() {
		this.application.close();
		BackendFixture restarted = start(
				new SpringApplicationBuilder(SkywrightBackendApplication.class,
						TargetStorageIntegrationTestConfiguration.class)
					.profiles("target-storage-integration"),
				this.database);
		this.ownsDatabase = false;
		return restarted;
	}

	HttpResponse<String> get(String path) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(baseUri.resolve(path)).GET().build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	<T> T bean(Class<T> type) {
		return this.application.getBean(type);
	}

	URI baseUri() {
		return this.baseUri;
	}

	long countReleasedCredentialProjections(UUID publicationId, UUID bindingId, long bindingRevision)
			throws java.sql.SQLException {
		return this.database.countReleasedCredentialProjections(publicationId, bindingId, bindingRevision);
	}

	@Override
	public void close() throws Exception {
		application.close();
		if (this.ownsDatabase) {
			database.close();
		}
	}

}
