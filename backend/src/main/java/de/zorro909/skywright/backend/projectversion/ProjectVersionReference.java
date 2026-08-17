package de.zorro909.skywright.backend.projectversion;

/** Display metadata enumerated without pulling a Training Project Version's content. */
public record ProjectVersionReference(String versionLabel, String manifestArtifactDigest) {
}
