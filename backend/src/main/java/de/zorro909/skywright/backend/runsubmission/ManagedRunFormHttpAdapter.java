package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.boundary.generated.api.ManagedRunFormApi;
import de.zorro909.skywright.backend.boundary.generated.model.ManagedRunForm;
import de.zorro909.skywright.backend.boundary.generated.model.ManagedReadinessCheck;
import de.zorro909.skywright.backend.boundary.generated.model.ManagedWorkload;
import de.zorro909.skywright.backend.boundary.generated.model.ManagedTarget;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManagedRunFormHttpAdapter implements ManagedRunFormApi {

	private final ManagedRuns runs;

	ManagedRunFormHttpAdapter(ManagedRuns runs) {
		this.runs = runs;
	}

	@Override
	public ResponseEntity<ManagedRunForm> getManagedRunForm() {
		var form = runs.form();
		return ResponseEntity.ok(new ManagedRunForm().ready(form.ready())
			.observedAt(form.observedAt().atOffset(ZoneOffset.UTC))
			.workloads(form.workloads()
				.stream()
				.map(w -> new ManagedWorkload().id(ManagedWorkload.IdEnum.fromValue(w.id()))
					.displayName(w.displayName())
					.trainingProjectId(w.trainingProjectId())
					.manifestArtifactDigest(w.manifestArtifactDigest())
					.datasetDefinitionId(w.datasetDefinitionId()))
				.toList())
			.targets(form.targets()
				.stream()
				.map(t -> new ManagedTarget().id(t.id())
					.displayName(t.displayName())
					.purchaseMode(ManagedTarget.PurchaseModeEnum.fromValue(t.purchaseMode()))
					.gpuModel(t.gpuModel())
					.gpuCount(t.gpuCount())
					.ready(t.ready())
					.checks(t.checks()
						.stream()
						.map(c -> new ManagedReadinessCheck(c.component(), c.ready(), c.code(), c.detail()))
						.toList()))
				.toList())
			.checks(form.checks()
				.stream()
				.map(c -> new ManagedReadinessCheck(c.component(), c.ready(), c.code(), c.detail()))
				.toList()));
	}

}
