package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.boundary.generated.api.LocalRunTargetApi;
import de.zorro909.skywright.backend.boundary.generated.model.LocalRunTarget;
import de.zorro909.skywright.backend.orchestration.Orchestrator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalRunTargetHttpAdapter implements LocalRunTargetApi {

	private final LocalRunTargetSettings settings;

	private final Orchestrator orchestrator;

	LocalRunTargetHttpAdapter(LocalRunTargetSettings settings, Orchestrator orchestrator) {
		this.settings = settings;
		this.orchestrator = orchestrator;
	}

	@Override
	public ResponseEntity<LocalRunTarget> getLocalRunTarget() {
		var target = settings.target(settings.identity());
		boolean available = false;
		try {
			available = orchestrator.refreshAvailability().toCompletableFuture().get(5, TimeUnit.SECONDS).available();
		}
		catch (Exception failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
		}
		return ResponseEntity.ok(new LocalRunTarget().identity(target.identity())
			.gpuModel(target.gpuModel())
			.maximumGpuCount(target.maximumGpuCount())
			.gpuMemoryBytes(target.gpuMemoryBytes())
			.submissionAvailable(available)
			.observedAt(OffsetDateTime.now(ZoneOffset.UTC)));
	}

}
