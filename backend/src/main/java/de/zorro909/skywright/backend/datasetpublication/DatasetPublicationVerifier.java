package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.datasetcatalog.DatasetManifestEntry;
import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class DatasetPublicationVerifier {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TargetStorageResolver targetStorages;

	private final Clock clock;

	DatasetPublicationVerifier(TargetStorageResolver targetStorages, Clock clock) {
		this.targetStorages = targetStorages;
		this.clock = clock;
	}

	VerifiedPublication verify(DatasetPublicationView publication) {
		ResolvedTargetStorage target = this.targetStorages.resolveDataset(publication.targetStorageId(),
				"transfer-worker");
		try (S3AsyncClient client = client(target)) {
			byte[] manifestBytes = client
				.getObject(GetObjectRequest.builder()
					.bucket(target.bucket())
					.key(key(publication.operationLocation(), "manifest.json"))
					.build(), AsyncResponseTransformer.toBytes())
				.join()
				.asByteArray();
			if (!digest(manifestBytes).equals(publication.manifestIdentity())) {
				throw mismatch("The staged manifest identity does not match the publication");
			}
			String fingerprintDocument = "{\"format\":\"" + publication.formatIdentity() + "\",\"manifest\":\""
					+ publication.manifestIdentity() + "\",\"version\":\"skywright-dataset-content@1\"}";
			if (!digest(fingerprintDocument.getBytes(StandardCharsets.UTF_8))
				.equals(publication.contentFingerprint())) {
				throw mismatch("The Dataset content fingerprint does not match the staged manifest");
			}
			List<ManifestObject> objects = parseManifest(manifestBytes, publication);
			Set<String> expectedKeys = new HashSet<>();
			List<DatasetManifestEntry> catalogEntries = new ArrayList<>();
			for (ManifestObject object : objects) {
				String remoteKey = key(publication.payloadLocation(), object.objectKey());
				expectedKeys.add(remoteKey);
				verifyObject(client, target.bucket(), remoteKey, object);
				catalogEntries.add(new DatasetManifestEntry(object.objectKey(), object.byteCount(),
						Base64.getEncoder().encodeToString(HexFormat.of().parseHex(object.sha256().substring(7)))));
			}
			if (!remoteKeys(client, target.bucket(), publication.payloadLocation() + "/").equals(expectedKeys)) {
				throw mismatch("The staged payload contains missing or additional objects");
			}
			return new VerifiedPublication(catalogEntries, objects.size(),
					objects.stream().mapToLong(ManifestObject::byteCount).sum(), this.clock.instant());
		}
		catch (DatasetPublicationException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throw new DatasetPublicationException("DATASET_VERIFICATION_UNAVAILABLE",
					"The managed Transfer Worker could not read Dataset storage", true);
		}
	}

	private static List<ManifestObject> parseManifest(byte[] bytes, DatasetPublicationView publication) {
		try {
			JsonNode root = JSON.readTree(bytes);
			if (!"skywright-dataset-manifest@1".equals(root.path("version").asText())
					|| !publication.formatIdentity().equals(root.path("format").asText())
					|| publication.objectCount() != root.path("objectCount").asLong(-1)
					|| publication.byteCount() != root.path("byteCount").asLong(-1)
					|| !root.path("objects").isArray()) {
				throw mismatch("The staged manifest facts do not match the publication");
			}
			List<ManifestObject> objects = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			String previousKey = null;
			for (JsonNode item : root.path("objects")) {
				String objectKey = item.path("objectKey").asText();
				long byteCount = item.path("byteCount").asLong(-1);
				String sha256 = item.path("sha256").asText();
				if (!safeKey(objectKey) || byteCount < 0 || !sha256.matches("sha256:[0-9a-f]{64}")
						|| !keys.add(objectKey)
						|| previousKey != null
								&& java.util.Arrays.compareUnsigned(previousKey.getBytes(StandardCharsets.UTF_8),
										objectKey.getBytes(StandardCharsets.UTF_8)) >= 0) {
					throw mismatch("The staged manifest contains an invalid object entry");
				}
				objects.add(new ManifestObject(objectKey, byteCount, sha256));
				previousKey = objectKey;
			}
			if (objects.size() != publication.objectCount()
					|| objects.stream().mapToLong(ManifestObject::byteCount).sum() != publication.byteCount()) {
				throw mismatch("The staged manifest totals do not match the publication");
			}
			return List.copyOf(objects);
		}
		catch (JacksonException failure) {
			throw mismatch("The staged manifest is not valid JSON");
		}
	}

	private static void verifyObject(S3AsyncClient client, String bucket, String key, ManifestObject object) {
		Path directory = null;
		try {
			directory = Files.createTempDirectory("skywright-dataset-verification-");
			Path downloaded = directory.resolve("object");
			client
				.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
						AsyncResponseTransformer.toFile(downloaded))
				.join();
			if (Files.size(downloaded) != object.byteCount() || !digest(downloaded).equals(object.sha256())) {
				throw mismatch("A staged payload object does not match the manifest");
			}
		}
		catch (IOException failure) {
			throw new DatasetPublicationException("DATASET_VERIFICATION_UNAVAILABLE",
					"The managed Transfer Worker could not verify a Dataset object", true);
		}
		finally {
			if (directory != null) {
				try {
					Files.deleteIfExists(directory.resolve("object"));
					Files.deleteIfExists(directory);
				}
				catch (IOException ignored) {
					// The temporary verifier copy is not authoritative publication state.
				}
			}
		}
	}

	private static Set<String> remoteKeys(S3AsyncClient client, String bucket, String prefix) {
		Set<String> result = new HashSet<>();
		String token = null;
		do {
			var page = client
				.listObjectsV2(
						ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token).build())
				.join();
			page.contents().forEach(object -> result.add(object.key()));
			token = page.isTruncated() ? page.nextContinuationToken() : null;
		}
		while (token != null);
		return result;
	}

	private static S3AsyncClient client(ResolvedTargetStorage target) {
		S3Configuration configuration = S3Configuration.builder()
			.pathStyleAccessEnabled(target.pathStyleAccess())
			.chunkedEncodingEnabled("enabled".equals(target.compatibilityOptions().get("chunkedEncoding")))
			.build();
		return S3AsyncClient.builder()
			.httpClientBuilder(NettyNioAsyncHttpClient.builder())
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(configuration)
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
			.build();
	}

	private static String digest(Path path) throws IOException {
		MessageDigest digest = sha256();
		try (InputStream stream = Files.newInputStream(path)) {
			byte[] buffer = new byte[1024 * 1024];
			int count;
			while ((count = stream.read(buffer)) >= 0) {
				digest.update(buffer, 0, count);
			}
		}
		return "sha256:" + HexFormat.of().formatHex(digest.digest());
	}

	private static String digest(byte[] bytes) {
		return "sha256:" + HexFormat.of().formatHex(sha256().digest(bytes));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("The runtime does not provide SHA-256", failure);
		}
	}

	private static boolean safeKey(String value) {
		return !value.isBlank() && !value.startsWith("/") && !value.contains("\\")
				&& java.util.Arrays.stream(value.split("/", -1))
					.noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."));
	}

	private static String key(String prefix, String suffix) {
		return prefix.endsWith("/") ? prefix + suffix : prefix + "/" + suffix;
	}

	private static DatasetPublicationException mismatch(String detail) {
		return new DatasetPublicationException("DATASET_REMOTE_MANIFEST_MISMATCH", detail, false);
	}

	private record ManifestObject(String objectKey, long byteCount, String sha256) {
	}

}
