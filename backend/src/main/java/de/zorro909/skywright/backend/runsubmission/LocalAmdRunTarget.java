package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.rundefinition.EligibleTarget;
import de.zorro909.skywright.backend.runtimeassembly.LocalRuntimeProjection;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class LocalAmdRunTarget implements ManagedRunTarget {

	private final LocalRunTargetSettings settings;

	private final RuntimePullDelivery pulls;

	LocalAmdRunTarget(LocalRunTargetSettings settings, RuntimePullDelivery pulls) {
		this.settings = settings;
		this.pulls = pulls;
	}

	public String identity() {
		return settings.identity();
	}

	public String acceleratorBackend() {
		return "rocm";
	}

	public TargetClass targetClass(int count) {
		return count == 1 ? TargetClass.LOCAL_SINGLE_GPU : TargetClass.LOCAL_MULTI_GPU;
	}

	private LocalRuntimeProjection.Target localTarget() {
		return settings.target(identity());
	}

	public String pullNamespace(boolean privateImage) {
		return privateImage ? pulls.namespace(settings.kubernetesContext()) : null;
	}

	public de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification project(
			de.zorro909.skywright.backend.rundefinition.RunDefinition definition,
			de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials materials, boolean privateImage) {
		return new LocalRuntimeProjection().project(definition, materials, localTarget(),
				privateImage ? "skywright-pull-" + materials.runId() : null, pullNamespace(privateImage),
				settings.writerAuthorityEnabled());
	}

	public EligibleTarget eligible(int count) {
		var target = localTarget();
		return new EligibleTarget(identity(), targetClass(count), acceleratorBackend(), target.gpuModel(),
				target.maximumGpuCount(), target.gpuMemoryBytes());
	}

	public void requireReady(int count) {
		localTarget();
		if (!settings.writerAuthorityEnabled())
			return;
		var observed = readiness();
		if (!observed.available() || !observed.nodeReady() || !observed.writerReady())
			throw new RunSubmissionException("WRITER_AUTHORITY_UNAVAILABLE", 503);
		if (!observed.gpuModel().equals(settings.gpuModel()) || observed.gpuCount() != settings.maximumGpuCount())
			throw new RunSubmissionException("LOCAL_TARGET_UNAVAILABLE", 503);
		if (observed.freeGpuCount() < count)
			throw new RunSubmissionException("GPU_CAPACITY_UNAVAILABLE", 503);
	}

	public List<ManagedRunForms.Check> checks() {
		var observed = readiness();
		boolean gpu = observed.available() && observed.nodeReady() && observed.gpuModel().equals(settings.gpuModel())
				&& observed.gpuCount() == settings.maximumGpuCount() && observed.freeGpuCount() >= 1;
		boolean writer = settings.writerAuthorityEnabled() && observed.writerReady();
		return List.of(
				new ManagedRunForms.Check("gpu", gpu, gpu ? "READY" : "GPU_CAPACITY_UNAVAILABLE",
						"Check the AMD device plugin, finish external GPU work and wait for fresh host readiness."),
				new ManagedRunForms.Check("writerAuthority", writer, writer ? "READY" : "WRITER_AUTHORITY_UNAVAILABLE",
						"Check the local writer authority's readiness and retained custody."));
	}

	private RuntimePullDelivery.Readiness readiness() {
		try {
			return pulls.readiness(settings.kubernetesContext());
		}
		catch (RuntimeException unavailable) {
			return new RuntimePullDelivery.Readiness(false, false, "", 0, 0, false);
		}
	}

}
