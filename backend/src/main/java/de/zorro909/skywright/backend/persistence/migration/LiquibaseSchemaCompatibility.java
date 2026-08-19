package de.zorro909.skywright.backend.persistence.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Validates the applied changelog through Liquibase without changing database state. */
@Component
public final class LiquibaseSchemaCompatibility {

	private final SpringLiquibase springLiquibase;

	private final ResourceLoader resourceLoader;

	private final String runtimeRole;

	LiquibaseSchemaCompatibility(SpringLiquibase springLiquibase, ResourceLoader resourceLoader,
			@Value("${spring.datasource.username}") String runtimeRole) {
		this.springLiquibase = springLiquibase;
		this.resourceLoader = resourceLoader;
		this.runtimeRole = runtimeRole;
	}

	public void validate() {
		try (var connection = springLiquibase.getDataSource().getConnection();
				var resourceAccessor = new SpringResourceAccessor(resourceLoader);
				var liquibase = new Liquibase(springLiquibase.getChangeLog(), resourceAccessor,
						new JdbcConnection(connection))) {
			var database = liquibase.getDatabase();
			database.setDefaultSchemaName(springLiquibase.getDefaultSchema());
			database.setLiquibaseSchemaName(springLiquibase.getLiquibaseSchema());
			liquibase.setChangeLogParameter("runtimeRole", this.runtimeRole);
			Scope.child(Scope.Attr.ui, new LoggerUIService(), () -> {
				liquibase.validate();
				if (!liquibase.listUnrunChangeSets(contexts(), labels()).isEmpty()) {
					throw new IllegalStateException("The database schema has pending migrations");
				}
			});
		}
		catch (Exception exception) {
			throw new IllegalStateException("The database schema is not compatible with this backend", exception);
		}
	}

	private Contexts contexts() {
		return springLiquibase.getContexts() == null ? new Contexts() : new Contexts(springLiquibase.getContexts());
	}

	private LabelExpression labels() {
		return springLiquibase.getLabelFilter() == null ? new LabelExpression()
				: new LabelExpression(springLiquibase.getLabelFilter());
	}

}
