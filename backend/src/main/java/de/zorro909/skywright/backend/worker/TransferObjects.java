package de.zorro909.skywright.backend.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Predicate;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;

/** Bounded object operations shared by isolated Transfer Workers. */
public final class TransferObjects {

	private TransferObjects() {
	}

	public static S3AsyncClient client(URI endpoint, String region, AwsCredentialsProvider credentials,
			boolean pathStyle, boolean chunked, RequestChecksumCalculation checksum,
			ClientOverrideConfiguration configuration, Duration readTimeout) {
		var http = NettyNioAsyncHttpClient.builder();
		if (readTimeout != null)
			http.readTimeout(readTimeout);
		return S3AsyncClient.builder()
			.endpointOverride(endpoint)
			.region(Region.of(region))
			.credentialsProvider(credentials)
			.httpClientBuilder(http)
			.serviceConfiguration(
					S3Configuration.builder().pathStyleAccessEnabled(pathStyle).chunkedEncodingEnabled(chunked).build())
			.requestChecksumCalculation(checksum)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.overrideConfiguration(configuration)
			.build();
	}

	/** Registered request-checksum policy, with the same default as qualification. */
	public static RequestChecksumCalculation checksumCalculation(String option) {
		return "when-supported".equals(option) ? RequestChecksumCalculation.WHEN_SUPPORTED
				: RequestChecksumCalculation.WHEN_REQUIRED;
	}

	/** Verify complete object bytes; metadata and multipart checksums are not proof. */
	public static void verify(S3AsyncClient client, String bucket, String key, long size, String digest)
			throws IOException {
		var response = client
			.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
					AsyncResponseTransformer.toBlockingInputStream())
			.join();
		try {
			if (response.response().contentLength() == null || response.response().contentLength() != size)
				throw new IntegrityMismatch();
			verify(response, size, digest, OutputStream.nullOutputStream());
		}
		finally {
			response.abort();
		}
	}

	/**
	 * Consume exactly the declared length, allowing one extra byte only to detect
	 * overflow.
	 */
	public static void verify(InputStream stream, long size, String digest, OutputStream copy) throws IOException {
		if (size < 0)
			throw new IntegrityMismatch();
		final MessageDigest hash;
		try {
			hash = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}
		byte[] buffer = new byte[1024 * 1024];
		long consumed = 0;
		while (true) {
			if (Thread.currentThread().isInterrupted())
				throw new InterruptedIOException();
			int count = stream.read(buffer, 0, (int) Math.min(buffer.length - 1L, size - consumed) + 1);
			if (count == -1)
				break;
			consumed += count;
			if (consumed > size)
				throw new IntegrityMismatch();
			hash.update(buffer, 0, count);
			copy.write(buffer, 0, count);
		}
		if (consumed != size || !HexFormat.of().formatHex(hash.digest()).equals(digest))
			throw new IntegrityMismatch();
	}

	/**
	 * Inventory a bounded number of uploads and abort only identities in the caller's
	 * owned scope.
	 */
	public static void abortUploads(S3AsyncClient client, String bucket, String prefix, Predicate<String> ownedKey,
			int maximumUploads) {
		String keyMarker = null;
		String uploadMarker = null;
		int remaining = maximumUploads;
		do {
			if (remaining <= 0)
				throw new IllegalStateException("Multipart inventory budget exhausted");
			var page = client
				.listMultipartUploads(ListMultipartUploadsRequest.builder()
					.bucket(bucket)
					.prefix(prefix)
					.keyMarker(keyMarker)
					.uploadIdMarker(uploadMarker)
					.maxUploads(Math.min(1000, remaining))
					.build())
				.join();
			remaining -= Math.max(1, page.uploads().size());
			if (remaining < 0)
				throw new IllegalStateException("Multipart inventory budget exceeded");
			for (var upload : page.uploads()) {
				if (ownedKey.test(upload.key()))
					client.abortMultipartUpload(b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()))
						.join();
			}
			if (!Boolean.TRUE.equals(page.isTruncated()))
				return;
			if (page.nextKeyMarker() == null || page.nextUploadIdMarker() == null
					|| (page.nextKeyMarker().equals(keyMarker) && page.nextUploadIdMarker().equals(uploadMarker)))
				throw new IllegalStateException("Multipart inventory did not advance");
			keyMarker = page.nextKeyMarker();
			uploadMarker = page.nextUploadIdMarker();
		}
		while (true);
	}

	public static final class IntegrityMismatch extends IOException {

	}

}
