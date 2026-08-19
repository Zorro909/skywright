package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3TargetStorageQualificationProbeTest {

	@Test
	void rangedReadMustReturnTheRequestedBytes() {
		byte[] content = "skywright-target-storage-qualification".getBytes(StandardCharsets.UTF_8);

		assertThatCode(() -> S3TargetStorageQualificationProbe.requireExpectedRange(content,
				"skywright".getBytes(StandardCharsets.UTF_8)))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> S3TargetStorageQualificationProbe.requireExpectedRange(content,
				"kywright-".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("wrong content");
	}

	@Test
	void transientPrerequisiteFailureSkipsDependentChecksWithoutBecomingPermanent() {
		var results = new S3TargetStorageQualificationProbe.ResultCollector();
		AtomicBoolean dependentCalled = new AtomicBoolean();
		results.check("put-object", () -> {
			throw new CompletionException(S3Exception.builder().statusCode(503).message("unavailable").build());
		});

		results.checkAfter("metadata-preservation", List.of("put-object"), () -> dependentCalled.set(true));

		assertThat(dependentCalled).isFalse();
		assertThat(results.permanentFailure()).isFalse();
	}

	@Test
	void independentPermanentFailureStillDominatesATransientFailure() {
		var results = new S3TargetStorageQualificationProbe.ResultCollector();
		results.check("put-object", () -> {
			throw new CompletionException(S3Exception.builder().statusCode(503).message("unavailable").build());
		});

		results.check("list-objects", () -> {
			throw new IllegalStateException("incompatible response");
		});

		assertThat(results.permanentFailure()).isTrue();
	}

	@Test
	void presignedGetIoFailureIsTransient() {
		var results = new S3TargetStorageQualificationProbe.ResultCollector();

		results.check("presigned-get", () -> {
			throw new IllegalStateException("Presigned GET failed", new IOException("connection reset"));
		});

		assertThat(results.permanentFailure()).isFalse();
	}

	@Test
	void presignedGetInterruptionIsTransient() {
		var results = new S3TargetStorageQualificationProbe.ResultCollector();

		results.check("presigned-get", () -> {
			throw new IllegalStateException("Presigned GET was interrupted", new InterruptedException("cancelled"));
		});

		assertThat(results.permanentFailure()).isFalse();
	}

}
