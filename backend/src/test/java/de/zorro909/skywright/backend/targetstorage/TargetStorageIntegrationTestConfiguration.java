package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration(proxyBeanMethods = false)
@Profile("target-storage-integration")
public class TargetStorageIntegrationTestConfiguration {

	private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider
		.create(AwsBasicCredentials.create(SeaweedFsFixture.WORKER_ACCESS_KEY, SeaweedFsFixture.WORKER_SECRET_KEY));

	@Bean
	TargetStorageCredentialAccess integrationTargetStorageCredentialAccess() {
		return (bindingId, bindingRevision, consumingRole) -> Optional.of(CREDENTIALS);
	}

	@Bean
	@Primary
	TargetStorageBindingReadiness integrationTargetStorageBindingReadiness() {
		return (bindingId, bindingRevision, consumingRole) -> BindingReadiness.READY;
	}

	@Bean
	@Primary
	TargetStorageQualification integrationTargetStorageQualification(TargetStorageRegistry registry,
			TargetStorageCredentialAccess credentialAccess) {
		return new TargetStorageQualification(registry, new S3TargetStorageQualificationProbe(credentialAccess));
	}

}
