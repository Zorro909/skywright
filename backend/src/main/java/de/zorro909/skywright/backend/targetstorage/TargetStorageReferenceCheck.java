package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

/**
 * Checks durable references owned outside the Target Storage registry, such as Storage
 * Locations, immutable Run Definitions, and catalog records.
 */
public interface TargetStorageReferenceCheck {

	boolean hasDurableReference(UUID storageId);

}
