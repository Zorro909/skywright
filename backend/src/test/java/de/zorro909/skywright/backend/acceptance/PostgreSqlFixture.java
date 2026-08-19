package de.zorro909.skywright.backend.acceptance;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.slf4j.LoggerFactory;

final class PostgreSqlFixture {

	private static final String SCHEMA = "skywright";

	private static final PostgreSQLContainer POSTGRESQL = startPostgreSql();

	private PostgreSqlFixture() {
	}

	static Database freshDatabase() throws SQLException {
		var suffix = UUID.randomUUID().toString().replace("-", "");
		var database = "skywright_" + suffix;
		var migrator = "migrator_" + suffix;
		var runtime = "runtime_" + suffix;
		var migratorPassword = UUID.randomUUID().toString();
		var runtimePassword = UUID.randomUUID().toString();
		try (var connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(),
				POSTGRESQL.getPassword()); var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE " + migrator + " LOGIN PASSWORD '" + migratorPassword + "'");
			statement.execute("CREATE ROLE " + runtime + " LOGIN PASSWORD '" + runtimePassword + "'");
			statement.execute("CREATE DATABASE " + database + " OWNER " + migrator);
		}
		var jdbcUrl = "jdbc:postgresql://" + POSTGRESQL.getHost() + ":" + POSTGRESQL.getMappedPort(5432) + "/"
				+ database + "?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true";
		try (var connection = DriverManager.getConnection(jdbcUrl, migrator, migratorPassword);
				var statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION " + migrator);
			statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + runtime);
		}
		return new Database(database, jdbcUrl, migrator, migratorPassword, runtime, runtimePassword);
	}

	static void pause() {
		POSTGRESQL.getDockerClient().pauseContainerCmd(POSTGRESQL.getContainerId()).exec();
	}

	static void unpause() {
		RuntimeException lastFailure = null;
		for (var attempt = 0; attempt < 5; attempt++) {
			try {
				var paused = POSTGRESQL.getDockerClient()
					.inspectContainerCmd(POSTGRESQL.getContainerId())
					.exec()
					.getState()
					.getPaused();
				if (!Boolean.TRUE.equals(paused)) {
					return;
				}
				POSTGRESQL.getDockerClient().unpauseContainerCmd(POSTGRESQL.getContainerId()).exec();
			}
			catch (RuntimeException exception) {
				lastFailure = exception;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while restoring PostgreSQL", exception);
			}
		}
		throw new IllegalStateException("Could not restore PostgreSQL after the outage test", lastFailure);
	}

	private static PostgreSQLContainer startPostgreSql() {
		var image = DockerImageName.parse(System.getProperty("postgresql.container.image"))
			.asCompatibleSubstituteFor("postgres");
		var postgresql = new PostgreSQLContainer(image).withDatabaseName("postgres")
			.withUsername("postgres")
			.withPassword(UUID.randomUUID().toString())
			.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("test.postgresql")));
		postgresql.start();
		return postgresql;
	}

	record Database(String databaseName, String jdbcUrl, String migrator, String migratorPassword, String runtime,
			String runtimePassword) implements AutoCloseable {

		List<String> backendArguments() {
			return List.of("--spring.datasource.url=" + jdbcUrl + "&currentSchema=" + SCHEMA,
					"--spring.datasource.username=" + runtime, "--spring.datasource.password=" + runtimePassword,
					"--spring.liquibase.url=" + jdbcUrl, "--spring.liquibase.user=" + migrator,
					"--spring.liquibase.password=" + migratorPassword);
		}

		String[] springProperties() {
			return backendArguments().stream().map(argument -> argument.substring(2)).toArray(String[]::new);
		}

		long countTables(String schema) throws SQLException {
			try (var connection = DriverManager.getConnection(jdbcUrl, migrator, migratorPassword);
					var statement = connection
						.prepareStatement("SELECT count(*) FROM information_schema.tables WHERE table_schema = ?")) {
				statement.setString(1, schema);
				try (var result = statement.executeQuery()) {
					result.next();
					return result.getLong(1);
				}
			}
		}

		String migrationChecksum(String changesetId) throws SQLException {
			try (var connection = DriverManager.getConnection(jdbcUrl, migrator, migratorPassword);
					var statement = connection
						.prepareStatement("SELECT md5sum FROM skywright.databasechangelog WHERE id = ?")) {
				statement.setString(1, changesetId);
				try (var result = statement.executeQuery()) {
					result.next();
					return result.getString(1);
				}
			}
		}

		void executeAsMigrator(String sql) throws SQLException {
			try (var connection = DriverManager.getConnection(jdbcUrl, migrator, migratorPassword);
					var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}

		void executeAsRuntime(String sql) throws SQLException {
			try (var connection = DriverManager.getConnection(jdbcUrl, runtime, runtimePassword);
					var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}

		@Override
		public void close() throws SQLException {
			try (var connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(),
					POSTGRESQL.getPassword()); var statement = connection.createStatement()) {
				statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
				statement.execute("DROP ROLE " + runtime);
				statement.execute("DROP ROLE " + migrator);
			}
		}

	}

}
