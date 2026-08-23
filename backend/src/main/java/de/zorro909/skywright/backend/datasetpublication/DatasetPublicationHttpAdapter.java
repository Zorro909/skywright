package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.boundary.generated.api.DatasetPublicationsApi;
import de.zorro909.skywright.backend.boundary.generated.api.DatasetsApi;
import de.zorro909.skywright.backend.boundary.generated.model.InitiateDatasetPublication;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetPublicationHttpAdapter implements DatasetPublicationsApi, DatasetsApi {

	private final DatasetPublicationService publications;

	DatasetPublicationHttpAdapter(DatasetPublicationService publications) {
		this.publications = publications;
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> initiateDatasetPublication(
			InitiateDatasetPublication request) {
		var initiated = this.publications.initiate(new DatasetPublicationRequest(request.getTargetStorageId(),
				request.getVersionLabel(), request.getFormatIdentity().getValue(), request.getManifestIdentity(),
				request.getContentFingerprint(), request.getObjectCount(), request.getByteCount()));
		return ResponseEntity.status(201).body(publication(initiated));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> getDatasetPublication(
			UUID publicationId) {
		return ResponseEntity.ok(publication(this.publications.get(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> completeDatasetPublication(
			UUID publicationId) {
		return ResponseEntity.ok(publication(this.publications.complete(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetLineage> getDataset(
			UUID datasetId) {
		DatasetLineageView value = this.publications.dataset(datasetId);
		return ResponseEntity
			.ok(new de.zorro909.skywright.backend.boundary.generated.model.DatasetLineage(value.datasetId(),
					value.revision(), value.preferredDefinitionId(), value.createdAt().atOffset(ZoneOffset.UTC)));
	}

	private static de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication publication(
			DatasetPublicationView value) {
		return new de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication(value.publicationId(),
				de.zorro909.skywright.backend.boundary.generated.model.DatasetPublicationState
					.fromValue(wireValue(value.state())),
				value.datasetId(), value.definitionId(), value.copyId(), value.targetStorageId(), value.versionLabel(),
				value.formatIdentity(), value.manifestIdentity(), value.contentFingerprint(), value.objectCount(),
				value.byteCount(), value.payloadLocation(), value.operationLocation(), value.verifiedObjectCount(),
				value.verifiedByteCount(), value.preferredDefinitionId(), value.preferredDefinitionChanged(),
				value.retryable(), value.failureCode(), value.createdAt().atOffset(ZoneOffset.UTC),
				value.verifiedAt() == null ? null : value.verifiedAt().atOffset(ZoneOffset.UTC),
				value.completedAt() == null ? null : value.completedAt().atOffset(ZoneOffset.UTC));
	}

	private static String wireValue(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
