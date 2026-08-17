package de.zorro909.skywright.backend.projectversion;

/** Trusted Skywright-owned project identity and its current registry attribute. */
public record TrainingProjectBinding(String projectIdentity, String registryRepository) {
}
