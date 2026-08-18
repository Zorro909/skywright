package de.zorro909.skywright.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PersistenceArchitectureTest {

	@Test
	void applicationPersistenceStaysInsideTheJpaBoundary() {
		var applicationClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("de.zorro909.skywright.backend");

		noClasses().that()
			.resideOutsideOfPackages("..persistence.migration..", "..persistence.failure..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
			.because(
					"application persistence uses JPA; JDBC is reserved for Liquibase infrastructure and test harnesses")
			.check(applicationClasses);
	}

	@Test
	void applicationPersistenceDoesNotDeclareNativeQueries() throws Exception {
		var forbiddenNativeSqlApis = java.util.List.of("nativeQuery", "createNative", "NamedNativeQuery", "SQLDelete",
				"SQLInsert", "SQLUpdate", "SQLRestriction", "SQLJoinTableRestriction", "ColumnTransformer", "Formula",
				"JoinFormula", "Subselect");
		try (var sources = Files.walk(Path.of("src/main/java"))) {
			var sourceText = sources.filter(path -> path.toString().endsWith(".java"))
				.map(PersistenceArchitectureTest::readSource)
				.toList();

			org.assertj.core.api.Assertions.assertThat(sourceText)
				.allSatisfy(source -> org.assertj.core.api.Assertions.assertThat(forbiddenNativeSqlApis)
					.noneMatch(source::contains));
		}
	}

	private static String readSource(Path source) {
		try {
			return Files.readString(source);
		}
		catch (java.io.IOException exception) {
			throw new IllegalStateException("Could not inspect " + source, exception);
		}
	}

}
