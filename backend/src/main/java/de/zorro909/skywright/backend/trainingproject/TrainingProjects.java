package de.zorro909.skywright.backend.trainingproject;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

import de.zorro909.skywright.backend.projectversion.ProjectVersionAssessment;
import de.zorro909.skywright.backend.projectversion.ProjectVersionDiscovery;
import de.zorro909.skywright.backend.projectversion.TrainingProjectBinding;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersions;

@Transactional
public class TrainingProjects {

	private static final Pattern REPOSITORY = Pattern
		.compile("ghcr\\.io/[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*/[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*");

	private final TrainingProjectRepository repository;

	private final TrainingProjectCredentialReadiness credentialReadiness;

	private final TrainingProjectVersions versions;

	TrainingProjects(TrainingProjectRepository repository, TrainingProjectCredentialReadiness credentialReadiness,
			TrainingProjectVersions versions) {
		this.repository = repository;
		this.credentialReadiness = credentialReadiness;
		this.versions = versions;
	}

	public UUID create(String displayName, String registryRepository, RegistryAccessMode accessMode,
			UUID resolverCredentialBindingId, UUID executionCredentialBindingId) {
		String name = normalizeName(displayName);
		String canonicalRepository = requireRepository(registryRepository);
		requireBindingShape(accessMode, resolverCredentialBindingId, executionCredentialBindingId);
		if (this.repository.nameExists(name, null)) {
			throw new TrainingProjectException("TRAINING_PROJECT_NAME_CONFLICT",
					"A Training Project already uses that display name.");
		}
		if (this.repository.repositoryExists(canonicalRepository)) {
			throw new TrainingProjectException("TRAINING_PROJECT_REPOSITORY_CONFLICT",
					"The repository is already bound to a Training Project.");
		}
		UUID id = UUID.randomUUID();
		RegistryBinding binding = new RegistryBinding(1, canonicalRepository, accessMode, resolverCredentialBindingId,
				executionCredentialBindingId,
				readiness(canonicalRepository, accessMode, resolverCredentialBindingId, executionCredentialBindingId),
				"active");
		this.repository.create(TrainingProjectEntity.create(id, name, binding));
		return id;
	}

	@Transactional(readOnly = true)
	public TrainingProjectView get(UUID id) {
		return currentView(project(id));
	}

	@Transactional(readOnly = true)
	public List<TrainingProjectView> list() {
		return this.repository.findAll().stream().map(this::currentView).toList();
	}

	public void rename(UUID id, long expectedRevision, String displayName) {
		TrainingProjectEntity project = project(id);
		requireRevision(project, expectedRevision);
		String name = normalizeName(displayName);
		if (this.repository.nameExists(name, id)) {
			throw new TrainingProjectException("TRAINING_PROJECT_NAME_CONFLICT",
					"A Training Project already uses that display name.");
		}
		project.displayName = name;
		project.revision++;
	}

	public void replaceCredentials(UUID id, long expectedRevision, UUID resolverCredentialBindingId,
			UUID executionCredentialBindingId) {
		TrainingProjectEntity project = project(id);
		requireRevision(project, expectedRevision);
		if (this.repository.hasActiveOperation(id)) {
			throw new TrainingProjectException("REGISTRY_REBINDING_CONFLICT",
					"Registry credentials cannot change while a Registry Rebinding Operation is active.");
		}
		RegistryBinding active = project.view().activeBinding();
		requireBindingShape(active.accessMode(), resolverCredentialBindingId, executionCredentialBindingId);
		project.replaceActiveBinding(
				new RegistryBinding(active.revision() + 1, active.repository(), active.accessMode(),
						resolverCredentialBindingId, executionCredentialBindingId, readiness(active.repository(),
								active.accessMode(), resolverCredentialBindingId, executionCredentialBindingId),
						"active"));
		project.revision++;
	}

	@Transactional(readOnly = true)
	public ProjectVersionDiscovery discoverVersions(UUID id) {
		TrainingProjectView project = get(id);
		RegistryBinding binding = requireReady(project.activeBinding());
		return this.versions
			.discoverAvailable(new TrainingProjectBinding(project.id().toString(), binding.repository()));
	}

	@Transactional(readOnly = true)
	public ProjectVersionAssessment assessVersion(UUID id, String manifestDigest) {
		TrainingProjectView project = get(id);
		RegistryBinding binding = requireReady(project.activeBinding());
		return this.versions.discover(new TrainingProjectBinding(project.id().toString(), binding.repository()),
				manifestDigest);
	}

