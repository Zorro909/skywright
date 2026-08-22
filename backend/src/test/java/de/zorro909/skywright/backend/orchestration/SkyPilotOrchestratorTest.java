package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class SkyPilotOrchestratorTest {

	private SkyPilotOrchestrator orchestrator;

	@AfterEach
	void closeOrchestrator() {
		if (this.orchestrator != null) {
			this.orchestrator.close();
		}
	}

	@Test
	void controlWorkCompletesWhileHeldWorkOccupiesItsWorkerAndQueue() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));

		var firstHeld = this.orchestrator.complete(new OrchestratorOperation("held-1", OperationKind.SUBMISSION));
		assertThat(client.heldStarted.await(1, TimeUnit.SECONDS)).isTrue();
		var queuedHeld = this.orchestrator.complete(new OrchestratorOperation("held-2", OperationKind.SUBMISSION));

		var rejectedHeld = this.orchestrator.complete(new OrchestratorOperation("held-3", OperationKind.SUBMISSION))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS);
		var status = this.orchestrator.observe(new StatusRequest(List.of("cluster-a")))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS);

		assertThat(rejectedHeld).isEqualTo(OrchestratorResult.failure(BridgeFailure.busy()));
		assertThat(status)
			.isEqualTo(OrchestratorResult.accepted(new OrchestratorOperation("status-1", OperationKind.STATUS)));
		assertThat(firstHeld).isNotDone();
		assertThat(queuedHeld).isNotDone();

		client.releaseHeld.countDown();
	}

	@Test
	void reachabilityFailureDegradesOnlyTheBridgeAndAProbeRestoresIt() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.reachable.set(false);
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));

		assertThat(this.orchestrator.availability().available()).isFalse();
		var failure = this.orchestrator.observe(new StatusRequest(List.of()))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS);

		assertThat(failure.failure().code()).isEqualTo("skypilot-unavailable");
		assertThat(failure.failure().cause()).isEqualTo(BridgeFailure.FailureCause.REACHABILITY);
		assertThat(this.orchestrator.availability().available()).isFalse();

		client.reachable.set(true);
		assertThat(this.orchestrator.refreshAvailability().toCompletableFuture().get(1, TimeUnit.SECONDS).available())
			.isTrue();
		assertThat(this.orchestrator.observe(new StatusRequest(List.of()))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS)
			.value()
			.kind()).isEqualTo(OperationKind.STATUS);
	}

	@Test
	void dependencyDiagnosticsDoNotExposeGuestFailureText() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.submitFailure = new SkyPilotClientFailure(BridgeFailure.FailureCause.AUTHENTICATION,
				"Authorization: Bearer secret-value");
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));

		var failure = this.orchestrator
			.submit(new OrchestratorTaskSpecification("job", null, "true",
					new OrchestratorTaskSpecification.Resources("aws", "2", "4", null), java.util.Map.of()))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS)
			.failure();

		assertThat(failure.cause()).isEqualTo(BridgeFailure.FailureCause.AUTHENTICATION);
		assertThat(failure.diagnostic()).doesNotContain("secret-value").isEqualTo("SkyPilot authentication failed");
	}

	@Test
	void shutdownIsIdempotentAndStopsAdmission() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ZERO));

		this.orchestrator.close();
		this.orchestrator.close();

		var result = this.orchestrator.observe(new StatusRequest(List.of()))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS);
		assertThat(result.failure().cause()).isEqualTo(BridgeFailure.FailureCause.SHUTDOWN);
		assertThat(client.closeCount).isEqualTo(1);
	}

	private static final class ControllableSkyPilotClient implements SkyPilotClient {

		private final CountDownLatch heldStarted = new CountDownLatch(1);

		private final CountDownLatch releaseHeld = new CountDownLatch(1);

		private final AtomicBoolean reachable = new AtomicBoolean(true);

		private int closeCount;

		private Exception submitFailure;

		@Override
		public String version() {
			return SkyPilotBridgeSettings.SKY_PILOT_VERSION;
		}

		@Override
		public void probe() throws Exception {
			if (!this.reachable.get()) {
				throw new java.net.ConnectException("API server unreachable");
			}
		}

		@Override
		public OrchestratorOperation submit(OrchestratorTaskSpecification task) throws Exception {
			if (this.submitFailure != null) {
				throw this.submitFailure;
			}
			return new OrchestratorOperation("submit-1", OperationKind.SUBMISSION);
		}

		@Override
		public OrchestratorOperation observe(StatusRequest request) {
			if (!this.reachable.get()) {
				throw new IllegalStateException("connection refused");
			}
			return new OrchestratorOperation("status-1", OperationKind.STATUS);
		}

		@Override
		public OrchestratorOperation control(ControlRequest request) {
			return new OrchestratorOperation("control-1", OperationKind.CONTROL);
		}

		@Override
		public OrchestratorOperation cleanup(CleanupRequest request) {
			return new OrchestratorOperation("cleanup-1", OperationKind.CLEANUP);
		}

		@Override
		public OperationOutcome complete(OrchestratorOperation operation) throws InterruptedException {
			this.heldStarted.countDown();
			this.releaseHeld.await();
			return new OperationOutcome.Controlled(true);
		}

		@Override
		public void close() {
			this.closeCount++;
			this.releaseHeld.countDown();
		}

	}

}
