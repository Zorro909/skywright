package de.zorro909.skywright.backend.targetstorage;

import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public interface TargetStorageCredentialAccess {

	Optional<AwsCredentialsProvider> credentials(UUID bindingId, long bindingRevision, String consumingRole);

}
