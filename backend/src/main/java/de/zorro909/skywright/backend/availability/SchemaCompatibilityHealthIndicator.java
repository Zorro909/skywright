package de.zorro909.skywright.backend.availability;

import de.zorro909.skywright.backend.persistence.migration.LiquibaseSchemaCompatibility;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("schemaCompatibility")
final class SchemaCompatibilityHealthIndicator implements HealthIndicator {

	private final EntityManagerFactory entityManagerFactory;

	private final LiquibaseSchemaCompatibility liquibaseSchemaCompatibility;

	SchemaCompatibilityHealthIndicator(EntityManagerFactory entityManagerFactory,
			LiquibaseSchemaCompatibility liquibaseSchemaCompatibility) {
		this.entityManagerFactory = entityManagerFactory;
		this.liquibaseSchemaCompatibility = liquibaseSchemaCompatibility;
	}

	@Override
	public Health health() {
		try {
			entityManagerFactory.getSchemaManager().validate();
			liquibaseSchemaCompatibility.validate();
			return Health.up().build();
		}
		catch (Exception exception) {
			return Health.down(exception).build();
		}
	}

}
