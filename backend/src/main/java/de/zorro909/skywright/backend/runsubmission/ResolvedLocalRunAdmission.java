package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.trainingproject.ReferencedProjectArtifact;

import de.zorro909.skywright.backend.credential.CredentialBinding;
import de.zorro909.skywright.backend.credential.LocalCredentialProjections;
import de.zorro909.skywright.backend.credential.VaultBindings;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.projectversion.ProjectVersionRegistry;
import de.zorro909.skywright.backend.projectversion.TrainingProjectBinding;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersions;
import de.zorro909.skywright.backend.rundefinition.CostQuoteReader;
import de.zorro909.skywright.backend.rundefinition.DatasetDefinitionAssessment;
import de.zorro909.skywright.backend.rundefinition.DatasetDefinitionReference;
import de.zorro909.skywright.backend.rundefinition.EligibleTarget;
import de.zorro909.skywright.backend.rundefinition.ReportingCurrencyReader;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionResolver;
import de.zorro909.skywright.backend.rundefinition.RunSubmission;
import de.zorro909.skywright.backend.rundefinition.TargetEligibilityAssessment;
import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.runtimeassembly.LocalRuntimeProjection;
import de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRunDefinitionReader;
import de.zorro909.skywright.backend.trainingproject.TrainingProjects;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
final class ResolvedLocalRunAdmission implements LocalRunAdmission {

	private final TrainingProjects projects;

	private final TrainingProjectVersions versions;

	private final ProjectVersionRegistry registry;

	private final DatasetCatalog datasets;

	private final TargetStorageRegistry storages;

	private final ReportingCurrencyReader currency;

	private final CostQuoteReader quotes;

	private final LocalRunTargetSettings settings;

	private final ObjectProvider<LocalCredentialProjections> projections;

	private final ObjectProvider<VaultBindings> vault;

	private final RuntimePullDelivery pulls;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	ResolvedLocalRunAdmission(TrainingProjects projects, TrainingProjectVersions versions,
			ProjectVersionRegistry registry, DatasetCatalog datasets, TargetStorageRegistry storages,
			ReportingCurrencyReader currency, CostQuoteReader quotes, LocalRunTargetSettings settings,
			ObjectProvider<LocalCredentialProjections> projections, ObjectProvider<VaultBindings> vault,
			RuntimePullDelivery pulls) {
		this.projects = projects;
		this.versions = versions;
		this.registry = registry;
		this.datasets = datasets;
		this.storages = storages;
		this.currency = currency;
		this.quotes = quotes;
		this.settings = settings;
		this.projections = projections;
		this.vault = vault;
		this.pulls = pulls;
	}

