package de.zorro909.skywright.backend.runsubmission;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import de.zorro909.skywright.backend.trainingproject.TrainingProjects;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCopyAvailability;
import de.zorro909.skywright.backend.datasetcatalog.DatasetCopyRole;
import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import de.zorro909.skywright.backend.credential.VaultBindings;
import de.zorro909.skywright.backend.orchestration.Orchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Read-only admission evidence; never accepts intent or acquires Dataset leases. */
@Component
final class ManagedRunForms {

	private final DemonstrationSettings demonstration;

	private final LocalRunTargetSettings target;

	private final ManagedRunTargets adapters;

	private final TrainingProjects projects;

	private final DatasetCatalog datasets;

	private final TargetStorageRegistry storages;

	private final ObjectProvider<VaultBindings> vault;

	private final RuntimePullDelivery pulls;

	private final Orchestrator orchestrator;

	private final java.util.concurrent.ThreadPoolExecutor readers = new java.util.concurrent.ThreadPoolExecutor(2, 2,
			60, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
			Thread.ofPlatform().daemon().name("managed-form-", 0).factory());

	ManagedRunForms(DemonstrationSettings demonstration, LocalRunTargetSettings target, TrainingProjects projects,
			DatasetCatalog datasets, TargetStorageRegistry storages, ObjectProvider<VaultBindings> vault,
			RuntimePullDelivery pulls, Orchestrator orchestrator, ManagedRunTargets adapters) {
		this.adapters = adapters;
		this.demonstration = demonstration;
		this.target = target;
		this.projects = projects;
		this.datasets = datasets;
		this.storages = storages;
		this.vault = vault;
		this.pulls = pulls;
		this.orchestrator = orchestrator;
	}

	record Check(String component, boolean ready, String code, String detail) {
	}

	record Workload(String id, String displayName, java.util.UUID trainingProjectId, String manifestArtifactDigest,
			java.util.UUID datasetDefinitionId) {
	}

	record Target(String id, String displayName, String purchaseMode, String gpuModel, Integer gpuCount, boolean ready,
			List<Check> checks) {
	}

	record Form(boolean ready, Instant observedAt, List<Workload> workloads, List<Target> targets, List<Check> checks) {
	}

