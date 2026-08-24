package de.zorro909.skywright.backend.acceptance;

import de.zorro909.skywright.backend.SkywrightBackendApplication;
import de.zorro909.skywright.backend.datasetpublication.DatasetPublicationCommitGateTestConfiguration;
import de.zorro909.skywright.backend.targetstorage.TargetStorageIntegrationTestConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class BackendFixture implements AutoCloseable {

	private ConfigurableApplicationContext application;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private URI baseUri;

	private final PostgreSqlFixture.Database database;

	private final Supplier<SpringApplicationBuilder> builder;

	private boolean ownsDatabase;

	private BackendFixture(ConfigurableApplicationContext application, PostgreSqlFixture.Database database,
			Supplier<SpringApplicationBuilder> builder, boolean ownsDatabase) {
		this.application = application;
		this.database = database;
		this.builder = builder;
		this.ownsDatabase = ownsDatabase;
		this.baseUri = baseUri(application);
	}

	static BackendFixture start() {
		return start(() -> new SpringApplicationBuilder(SkywrightBackendApplication.class));
	}

	static BackendFixture startWithTargetStorageIntegration() {
		return start(() -> new SpringApplicationBuilder(SkywrightBackendApplication.class,
				TargetStorageIntegrationTestConfiguration.class, DatasetPublicationCommitGateTestConfiguration.class)
			.profiles("target-storage-integration"));
	}

	static BackendFixture start(BuildProperties buildProperties) {
		return start(() -> new SpringApplicationBuilder(SkywrightBackendApplication.class).initializers(
				application -> application.getBeanFactory().registerSingleton("buildProperties", buildProperties)));
	}

	private static BackendFixture start(Supplier<SpringApplicationBuilder> builder) {
		try {
			var database = PostgreSqlFixture.freshDatabase();
			try {
				var application = run(builder.get(), database);
				return new BackendFixture(application, database, builder, true);
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

	private static ConfigurableApplicationContext run(SpringApplicationBuilder builder,
			PostgreSqlFixture.Database database) {
		var properties = new java.util.ArrayList<>(java.util.List.of(database.springProperties()));
		properties.add("server.port=0");
		properties.add("skywright.deployment.environment=test");
		var arguments = properties.stream().map(property -> "--" + property).toArray(String[]::new);
		return builder.web(WebApplicationType.SERVLET).run(arguments);
	}

	BackendFixture restartWithTargetStorageIntegration() {
		this.application.close();
		Supplier<SpringApplicationBuilder> restartedBuilder = () -> new SpringApplicationBuilder(
				SkywrightBackendApplication.class,
						TargetStorageIntegrationTestConfiguration.class,
						DatasetPublicationCommitGateTestConfiguration.class)
			.profiles("target-storage-integration");
		BackendFixture restarted = new BackendFixture(run(restartedBuilder.get(), this.database), this.database,
				restartedBuilder, true);
		this.ownsDatabase = false;
		return restarted;
	}

	BackendFixture peerWithTargetStorageIntegration() {
		Supplier<SpringApplicationBuilder> peerBuilder = () -> new SpringApplicationBuilder(
				SkywrightBackendApplication.class,
						TargetStorageIntegrationTestConfiguration.class,
						DatasetPublicationCommitGateTestConfiguration.class)
			.profiles("target-storage-integration");
		return new BackendFixture(run(peerBuilder.get(), this.database), this.database, peerBuilder, false);
	}

	private static URI baseUri(ConfigurableApplicationContext application) {
		var server = (WebServerApplicationContext) application;
		return URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
	}

	void restart() {
		this.application.close();
		this.application = run(this.builder.get(), this.database);
		this.baseUri = baseUri(this.application);
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
		this.application.close();
		if (this.ownsDatabase) {
			this.database.close();
		}
	}

}
