package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class DatabaseStartupIT {

	@Test
	void executableMigratesTheSkywrightSchemaBeforeBecomingReady() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));

				assertThat(database.countTables("skywright")).as(backend.output()).isEqualTo(24);
				assertThat(database.countTables("public")).isZero();
			}
		}
	}

	@Test
	void completeChangelogRollsBackAndReappliesOnPostgreSql18() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			var arguments = arguments(database, port);
			var rollbackArguments = java.util.Arrays.copyOf(arguments, arguments.length + 1);
			rollbackArguments[arguments.length] = "--spring.liquibase.test-rollback-on-update=true";
			try (var backend = BackendProcess.start(rollbackArguments)) {
				try {
					BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
				}
				catch (AssertionError failure) {
					throw new AssertionError(failure.getMessage() + "\n" + backend.output(), failure);
				}

				assertThat(database.countTables("skywright")).as(backend.output()).isEqualTo(24);
			}
		}
	}

	@Test
	void immediatelyPrecedingDatabaseReleaseUpgradesInPlace() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var oldPort = BackendProcess.availablePort();
			var oldArguments = arguments(database, oldPort);
			oldArguments = java.util.Arrays.copyOf(oldArguments, oldArguments.length + 2);
			oldArguments[oldArguments.length
					- 2] = "--spring.liquibase.change-log=classpath:/db/changelog/previous-database-release.yaml";
			oldArguments[oldArguments.length - 1] = "--spring.jpa.hibernate.ddl-auto=none";
			try (var backend = BackendProcess.start(oldArguments)) {
				awaitLiveness(backend, oldPort, Duration.ofSeconds(30));
				assertThat(database.countTables("skywright")).as(backend.output()).isEqualTo(8);
			}

			var upgradedPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, upgradedPort))) {
				BackendProcess.awaitReadiness(upgradedPort, Duration.ofSeconds(30));
				assertThat(database.countTables("skywright")).as(backend.output()).isEqualTo(24);
			}
		}
	}

	private void awaitLiveness(BackendProcess backend, int port, Duration timeout) throws Exception {
		var deadline = java.time.Instant.now().plus(timeout);
		while (java.time.Instant.now().isBefore(deadline)) {
			try {
				if (get(port, "/livez").statusCode() == 200) {
					return;
				}
			}
			catch (java.io.IOException ignored) {
				// The old control plane has not bound its HTTP socket yet.
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("Backend did not become live within " + timeout + "\n" + backend.output());
	}

	@Test
	void missingDatabaseConfigurationStopsStartup() throws Exception {
		try (var backend = BackendProcess.start("--server.port=0", "--skywright.deployment.environment=test")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).contains("SKYWRIGHT_DATABASE_MIGRATION_URL")
				.doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void unavailableDatabaseStopsStartup() throws Exception {
		var unavailablePort = BackendProcess.availablePort();
		var jdbcUrl = "jdbc:postgresql://127.0.0.1:" + unavailablePort + "/skywright";
		try (var backend = BackendProcess.start("--server.port=0", "--skywright.deployment.environment=test",
				"--spring.datasource.url=" + jdbcUrl, "--spring.datasource.username=runtime",
				"--spring.datasource.password=runtime", "--spring.liquibase.url=" + jdbcUrl,
				"--spring.liquibase.user=migrator", "--spring.liquibase.password=migrator")) {
			var exitCode = backend.awaitExit(Duration.ofSeconds(20));

			assertThat(exitCode).isNotZero();
			assertThat(backend.output()).doesNotContain("Started SkywrightBackendApplication");
		}
	}

	@Test
	void runtimeRoleCannotAlterTheSchema() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));

				assertThatThrownBy(() -> database.executeAsRuntime("CREATE TABLE skywright.forbidden(id uuid)"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
			}
		}
	}

	@Test
	void runtimeRoleCannotModifyMigrationHistory() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));

				assertThatThrownBy(() -> database.executeAsRuntime("DELETE FROM skywright.databasechangelog"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
			}
		}
	}

	@Test
	void changedAppliedChangesetStopsStartup() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var firstPort = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, firstPort))) {
				BackendProcess.awaitReadiness(firstPort, Duration.ofSeconds(30));
			}
			database.executeAsMigrator("UPDATE skywright.databasechangelog SET md5sum = '9:invalid' "
					+ "WHERE id = 'issue-79-persistence-foundation'");

			try (var backend = BackendProcess.start(arguments(database, BackendProcess.availablePort()))) {
				var exitCode = backend.awaitExit(Duration.ofSeconds(20));

				assertThat(exitCode).isNotZero();
				assertThat(backend.output()).containsIgnoringCase("validation failed")
					.doesNotContain("Started SkywrightBackendApplication");
			}
		}
	}

	@Test
	void incompatibleMigrationHistoryRemovesReadinessAtRuntime() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
				var validChecksum = database.migrationChecksum();
				database.executeAsMigrator("UPDATE skywright.databasechangelog SET md5sum = '9:invalid' "
						+ "WHERE id = 'issue-79-persistence-foundation'");

				assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
				assertThat(get(port, "/readyz").statusCode()).isEqualTo(503);
				assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(503);

				database.executeAsMigrator("UPDATE skywright.databasechangelog SET md5sum = '" + validChecksum
						+ "' WHERE id = 'issue-79-persistence-foundation'");
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
			}
		}
	}

	@Test
	void runtimeOutageKeepsLivenessButRemovesReadinessUntilRecovery() throws Exception {
		try (var database = PostgreSqlFixture.freshDatabase()) {
			var port = BackendProcess.availablePort();
			try (var backend = BackendProcess.start(arguments(database, port))) {
				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
				PostgreSqlFixture.pause();
				try {
					assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
					assertThat(get(port, "/readyz").statusCode()).isEqualTo(503);
					assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(503);
				}
				finally {
					PostgreSqlFixture.unpause();
				}

				BackendProcess.awaitReadiness(port, Duration.ofSeconds(30));
				assertThat(backend.isAlive()).isTrue();
			}
		}
	}

	private String[] arguments(PostgreSqlFixture.Database database, int port) {
		var databaseArguments = database.backendArguments();
		var arguments = new String[databaseArguments.size() + 2];
		arguments[0] = "--server.port=" + port;
		arguments[1] = "--skywright.deployment.environment=test";
		for (var index = 0; index < databaseArguments.size(); index++) {
			arguments[index + 2] = databaseArguments.get(index);
		}
		return arguments;
	}

	private HttpResponse<String> get(int port, String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

}