	Form read() {
		if (!demonstration.installed())
			return inspect();
		java.util.concurrent.Future<Form> pending;
		try {
			pending = readers.submit(this::inspect);
		}
		catch (java.util.concurrent.RejectedExecutionException busy) {
			throw new RunSubmissionException("ADMISSION_FORM_BUSY", 429);
		}
		try {
			return pending.get(15, TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			pending.cancel(true);
			return unavailable("PREFLIGHT_INTERRUPTED", "Readiness was interrupted. Retry the installation check.");
		}
		catch (java.util.concurrent.TimeoutException timeout) {
			pending.cancel(true);
		}
		catch (java.util.concurrent.ExecutionException unavailable) {
			return unavailable("PREFLIGHT_UNAVAILABLE", "Readiness is unavailable. Check the installation services.");
		}
		return unavailable("PREFLIGHT_TIMEOUT",
				"Readiness could not be established within 15 seconds. Check the installation services and retry.");
	}

	private Form unavailable(String code, String detail) {
		return new Form(false, Instant.now(),
				List.of(new Workload("demonstration", demonstration.displayName(), demonstration.trainingProjectId(),
						demonstration.manifestArtifactDigest(), demonstration.datasetDefinitionId())),
				List.of(), List.of(new Check("preflight", false, code, detail)));
	}

	@jakarta.annotation.PreDestroy
	void stop() {
		readers.shutdownNow();
	}

	private Form inspect() {
		var checks = new ArrayList<Check>();
		var workloads = demonstration.installed()
				? List.of(new Workload("demonstration", demonstration.displayName(), demonstration.trainingProjectId(),
						demonstration.manifestArtifactDigest(), demonstration.datasetDefinitionId()))
				: List.<Workload>of();
		var targets = new ArrayList<Target>();
		checks.add(check("target", "TARGET_UNAVAILABLE", "Check the installed AMD target configuration.", () -> {
			var configured = target.target(target.identity());
			targets.add(new Target(configured.identity(), "Local AMD", "local", configured.gpuModel(),
					configured.maximumGpuCount(), false, List.of()));
			return true;
		}));
		if (!demonstration.installed()) {
			checks.add(new Check("workload", false, "WORKLOAD_NOT_INSTALLED",
					"Install the supplied demonstration Training Project Version and Dataset."));
			return joined(workloads, targets, checks);
		}
		checks.add(check("projectVersion", "PROJECT_VERSION_UNAVAILABLE",
				"Check the pinned project image, configuration contract and metric contract in the registry.",
				() -> projects.assessVersion(demonstration.trainingProjectId(), demonstration.manifestArtifactDigest())
					.runnable()));
		checks.add(check("dataset", "DATASET_UNAVAILABLE",
				"Publish and verify the installed Dataset on eligible storage.", () -> {
					var dataset = datasets.get(demonstration.datasetDefinitionId());
					return dataset.copies()
						.stream()
						.anyMatch(copy -> copy.role() == DatasetCopyRole.AUTHORITY
								&& copy.currentGeneration().availability() == DatasetCopyAvailability.AVAILABLE
								&& copy.currentGeneration().acceptingLeases()
								&& copy.currentGeneration().verifiedAt() != null
								&& copy.currentGeneration()
									.contentFingerprint()
									.equals(dataset.definition().contentFingerprint())
								&& copy.currentGeneration()
									.manifestIdentity()
									.equals(dataset.definition().manifestIdentity())
								&& storages.eligibleDataset(copy.targetStorageId()));
				}));
		checks.add(check("storage", "RUN_STORAGE_UNAVAILABLE",
				"Qualify separate Dataset and Run Store storage and assign local defaults.", () -> {
					var selected = storages.resolveForRunDefinition(TargetClass.LOCAL_SINGLE_GPU,
							new de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides(null, null,
									null));
					storages.trainingAccess(selected.execution().storageId(), false);
					return true;
				}));
		checks.add(check("credentials", "CREDENTIALS_UNAVAILABLE",
				"Check the exact installed Vault binding revisions and their expiry.", () -> {
					var bindings = vault.getIfAvailable();
					if (bindings == null || bindings.definitions().isEmpty())
						return false;
					var required = new java.util.HashSet<java.util.UUID>();
					var selected = storages.resolveForRunDefinition(TargetClass.LOCAL_SINGLE_GPU,
							new de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides(null, null,
									null));
					required.add(storages.trainingAccess(selected.execution().storageId(), false).bindingId());
					var dataset = datasets.get(demonstration.datasetDefinitionId());
					var authority = dataset.copies()
						.stream()
						.filter(copy -> copy.role() == DatasetCopyRole.AUTHORITY
								&& copy.currentGeneration().availability() == DatasetCopyAvailability.AVAILABLE
								&& storages.eligibleDataset(copy.targetStorageId()))
						.findFirst()
						.orElseThrow();
					required.add(storages.trainingAccess(authority.targetStorageId(), true).bindingId());
					var pull = projects.runtimePullSelection(demonstration.trainingProjectId());
					if (pull != null)
						required.add(pull.bindingId());
					for (var id : required) {
						var binding = bindings.definitions()
							.stream()
							.filter(value -> value.id().equals(id))
							.findFirst()
							.orElseThrow();
						if (Thread.currentThread().isInterrupted()
								|| bindings.readiness(binding.id(), binding.revision(), binding.role())
									.status() != VaultBindings.Status.READY)
							return false;
					}
					return true;
				}));
		checks.add(check("registry", "REGISTRY_PULL_UNAVAILABLE",
				"Check the project runtime-pull binding and target-side delivery helper.", () -> {
					projects.runtimePullSelection(demonstration.trainingProjectId());
					return !pulls.namespace(target.kubernetesContext()).isBlank();
				}));
		checks.addAll(adapters.local().checks());
		checks.add(check("controlPath", "CONTROL_PATH_UNAVAILABLE",
				"Check the SkyPilot service, its backend authorization and Kubernetes access.", () -> {
					try {
						return orchestrator.refreshAvailability()
							.toCompletableFuture()
							.get(5, TimeUnit.SECONDS)
							.available();
					}
					catch (InterruptedException failure) {
						Thread.currentThread().interrupt();
						return false;
					}
					catch (Exception failure) {
						return false;
					}
				}));
		return joined(workloads, targets, checks);
	}

	private Form joined(List<Workload> workloads, List<Target> localTargets, List<Check> checks) {
		var commonComponents = java.util.Set.of("workload", "projectVersion", "dataset", "controlPath");
		var common = checks.stream().filter(c -> commonComponents.contains(c.component())).toList();
		var localChecks = checks.stream().filter(c -> !commonComponents.contains(c.component())).toList();
		boolean commonReady = !workloads.isEmpty() && common.stream().allMatch(Check::ready);
		var targets = new ArrayList<Target>();
		for (var local : localTargets)
			targets.add(new Target(local.id(), local.displayName(), local.purchaseMode(), local.gpuModel(),
					local.gpuCount(), commonReady && localChecks.stream().allMatch(Check::ready), localChecks));
		var vast = adapters.select(VastOnDemandRunTarget.ID);
		targets.add(new Target(vast.identity(), "Vast.ai on-demand", "on-demand", null, null, false, vast.checks()));
		return new Form(targets.stream().anyMatch(Target::ready), Instant.now(), workloads, targets, common);
	}

	private static Check check(String component, String code, String detail, BooleanSupplier action) {
		boolean ready = false;
		try {
			if (!Thread.currentThread().isInterrupted())
				ready = action.getAsBoolean();
		}
		catch (RuntimeException unavailable) {
			/* Report the owning prerequisite without provider values. */ }
		return new Check(component, ready, ready ? "READY" : code, detail);
	}

}
