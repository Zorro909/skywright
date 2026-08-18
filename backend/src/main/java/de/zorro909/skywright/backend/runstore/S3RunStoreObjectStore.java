package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS-provider adapter hidden behind the Java Run Store access module. */
public final class S3RunStoreObjectStore implements RunStoreObjectStore, AutoCloseable {

	private final ResolvedTargetStorage target;

	private final S3Client client;

	private final S3Presigner presigner;

	public S3RunStoreObjectStore(ResolvedTargetStorage target) {
		this.target = target;
		S3Configuration configuration = S3Configuration.builder()
			.pathStyleAccessEnabled(target.pathStyleAccess())
			.build();
		this.client = S3Client.builder()
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(configuration)
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(configuration)
			.build();
	}

	@Override
	public List<RunStoreObject> list(String prefix) {
		List<RunStoreObject> result = new ArrayList<>();
		String continuation = null;
		do {
			ListObjectsV2Response page = this.client.listObjectsV2(ListObjectsV2Request.builder()
				.bucket(this.target.bucket())
				.prefix(prefix)
				.continuationToken(continuation)
				.build());
			page.contents().forEach(item -> result.add(get(item.key())));
			continuation = page.isTruncated() ? page.nextContinuationToken() : null;
		}
		while (continuation != null);
		return List.copyOf(result);
	}

	@Override
	public RunStoreObject get(String key) {
		ResponseBytes<GetObjectResponse> response = this.client
			.getObjectAsBytes(GetObjectRequest.builder().bucket(this.target.bucket()).key(key).build());
		return new RunStoreObject(key, response.asByteArray(), response.response().contentType(),
				response.response().metadata());
	}

	@Override
	public URI presignGet(String key, int expiresInSeconds) {
		GetObjectRequest get = GetObjectRequest.builder().bucket(this.target.bucket()).key(key).build();
		return URI.create(
				this.presigner
					.presignGetObject(GetObjectPresignRequest.builder()
						.signatureDuration(Duration.ofSeconds(expiresInSeconds))
						.getObjectRequest(get)
						.build())
					.url()
					.toString());
	}

	@Override
	public void close() {
		this.presigner.close();
		this.client.close();
	}

}
