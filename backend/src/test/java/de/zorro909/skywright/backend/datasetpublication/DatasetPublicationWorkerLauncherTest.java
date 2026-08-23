package de.zorro909.skywright.backend.datasetpublication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

class DatasetPublicationWorkerLauncherTest {

	@Test
	void workerReceivesOnlyItsRoleScopedCredential() {
		Map<String, String> environment = new HashMap<>(Map.of("BACKEND_SENTINEL_SECRET", "must-not-cross"));

		DatasetPublicationWorkerLauncher.projectEnvironment(environment,
				AwsBasicCredentials.create("worker-key", "worker-secret"));

		assertThat(environment).containsExactlyInAnyOrderEntriesOf(
				Map.of("AWS_ACCESS_KEY_ID", "worker-key", "AWS_SECRET_ACCESS_KEY", "worker-secret"));
	}

	@Test
	void workerReceivesItsSessionToken() {
		Map<String, String> environment = new HashMap<>();

		DatasetPublicationWorkerLauncher.projectEnvironment(environment,
				AwsSessionCredentials.create("worker-key", "worker-secret", "worker-session"));

		assertThat(environment).containsEntry("AWS_SESSION_TOKEN", "worker-session");
	}

}
