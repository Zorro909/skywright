package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import de.zorro909.skywright.backend.runstore.BoundedS3Body;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

@Service
final class S3RunStopRequests implements RunStopRequests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunAcceptanceStore runs;

	private final TargetStorageResolver storages;

	S3RunStopRequests(RunAcceptanceStore runs, TargetStorageResolver storages) {
		this.runs = runs;
		this.storages = storages;
	}

	@Override
	public Instant deliver(AcceptedRun run, RunCommand command) {
		if (command.kind() == RunCommand.Kind.SUBMISSION || !command.runId().equals(run.runId()))
			throw new IllegalArgumentException("Stop projection identity differs");
		var definition = run.definition().value();
		String project = definition.at("/trainingProjectVersion/projectIdentity").asText();
		var target = storages.resolveRunOutputRead(runs.currentStorage(run.runId()), project, run.runId().toString());
		String kind = command.kind() == RunCommand.Kind.CANCELLATION_REQUEST ? "cancellation" : "policy-stop";
		String key = project + "/" + run.runId() + "/v1/control/" + kind + ".json";
		byte[] body = JSON.writeValueAsBytes(new TreeMap<>(Map.of("schemaVersion", 1, "runId", run.runId().toString(),
				"projectVersion", definition.at("/trainingProjectVersion/manifestArtifactDigest").asText(), "commandId",
				command.id().toString(), "kind", kind, "requestedAt", command.acceptedAt().toString())));
		String digest;
		try {
			digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
		var metadata = Map.of("skywright-schema", "v1", "skywright-kind", "run-stop-request", "skywright-size",
				Integer.toString(body.length), "skywright-sha256", digest);
		try (var client = S3AsyncClient.builder()
			.endpointOverride(target.endpoint())
			.region(target.region())
			.credentialsProvider(target.credentials())
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(target.pathStyleAccess())
				.chunkedEncodingEnabled("enabled".equals(target.compatibilityOptions().get("chunkedEncoding")))
				.build())
			.requestChecksumCalculation(
					"when-supported".equals(target.compatibilityOptions().get("checksumCalculation"))
							? RequestChecksumCalculation.WHEN_SUPPORTED : RequestChecksumCalculation.WHEN_REQUIRED)
			.overrideConfiguration(ClientOverrideConfiguration.builder()
				.retryStrategy(b -> b.maxAttempts(1))
				.apiCallTimeout(Duration.ofSeconds(5))
				.apiCallAttemptTimeout(Duration.ofSeconds(5))
				.build())
			.build()) {
			try {
				client
					.putObject(b -> b.bucket(target.bucket())
						.key(key)
						.contentType("application/json")
						.metadata(metadata)
						.ifNoneMatch("*"), AsyncRequestBody.fromBytes(body))
					.join();
			}
			catch (CompletionException failure) {
				if (!(failure.getCause() instanceof S3Exception s3) || s3.statusCode() != 412)
					throw failure;
			}
			// The same read verifies an uncertain previous publication and recovers
			// its original timestamp; restarting cannot grant a new policy grace.
			var content = client.getObject(b -> b.bucket(target.bucket()).key(key), new BoundedS3Body(body.length))
				.join();
			var response = content.response();
			if (!response.metadata().equals(metadata) || response.lastModified() == null
					|| !Arrays.equals(content.asByteArray(), body))
				throw new RunSubmissionException("RUN_STOP_PROJECTION_CONFLICT", 409);
			return response.lastModified();
		}
	}

}
