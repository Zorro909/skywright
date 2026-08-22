package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.datasetcatalog.DatasetCatalog;
import de.zorro909.skywright.backend.datasetcatalog.DatasetPublication;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class DatasetCatalogApiIT {

	@Test
	void publishedCatalogFactsSurviveJpaAndAreQueryableThroughTheGeneratedApi() throws Exception {
		try (var backend = BackendFixture.start()) {
			var storage = backend.post("/api/v1/target-storages", registration());
			assertThat(storage.statusCode()).as(storage.body()).isEqualTo(201);
			UUID storageId = UUID.fromString(storage.body().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
			UUID definitionId = UUID.randomUUID();
			backend.bean(DatasetCatalog.class)
				.publish(new DatasetPublication(UUID.randomUUID(), definitionId, "release-1", "sha256:content",
						"sha256:manifest", UUID.randomUUID(), storageId, "datasets/release-1", 4096,
						Instant.parse("2026-08-22T10:00:00Z")));

			var record = backend.get("/api/v1/dataset-catalog/" + definitionId);
			var page = backend.get("/api/v1/dataset-catalog?limit=1&role=authority");

			assertThat(record.statusCode()).as(record.body()).isEqualTo(200);
			assertThat(record.body()).contains(definitionId.toString(), "release-1", "datasets/release-1",
					"\"role\":\"authority\"", "\"activeLeaseCount\":0");
			assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
			assertThat(page.body()).contains(definitionId.toString(), "\"nextCursor\":null");
		}
	}

	private static String registration() {
		return """
				{
				  "name": "Dataset authority",
				  "purpose": "dataset",
				  "bucket": "dataset-catalog-api",
				  "configuration": {
				    "endpoint": "http://127.0.0.1:9000",
				    "region": "us-east-1",
				    "pathStyleAccess": true,
				    "compatibilityOptions": {}
				  },
				  "bindings": []
				}
				""";
	}

}
