package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.boundary.generated.api.EligibleGpuOfferingsApi;
import de.zorro909.skywright.backend.boundary.generated.model.CreateEligibleGpuOffering;
import de.zorro909.skywright.backend.boundary.generated.model.EligibleGpuOffering;
import de.zorro909.skywright.backend.boundary.generated.model.UpdateEligibleGpuOffering;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EligibleGpuOfferingHttpAdapter implements EligibleGpuOfferingsApi {

	private final EligibleGpuOfferingCatalogue catalogue;

	EligibleGpuOfferingHttpAdapter(EligibleGpuOfferingCatalogue catalogue) {
		this.catalogue = catalogue;
	}

	@Override
	public ResponseEntity<EligibleGpuOffering> createEligibleGpuOffering(CreateEligibleGpuOffering request) {
		return ResponseEntity.status(201).body(offering(this.catalogue.create(input(request))));
	}

	@Override
	public ResponseEntity<EligibleGpuOffering> getEligibleGpuOffering(UUID offeringId) {
		return ResponseEntity.ok(offering(this.catalogue.get(offeringId)));
	}

	@Override
	public ResponseEntity<List<EligibleGpuOffering>> listEligibleGpuOfferings() {
		return ResponseEntity.ok(this.catalogue.list().stream().map(EligibleGpuOfferingHttpAdapter::offering).toList());
	}

	@Override
	public ResponseEntity<EligibleGpuOffering> updateEligibleGpuOffering(UUID offeringId,
			UpdateEligibleGpuOffering request) {
		return ResponseEntity
			.ok(offering(this.catalogue.update(offeringId, request.getExpectedRevision(), input(request))));
	}

	@Override
	public ResponseEntity<Void> deleteEligibleGpuOffering(Long expectedRevision, UUID offeringId) {
		this.catalogue.delete(offeringId, expectedRevision);
		return ResponseEntity.noContent().build();
	}

	private static EligibleGpuOffering offering(EligibleGpuOfferingView value) {
		return new EligibleGpuOffering(value.id(), value.revision(),
				de.zorro909.skywright.backend.boundary.generated.model.TargetClass
					.fromValue(value.targetClass().wireValue()),
				value.target(), value.providerOfferingId(), value.region(), value.instanceType(), value.gpuModel(),
				value.gpuCount(), value.gpuMemoryBytes(),
				de.zorro909.skywright.backend.boundary.generated.model.GpuOfferingPurchaseMode
					.fromValue(value.purchaseMode().wireValue()),
				de.zorro909.skywright.backend.boundary.generated.model.TargetSupportTier
					.fromValue(value.supportTier().wireValue()));
	}

	private static EligibleGpuOfferingInput input(CreateEligibleGpuOffering request) {
		return new EligibleGpuOfferingInput(targetClass(request.getTargetClass().getValue()), request.getTarget(),
				request.getProviderOfferingId(), request.getRegion(), request.getInstanceType(), request.getGpuModel(),
				request.getGpuCount(), request.getGpuMemoryBytes(),
				GpuOfferingPurchaseMode.fromWireValue(request.getPurchaseMode().getValue()),
				TargetSupportTier.fromWireValue(request.getSupportTier().getValue()));
	}

	private static EligibleGpuOfferingInput input(UpdateEligibleGpuOffering request) {
		return new EligibleGpuOfferingInput(targetClass(request.getTargetClass().getValue()), request.getTarget(),
				request.getProviderOfferingId(), request.getRegion(), request.getInstanceType(), request.getGpuModel(),
				request.getGpuCount(), request.getGpuMemoryBytes(),
				GpuOfferingPurchaseMode.fromWireValue(request.getPurchaseMode().getValue()),
				TargetSupportTier.fromWireValue(request.getSupportTier().getValue()));
	}

	private static TargetClass targetClass(String value) {
		TargetClass result = Map
			.of("local-single-gpu", TargetClass.LOCAL_SINGLE_GPU, "local-multi-gpu", TargetClass.LOCAL_MULTI_GPU,
					"cloud-on-demand", TargetClass.CLOUD_ON_DEMAND, "cloud-spot", TargetClass.CLOUD_SPOT)
			.get(value);
		if (result == null) {
			throw new GpuOfferingValidationException("GPU_OFFERING_TARGET_CLASS_INVALID",
					"Target Class is not supported");
		}
		return result;
	}

}