	@Transactional(readOnly = true)
	public ResolvedTrainingProjectBinding resolveForNewWork(UUID id) {
		TrainingProjectView project = get(id);
		RegistryBinding binding = requireReady(project.activeBinding());
		return new ResolvedTrainingProjectBinding(project.id(), binding.revision(), binding.repository());
	}

	@Transactional(readOnly = true)
	public void requireCurrentBindingRevision(UUID id, long bindingRevision) {
		if (get(id).activeBinding().revision() != bindingRevision) {
			throw new TrainingProjectException("TRAINING_PROJECT_BINDING_CHANGED",
					"The active registry binding changed; resolve the Training Project again.");
		}
	}

	private RegistryReadiness readiness(String repository, RegistryAccessMode accessMode, UUID resolver,
			UUID execution) {
		if (accessMode == RegistryAccessMode.PUBLIC) {
			return RegistryReadiness.READY;
		}
		if (resolver == null || execution == null) {
			return RegistryReadiness.MISSING;
		}
		RegistryReadiness resolverReadiness = this.credentialReadiness.readiness(resolver, "backend-resolver",
				repository);
		RegistryReadiness executionReadiness = this.credentialReadiness.readiness(execution, "execution-target-pull",
				repository);
		return resolverReadiness == RegistryReadiness.READY ? executionReadiness : resolverReadiness;
	}

	private TrainingProjectView currentView(TrainingProjectEntity entity) {
		TrainingProjectView stored = entity.view();
		List<RegistryBinding> history = stored.bindingHistory().stream().map(binding -> {
			if ("retired".equals(binding.state())) {
				return binding;
			}
			return new RegistryBinding(binding.revision(), binding.repository(), binding.accessMode(),
					binding.resolverCredentialBindingId(), binding.executionCredentialBindingId(),
					readiness(binding.repository(), binding.accessMode(), binding.resolverCredentialBindingId(),
							binding.executionCredentialBindingId()),
					binding.state());
		}).toList();
		RegistryBinding active = history.stream()
			.filter(binding -> "active".equals(binding.state()))
			.findFirst()
			.orElseThrow();
		return new TrainingProjectView(stored.id(), stored.displayName(), stored.revision(), active, history);
	}

	private static RegistryBinding requireReady(RegistryBinding binding) {
		if (binding.readiness() != RegistryReadiness.READY) {
			throw new TrainingProjectException("TRAINING_PROJECT_CREDENTIALS_UNAVAILABLE",
					"The active registry binding's Credential Bindings are not ready.");
		}
		return binding;
	}

	private TrainingProjectEntity project(UUID id) {
		return this.repository.find(Objects.requireNonNull(id, "projectId"))
			.orElseThrow(() -> new TrainingProjectException("TRAINING_PROJECT_NOT_FOUND",
					"The Training Project does not exist."));
	}

	private static void requireRevision(TrainingProjectEntity project, long expectedRevision) {
		if (project.revision != expectedRevision) {
			throw new TrainingProjectException("TRAINING_PROJECT_REVISION_CONFLICT",
					"The Training Project changed; reload it and retry.");
		}
	}

	private static String normalizeName(String value) {
		if (value == null || value.trim().isEmpty() || value.trim().length() > 255) {
			throw new TrainingProjectException("TRAINING_PROJECT_INVALID",
					"displayName must contain 1 to 255 characters.");
		}
		return value.trim();
	}

	static String requireRepository(String value) {
		String repository = value == null ? "" : value.trim();
		if (!REPOSITORY.matcher(repository).matches() || !repository.equals(repository.toLowerCase(Locale.ROOT))) {
			throw new TrainingProjectException("TRAINING_PROJECT_INVALID",
					"repository must be a canonical lowercase GHCR repository without a tag or digest.");
		}
		return repository;
	}

	static void requireBindingShape(RegistryAccessMode mode, UUID resolver, UUID execution) {
		if (mode == null) {
			throw new TrainingProjectException("TRAINING_PROJECT_INVALID", "accessMode is required.");
		}
		if (mode == RegistryAccessMode.PUBLIC && (resolver != null || execution != null)) {
			throw new TrainingProjectException("TRAINING_PROJECT_INVALID",
					"Public registry bindings cannot include Credential Binding references.");
		}
		if (mode == RegistryAccessMode.PRIVATE && resolver != null && resolver.equals(execution)) {
			throw new TrainingProjectException("TRAINING_PROJECT_INVALID",
					"Private registry roles require distinct Credential Bindings.");
		}
	}

}
