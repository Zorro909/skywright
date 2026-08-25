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
		var initiated = this.publications.initiate(request(request));
		return ResponseEntity.status(201).body(publication(initiated));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> resumeDatasetPublication(
			UUID publicationId, InitiateDatasetPublication request) {
		return ResponseEntity.ok(publication(this.publications.resume(publicationId, request(request))));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> recordDatasetPublicationProgress(
			UUID publicationId,
			de.zorro909.skywright.backend.boundary.generated.model.DatasetPublicationProgress request) {
		return ResponseEntity.ok(publication(this.publications.progress(publicationId,
				new DatasetPublicationProgress(request.getUploadedObjectCount(), request.getUploadedByteCount()))));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> recordDatasetPublicationFailure(
			UUID publicationId,
			de.zorro909.skywright.backend.boundary.generated.model.DatasetPublicationFailure request) {
		return ResponseEntity.ok(publication(
				this.publications.failLocal(publicationId, new DatasetPublicationFailure(request.getFailureCode()))));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> getDatasetPublication(
			UUID publicationId) {
		return ResponseEntity.ok(publication(this.publications.get(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> completeDatasetPublication(
			UUID publicationId) {
		return ResponseEntity.accepted().body(publication(this.publications.complete(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> startDatasetPublicationTransfer(
			UUID publicationId) {
		return ResponseEntity.ok(publication(this.publications.startTransfer(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> stopDatasetPublicationTransfer(
			UUID publicationId) {
		return ResponseEntity.ok(publication(this.publications.stopTransfer(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> abortDatasetPublication(
			UUID publicationId) {
		return ResponseEntity.accepted().body(publication(this.publications.abort(publicationId)));
	}

	@Override
	public ResponseEntity<de.zorro909.skywright.backend.boundary.generated.model.DatasetPublication> retryDatasetPublicationCleanup(
			UUID publicationId) {
		return ResponseEntity.accepted().body(publication(this.publications.retryCleanup(publicationId)));
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
				value.datasetId(), value.definitionId(), value.copyId(), value.targetStorageId(),
				value.expectedDatasetRevision(),
				value.preferredDefinitionDecision() == null ? null
						: de.zorro909.skywright.backend.boundary.generated.model.DatasetPreferredDefinitionDecision
							.fromValue(wireValue(value.preferredDefinitionDecision())),
				value.versionLabel(), value.formatIdentity(), value.manifestIdentity(), value.contentFingerprint(),
				value.objectCount(), value.byteCount(), value.payloadLocation(), value.operationLocation(),
				value.uploadedObjectCount(), value.uploadedByteCount(), value.verifiedObjectCount(),
				value.verifiedByteCount(), value.preferredDefinitionId(), value.preferredDefinitionChanged(),
				value.retryable(), value.failureCode(), value.failureDetail(), value.unavailableSource(),
				value.retryGuidance(), value.createdAt().atOffset(ZoneOffset.UTC),
				value.updatedAt().atOffset(ZoneOffset.UTC),
				value.verifiedAt() == null ? null : value.verifiedAt().atOffset(ZoneOffset.UTC),
				value.completedAt() == null ? null : value.completedAt().atOffset(ZoneOffset.UTC),
				value.verificationWorkerPid());
	}

	private static DatasetPublicationRequest request(InitiateDatasetPublication request) {
		var decision = request.getPreferredDefinitionDecision() == null ? null
				: PreferredDefinitionDecision.valueOf(request.getPreferredDefinitionDecision().name());
		return new DatasetPublicationRequest(request.getTargetStorageId(), request.getDatasetId(),
				request.getExpectedDatasetRevision(), decision, request.getVersionLabel(),
				request.getFormatIdentity().getValue(), request.getManifestIdentity(), request.getContentFingerprint(),
				request.getObjectCount(), request.getByteCount());
	}

	private static String wireValue(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
