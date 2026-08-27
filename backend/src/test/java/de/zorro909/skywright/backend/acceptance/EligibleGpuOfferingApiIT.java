package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingCatalogue;
import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class EligibleGpuOfferingApiIT {

	@Test
	void catalogueCrudUsesRevisionsAndSurvivesRestart() throws Exception {
		try (var backend = BackendFixture.start()) {
			var created = backend.post("/api/v1/eligible-gpu-offerings", offering("H100", "first-class"));
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
			assertThat(created.body()).contains("\"revision\":1", "\"target\":\"nebius\"",
					"\"providerOfferingId\":\"gpu-h100-8\"", "\"region\":\"eu-north1\"",
					"\"instanceType\":\"gpu-h100-sxm\"", "\"gpuModel\":\"H100\"", "\"gpuCount\":8",
					"\"gpuMemoryBytes\":85899345920", "\"purchaseMode\":\"spot\"", "\"supportTier\":\"first-class\"");
			String offeringId = jsonString(created.body(), "id");
			var catalogue = backend.bean(EligibleGpuOfferingCatalogue.class);
			var eligible = catalogue
				.eligible(new TargetRequest(TargetClass.CLOUD_SPOT, 8, 85899345920L, "nebius", "H100", null));
			assertThat(eligible).singleElement().extracting(value -> value.id().toString()).isEqualTo(offeringId);
			assertThat(catalogue.assess().targets()).singleElement()
				.satisfies(target -> assertThat(target.offeringId().toString()).isEqualTo(offeringId));

			assertThat(backend.get("/api/v1/eligible-gpu-offerings").body()).contains(offeringId, "H100");
			assertThat(backend.get("/api/v1/eligible-gpu-offerings/" + offeringId).body()).contains(offeringId,
					"gpu-h100-8");

			var updated = backend.put("/api/v1/eligible-gpu-offerings/" + offeringId, update("A100", "compatible", 1));
			assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
			assertThat(updated.body()).contains("\"revision\":2", "\"gpuModel\":\"A100\"",
					"\"supportTier\":\"compatible\"");

			var stale = backend.put("/api/v1/eligible-gpu-offerings/" + offeringId, update("L40S", "compatible", 1));
			assertThat(stale.statusCode()).isEqualTo(409);
			assertThat(stale.body()).contains("SKYWRIGHT_GPU_OFFERING_REVISION_CONFLICT");
			assertThat(backend.get("/api/v1/eligible-gpu-offerings/" + offeringId).body()).contains("A100")
				.doesNotContain("L40S");

			backend.restart();
			assertThat(backend.get("/api/v1/eligible-gpu-offerings/" + offeringId).body()).contains(offeringId,
					"\"revision\":2", "A100");

			var staleDelete = backend.delete("/api/v1/eligible-gpu-offerings/" + offeringId + "?expectedRevision=1");
			assertThat(staleDelete.statusCode()).isEqualTo(409);
			assertThat(staleDelete.body()).contains("SKYWRIGHT_GPU_OFFERING_REVISION_CONFLICT");

			assertThat(
					backend.delete("/api/v1/eligible-gpu-offerings/" + offeringId + "?expectedRevision=2").statusCode())
				.isEqualTo(204);
			var missing = backend.get("/api/v1/eligible-gpu-offerings/" + offeringId);
			assertThat(missing.statusCode()).isEqualTo(404);
			assertThat(missing.body()).contains("SKYWRIGHT_GPU_OFFERING_NOT_FOUND");
		}
	}

	private static String offering(String gpuModel, String supportTier) {
		return """
				{
				  "targetClass": "cloud-spot",
				  "target": "nebius",
				  "providerOfferingId": "gpu-h100-8",
				  "region": "eu-north1",
				  "instanceType": "gpu-h100-sxm",
				  "gpuModel": "%s",
				  "gpuCount": 8,
				  "gpuMemoryBytes": 85899345920,
				  "purchaseMode": "spot",
				  "supportTier": "%s"
				}
				""".formatted(gpuModel, supportTier);
	}

	private static String update(String gpuModel, String supportTier, long expectedRevision) {
		return offering(gpuModel, supportTier).replaceFirst("\\{", "{\"expectedRevision\":" + expectedRevision + ",");
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
