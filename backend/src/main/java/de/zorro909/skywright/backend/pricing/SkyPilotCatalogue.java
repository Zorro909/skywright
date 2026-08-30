package de.zorro909.skywright.backend.pricing;

import java.util.Optional;

/** Reads estimate observations through the version-paired SkyPilot catalogue service. */
@FunctionalInterface
public interface SkyPilotCatalogue {

	Optional<SkyPilotCatalogueObservation> price(SkyPilotCatalogueQuery query) throws Exception;

}
