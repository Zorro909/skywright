package de.zorro909.skywright.backend.projectversion;

/** OCI artifact bytes resolved together with their immutable registry manifest digest. */
public record RegistryArtifact(String manifestDigest, String content) {
}
