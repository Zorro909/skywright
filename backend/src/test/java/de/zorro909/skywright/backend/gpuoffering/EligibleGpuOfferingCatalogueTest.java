package de.zorro909.skywright.backend.gpuoffering;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EligibleGpuOfferingCatalogueTest {

	@Test
	void acceptsTheSupportedPurchaseModeForEveryTargetClassAndBothQualifyingSupportTiers() {
		for (TargetClass targetClass : TargetClass.values()) {
			GpuOfferingPurchaseMode purchaseMode = switch (targetClass) {
				case LOCAL_SINGLE_GPU, LOCAL_MULTI_GPU -> GpuOfferingPurchaseMode.LOCAL;
				case CLOUD_ON_DEMAND -> GpuOfferingPurchaseMode.ON_DEMAND;
				case CLOUD_SPOT -> GpuOfferingPurchaseMode.SPOT;
			};
			int requestedCount = targetClass == TargetClass.LOCAL_SINGLE_GPU ? 1 : 2;
			TargetRequest request = request(targetClass, requestedCount, null, null, null);

			assertThat(matches(offering(targetClass, purchaseMode, TargetSupportTier.FIRST_CLASS, "target", "H100",
					requestedCount, 80), request))
				.isTrue();
			assertThat(matches(offering(targetClass, purchaseMode, TargetSupportTier.COMPATIBLE, "target", "H100",
					requestedCount, 80), request))
				.isTrue();
		}
	}

	@Test
	void rejectsDeferredAndTargetClassPurchaseModeMismatches() {
		TargetRequest request = request(TargetClass.CLOUD_SPOT, 1, null, null, null);

		assertThat(matches(offering(TargetClass.CLOUD_SPOT, GpuOfferingPurchaseMode.SPOT, TargetSupportTier.DEFERRED,
				"target", "H100", 1, 80), request))
			.isFalse();
		assertThat(matches(offering(TargetClass.CLOUD_SPOT, GpuOfferingPurchaseMode.ON_DEMAND,
				TargetSupportTier.FIRST_CLASS, "target", "H100", 1, 80), request))
			.isFalse();
		assertThat(matches(offering(TargetClass.CLOUD_ON_DEMAND, GpuOfferingPurchaseMode.ON_DEMAND,
				TargetSupportTier.FIRST_CLASS, "target", "H100", 1, 80), request))
			.isFalse();
	}

	@Test
	void countAndMemoryThresholdsAreInclusiveAndRequireEnoughConfiguredHardware() {
		EligibleGpuOfferingView offering = offering(TargetClass.CLOUD_ON_DEMAND, GpuOfferingPurchaseMode.ON_DEMAND,
				TargetSupportTier.FIRST_CLASS, "target", "H100", 4, 80);

		assertThat(matches(offering, request(TargetClass.CLOUD_ON_DEMAND, 4, 80L, null, null))).isTrue();
		assertThat(matches(offering, request(TargetClass.CLOUD_ON_DEMAND, 5, 80L, null, null))).isFalse();
		assertThat(matches(offering, request(TargetClass.CLOUD_ON_DEMAND, 4, 81L, null, null))).isFalse();
	}

	@Test
	void exactTargetPinsDisableFallbackAndGpuModelFilteringRemainsIndependent() {
		List<EligibleGpuOfferingView> offerings = List.of(
				offering(TargetClass.CLOUD_SPOT, GpuOfferingPurchaseMode.SPOT, TargetSupportTier.FIRST_CLASS, "nebius",
						"H100", 8, 80),
				offering(TargetClass.CLOUD_SPOT, GpuOfferingPurchaseMode.SPOT, TargetSupportTier.FIRST_CLASS, "runpod",
						"A100", 8, 80));

		assertThat(matching(offerings, request(TargetClass.CLOUD_SPOT, 1, null, null, null)))
			.extracting(EligibleGpuOfferingView::gpuModel)
			.containsExactly("H100", "A100");
		assertThat(matching(offerings, request(TargetClass.CLOUD_SPOT, 1, null, null, "A100")))
			.extracting(EligibleGpuOfferingView::target)
			.containsExactly("runpod");
		assertThat(matching(offerings, request(TargetClass.CLOUD_SPOT, 1, null, "nebius", null)))
			.extracting(EligibleGpuOfferingView::target)
			.containsExactly("nebius");
		assertThat(matching(offerings, request(TargetClass.CLOUD_SPOT, 1, null, "nebius", "A100"))).isEmpty();
	}

	@Test
	void aNonPositiveRequestAndARequestWithoutMatchesProduceNoEligibility() {
		EligibleGpuOfferingView offering = offering(TargetClass.CLOUD_SPOT, GpuOfferingPurchaseMode.SPOT,
				TargetSupportTier.FIRST_CLASS, "nebius", "H100", 8, 80);

		assertThat(matches(offering, request(TargetClass.CLOUD_SPOT, 0, null, null, null))).isFalse();
		assertThat(matching(List.of(offering), request(TargetClass.CLOUD_SPOT, 1, null, "vast", null))).isEmpty();
	}

	private static boolean matches(EligibleGpuOfferingView offering, TargetRequest request) {
		return EligibleGpuOfferingCatalogue.matches(offering, request);
	}

	private static List<EligibleGpuOfferingView> matching(List<EligibleGpuOfferingView> offerings,
			TargetRequest request) {
		return offerings.stream().filter(offering -> matches(offering, request)).toList();
	}

	private static TargetRequest request(TargetClass targetClass, int gpuCount, Long minimumMemory, String target,
			String gpuModel) {
		return new TargetRequest(targetClass, gpuCount, minimumMemory, target, gpuModel, null);
	}

	private static EligibleGpuOfferingView offering(TargetClass targetClass, GpuOfferingPurchaseMode purchaseMode,
			TargetSupportTier supportTier, String target, String gpuModel, int gpuCount, long gpuMemory) {
		return new EligibleGpuOfferingView(UUID.randomUUID(), 1, targetClass, target, "provider-id", "region",
				"instance", gpuModel, gpuCount, gpuMemory, purchaseMode, supportTier);
	}

}
