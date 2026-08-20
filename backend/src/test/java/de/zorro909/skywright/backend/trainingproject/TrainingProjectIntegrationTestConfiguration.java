package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.projectversion.ProjectVersionReference;
import de.zorro909.skywright.backend.projectversion.ProjectVersionRegistry;
import de.zorro909.skywright.backend.projectversion.RegistryArtifact;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("training-project-integration")
public class TrainingProjectIntegrationTestConfiguration {

	@Bean
	@Primary
	DeterministicRegistry deterministicProjectVersionRegistry() {
		return new DeterministicRegistry();
	}

	@Bean
	@Primary
	MutableArtifactReferences mutableArtifactReferences() {
		return new MutableArtifactReferences();
	}

	public static final class DeterministicRegistry implements ProjectVersionRegistry {

		private final Set<String> copied = ConcurrentHashMap.newKeySet();

		public void copy(String digest) {
			this.copied.add(digest);
		}

		@Override
		public List<ProjectVersionReference> listVersions(String repository) {
			if (repository.endsWith("/unavailable")) {
				throw new IllegalStateException("registry unavailable");
			}
			if (repository.endsWith("/empty")) {
				return List.of();
			}
			return List.of(new ProjectVersionReference("1".repeat(40) + "-github-81-1", "sha256:" + "9".repeat(64)));
		}

		@Override
		public Optional<RegistryArtifact> pullArtifact(String repository, String reference) {
			return this.copied.contains(reference) ? Optional.of(new RegistryArtifact(reference, "{}"))
					: Optional.empty();
		}

		@Override
		public boolean imageAvailable(String repository, String digest) {
			return this.copied.contains(digest);
		}

	}

	public static final class MutableArtifactReferences implements TrainingProjectArtifactReferences {

		private final Set<ReferencedProjectArtifact> references = ConcurrentHashMap.newKeySet();

		public void add(ReferencedProjectArtifact artifact) {
			this.references.add(artifact);
		}

		@Override
		public Set<ReferencedProjectArtifact> referencedArtifacts(UUID projectId) {
			return Set.copyOf(this.references);
		}

	}

}
