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
					.displayName(w.displayName()))
				.toList())
			.targets(form.targets().stream().map(t -> new ManagedTarget(t.id(), t.gpuModel(), t.gpuCount())).toList())
			.checks(form.checks()
				.stream()
				.map(c -> new ManagedReadinessCheck(c.component(), c.ready(), c.code(), c.detail()))
				.toList()));
	}

}
