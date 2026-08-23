package de.zorro909.skywright.backend.datasetpublication;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

class DatasetPublicationWorkerLauncherTest {

	@Test
	void workerInheritsNoBackendEnvironment() {
		Map<String, String> environment = new HashMap<>(Map.of("BACKEND_SENTINEL_SECRET", "must-not-cross"));

		DatasetPublicationWorkerLauncher.clearEnvironment(environment);

		assertThat(environment).isEmpty();
	}

	@Test
	void workerReceivesOnlyItsRoleScopedCredentialAfterLaunchIsRecorded() {
		var basic = DatasetPublicationWorkerLauncher
			.credential(AwsBasicCredentials.create("worker-key", "worker-secret"));
		var session = DatasetPublicationWorkerLauncher
			.credential(AwsSessionCredentials.create("worker-key", "worker-secret", "worker-session"));

		assertThat(basic).isEqualTo(new DatasetPublicationWorkerCredential("worker-key", "worker-secret", null));
		assertThat(session)
			.isEqualTo(new DatasetPublicationWorkerCredential("worker-key", "worker-secret", "worker-session"));
	}

	@Test
	void workerVerificationHasNoFixedRuntimeDeadline() throws InterruptedException {
		var process = new CompletionProbeProcess();

		DatasetPublicationWorkerLauncher.awaitCompletion(process);

		assertThat(process.untimedWait).isTrue();
		assertThat(process.timedWait).isFalse();
	}

	private static final class CompletionProbeProcess extends Process {

		private boolean untimedWait;

		private boolean timedWait;

		@Override
		public OutputStream getOutputStream() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() {
			this.untimedWait = true;
			return 0;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) {
			this.timedWait = true;
			return false;
		}

		@Override
		public int exitValue() {
			return 0;
		}

		@Override
		public void destroy() {
		}

	}

}
