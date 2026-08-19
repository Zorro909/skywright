package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.acceptance.SeaweedFsFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Tag("real-service")
final class TargetStorageQualificationIT {

	@Test
	void exercisesEveryRequiredCapabilityAndCleansItsPrivatePrefix() throws Exception {
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
		try (SeaweedFsFixture service = SeaweedFsFixture.start();
				S3AsyncClient administrator = S3AsyncClient.builder()
					.httpClientBuilder(NettyNioAsyncHttpClient.builder())
					.endpointOverride(service.endpoint())
					.region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
					.build()) {
			service.awaitReady(administrator);
			String bucket = "qualification-" + UUID.randomUUID();
			administrator.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();

			TargetStorageCredentialAccess credentialAccess = (bindingId, bindingRevision, consumingRole) -> Optional
				.of(credentials);
			TargetStorageAssessment assessment = new S3TargetStorageQualificationProbe(credentialAccess)
				.qualify(new TargetStorageQualificationRequest(UUID.randomUUID(), TargetStoragePurpose.RUN_OUTPUT,
						bucket, 1, new TargetStorageConfiguration(service.endpoint(), "us-east-1", true, Map.of()),
						readyBindings()));

			assertThat(assessment.availability()).isEqualTo(CapabilityAvailability.AVAILABLE);
			assertThat(assessment.capabilities()).hasSize(19).allMatch(TargetStorageCapabilityResult::succeeded);
			assertThat(administrator.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
				.join()
				.contents()).isEmpty();
			assertThat(administrator.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build())
				.join()
				.uploads()).isEmpty();
		}
	}

	private static List<TargetStorageBinding> readyBindings() {
		return java.util.Arrays.stream(TargetStorageRole.values())
			.map(role -> new TargetStorageBinding(role, UUID.randomUUID(), 1, BindingReadiness.READY))
			.toList();
	}

}
