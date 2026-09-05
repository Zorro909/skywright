package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.credential.VaultBindings;
import de.zorro909.skywright.backend.credential.VaultRoleAccess;
import org.springframework.beans.factory.ObjectProvider;
import de.zorro909.skywright.backend.configurationcontract.ConfigurationContracts;
import de.zorro909.skywright.backend.metriccontract.MetricContracts;
import de.zorro909.skywright.backend.projectversion.GhcrProjectVersionRegistry;
import de.zorro909.skywright.backend.projectversion.ProjectVersionRegistry;
import de.zorro909.skywright.backend.projectversion.RegistryAuthorization;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TrainingProjectConfiguration {

	@Bean
	TrainingProjects trainingProjects(TrainingProjectRepository repository,
			TrainingProjectCredentialReadiness credentialReadiness, TrainingProjectVersions versions) {
		return new TrainingProjects(repository, credentialReadiness, versions);
	}

	@Bean
	@ConditionalOnMissingBean(TrainingProjectCredentialReadiness.class)
	TrainingProjectCredentialReadiness missingTrainingProjectCredentialReadiness(ObjectProvider<VaultBindings> vault) {
		var bindings = vault.getIfAvailable();
		return bindings == null ? (bindingId, consumingRole, repository) -> RegistryReadiness.MISSING
				: new VaultRoleAccess(bindings)::readiness;
	}

	@Bean
	@ConditionalOnMissingBean(TrainingProjectArtifactReferences.class)
	TrainingProjectArtifactReferences noReferencedTrainingProjectArtifacts() {
		return projectId -> java.util.Set.of();
	}

	@Bean
	@ConditionalOnMissingBean(ProjectVersionRegistry.class)
	ProjectVersionRegistry ghcrProjectVersionRegistry(RegistryAuthorization authorization) {
		return new GhcrProjectVersionRegistry(authorization);
	}

	@Bean
	@ConditionalOnMissingBean(RegistryAuthorization.class)
	RegistryAuthorization publicRegistryAuthorization(TrainingProjectRepository projects,
			ObjectProvider<VaultBindings> vault) {
		var bindings = vault.getIfAvailable();
		return bindings == null ? repository -> java.util.Optional.empty()
				: new VaultRegistryAuthorization(projects, bindings);
	}

	@Bean
	TrainingProjectVersions trainingProjectVersions(ProjectVersionRegistry registry) {
		return new TrainingProjectVersions(registry, new ConfigurationContracts(), new MetricContracts());
	}

	@Bean
	RegistryRebindings registryRebindings(TrainingProjectRepository repository,
			TrainingProjectArtifactReferences references, ProjectVersionRegistry registry,
			TrainingProjectCredentialReadiness credentialReadiness) {
		return new RegistryRebindings(repository, references, registry, credentialReadiness);
	}

}