	@Override
	public Prepared prepare(UUID runId, LocalRunRequest request) {
		var target = settings.target(request.target());
		var project = projects.resolveForAcceptance(request.trainingProjectId());
		var pullSelection = projects.runtimePullSelection(project.projectId());
		String pullNamespace = pullSelection == null ? null : pulls.namespace(target.kubernetesContext());
		var dataset = datasets.get(request.datasetDefinitionId()).definition();
		var reference = new DatasetDefinitionReference(dataset.datasetId().toString(),
				dataset.definitionId().toString(), dataset.contentFingerprint());
		var targetClass = request.gpuCount() == 1 ? TargetClass.LOCAL_SINGLE_GPU : TargetClass.LOCAL_MULTI_GPU;
		var targetRequest = new TargetRequest(targetClass, request.gpuCount(), null, target.identity(),
				target.gpuModel(), null);
		var resolver = new RunDefinitionResolver(versions,
				selected -> selected.equals(reference) ? DatasetDefinitionAssessment.accepted()
						: new DatasetDefinitionAssessment(false, List.of()),
				() -> new TargetEligibilityAssessment(List.of(new EligibleTarget(target.identity(), targetClass, "rocm",
						target.gpuModel(), target.maximumGpuCount(), target.gpuMemoryBytes())), List.of()),
				new TargetStorageRunDefinitionReader(storages), currency, quotes);
		var resolution = resolver
			.resolve(new RunSubmission(new TrainingProjectBinding(project.projectId().toString(), project.repository()),
					request.manifestArtifactDigest(), JSON.writeValueAsString(request.configuration()), reference,
					targetRequest, new RunDefinitionStorageOverrides(request.executionStorageId(), null, null),
					request.maximumRecoveryDebt(), null, null, false), null);
		if (!resolution.accepted()) {
			boolean unavailable = resolution.failures().stream().anyMatch(f -> f.code().endsWith("_UNAVAILABLE"));
			throw new RunSubmissionException(unavailable ? "RUN_ADMISSION_UNAVAILABLE" : "RUN_DEFINITION_INVALID",
					unavailable ? 503 : 422, resolution.failures());
		}
		var definition = resolution.definition();
		var read = datasets.selectForRun(request.datasetDefinitionId(), dataset.contentFingerprint(), runId,
				request.preferredDatasetCopyId());
		var datasetAccess = storages.trainingAccess(read.targetStorageId(), true);
		var outputAccess = storages
			.trainingAccess(UUID.fromString(definition.value().at("/storage/execution/storageId").asText()), false);
		var pinnedOutput = definition.value().at("/storage/execution");
		if (outputAccess.storage().registrationRevision() != pinnedOutput.path("registrationRevision").asLong()
				|| outputAccess.storage().configurationRevision() != pinnedOutput.path("configurationRevision")
					.asLong())
			throw new RunSubmissionException("RUN_STORAGE_REVISION_CONFLICT", 409);
		if (datasetAccess.storage().endpoint().equals(outputAccess.storage().endpoint())
				&& datasetAccess.storage().bucket().equals(outputAccess.storage().bucket()))
			throw new RunSubmissionException("DATASET_RUN_STORE_NOT_ISOLATED", 422);

		var manifest = JSON.readTree(artifact(project.repository(), request.manifestArtifactDigest()));
		var contractReferences = manifest.at("/contractArtifacts/rocm");
		String configuration = artifact(project.repository(), contractReferences.path("configuration").asText());
		String metrics = artifact(project.repository(), contractReferences.path("metrics").asText());
		var location = datasetAccess.storage();
		var materials = new RuntimeMaterials(1, runId,
				project.repository() + "@" + definition.value().at("/trainingProjectVersion/images/rocm").asText(),
				configuration, metrics,
				new RuntimeMaterials.Dataset(reference.datasetIdentity(), reference.version(),
						reference.contentFingerprint(), dataset.manifestIdentity(),
						read.manifest()
							.stream()
							.map(item -> new RuntimeMaterials.DatasetObject(item.objectKey(), item.byteCount(),
									datasetDigest(item.checksumSha256())))
							.toList()),
				new RuntimeMaterials.DatasetLocation(location.storageId().toString(), location.endpoint().toString(),
						location.bucket(), location.region(), read.location(), read.lease().copyId().toString(),
						read.lease().generation(), read.lease().id().toString(), location.pathStyleAccess(),
						location.compatibilityOptions()
							.getOrDefault("checksumCalculation", "when-required")
							.replace('-', '_')),
				null);
		var task = new LocalRuntimeProjection().project(definition, materials, target,
				pullSelection == null ? null : "skywright-pull-" + runId, pullNamespace);
		var broker = projections.getIfAvailable();
		if (broker == null || vault.getIfAvailable() == null)
			throw new RunSubmissionException("TRAINING_CREDENTIALS_UNAVAILABLE", 503);
		projects.requireCurrentBindingRevision(project.projectId(), project.bindingRevision());
		var artifacts = new java.util.HashSet<ReferencedProjectArtifact>();
		artifacts.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.VERSION_MANIFEST,
				request.manifestArtifactDigest()));
		for (var image : definition.value().at("/trainingProjectVersion/images"))
			artifacts.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.IMAGE, image.asText()));
		for (var backend : manifest.path("contractArtifacts")) {
			artifacts.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.CONFIGURATION_CONTRACT,
					backend.path("configuration").asText()));
			artifacts.add(new ReferencedProjectArtifact(ReferencedProjectArtifact.Kind.METRIC_CONTRACT,
					backend.path("metrics").asText()));
		}

		try {
			var credentials = broker.training(runId, selection(datasetAccess, "read-only"),
					selection(outputAccess, "read-write-delete"), Instant.MAX);
			try {
				var pull = pullSelection == null ? null : broker.runtimePull(runId, pullSelection, Instant.MAX,
						java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
				return new Prepared(definition, task, credentials, artifacts, pull);
			}
			catch (RuntimeException failure) {
				credentials.close();
				throw failure;
			}
		}
		catch (IllegalArgumentException failure) {
			throw new RunSubmissionException("TRAINING_CREDENTIAL_ISOLATION_INVALID", 422);
		}
	}

	private static String datasetDigest(String base64) {
		byte[] bytes = java.util.Base64.getDecoder().decode(base64);
		if (bytes.length != 32)
			throw new RunSubmissionException("DATASET_MANIFEST_INVALID", 422);
		return "sha256:" + java.util.HexFormat.of().formatHex(bytes);
	}

	private String artifact(String repository, String digest) {
		var artifact = registry.pullArtifact(repository, digest)
			.orElseThrow(() -> new RunSubmissionException("PROJECT_ARTIFACT_UNAVAILABLE", 503));
		if (!digest.equals(artifact.manifestDigest()))
			throw new RunSubmissionException("PROJECT_ARTIFACT_DIGEST_MISMATCH", 422);
		return artifact.content();
	}

	private LocalCredentialProjections.Selection selection(TargetStorageRegistry.TrainingAccess access,
			String profile) {
		var binding = vault.getObject()
			.definitions()
			.stream()
			.filter(value -> value.id().equals(access.bindingId()) && value.revision() == access.bindingRevision()
					&& value.kind() == CredentialBinding.Kind.S3 && value.role().equals("training-process")
					&& value.accessProfile().equals(profile))
			.findFirst()
			.orElseThrow(() -> new RunSubmissionException("TRAINING_CREDENTIALS_UNAVAILABLE", 503));
		return new LocalCredentialProjections.Selection(binding.id(), binding.resource(), profile);
	}

}
