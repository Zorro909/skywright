package de.zorro909.skywright.backend.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
		awaitInitialProbe();

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
		awaitInitialProbe();

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
		awaitInitialProbe();

		var failure = this.orchestrator.submit(new OrchestratorTaskSpecification("job", null, "true",
				List.of(new OrchestratorTaskSpecification.Resources("aws", "2", "4", null, null)), java.util.Map.of()))
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

	@Test
	void initialProbeDoesNotBlockConstruction() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.holdProbe.set(true);

		var creating = CompletableFuture.supplyAsync(
				() -> new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1))));
		try {
			this.orchestrator = creating.get(1, TimeUnit.SECONDS);
			assertThat(client.heldProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(this.orchestrator.availability().available()).isFalse();
		}
		finally {
			client.releaseProbe.countDown();
		}
		awaitInitialProbe();
		assertThat(this.orchestrator.availability().available()).isTrue();
	}

	@Test
	void forcedShutdownCompletesQueuedWork() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.releaseHeldOnClose.set(false);
		client.ignoreHeldInterrupt.set(true);
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ZERO));
		awaitInitialProbe();

		var active = this.orchestrator.complete(new OrchestratorOperation("held-1", OperationKind.SUBMISSION));
		assertThat(client.heldStarted.await(1, TimeUnit.SECONDS)).isTrue();
		var queued = this.orchestrator.complete(new OrchestratorOperation("held-2", OperationKind.SUBMISSION));

		this.orchestrator.close();

		assertThat(queued.toCompletableFuture().get(1, TimeUnit.SECONDS).failure().cause())
			.isEqualTo(BridgeFailure.FailureCause.SHUTDOWN);
		client.releaseHeld.countDown();
		assertThat(active.toCompletableFuture()).succeedsWithin(Duration.ofSeconds(1));
	}

	@Test
	void stalledAvailabilityRefreshDoesNotBlockControlWork() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();
		client.holdProbe.set(true);

		var refresh = this.orchestrator.refreshAvailability();
		assertThat(client.heldProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();
		var status = this.orchestrator.observe(new StatusRequest(List.of("job")))
			.toCompletableFuture()
			.get(1, TimeUnit.SECONDS);

		assertThat(status)
			.isEqualTo(OrchestratorResult.accepted(new OrchestratorOperation("status-1", OperationKind.STATUS)));
		assertThat(refresh).isNotDone();
		client.releaseProbe.countDown();
	}

	private void awaitInitialProbe() throws Exception {
		this.orchestrator.refreshAvailability().toCompletableFuture().get(1, TimeUnit.SECONDS);
	}

	private static final class ControllableSkyPilotClient implements SkyPilotClient {

		private final CountDownLatch heldStarted = new CountDownLatch(1);

		private final CountDownLatch releaseHeld = new CountDownLatch(1);

		private final CountDownLatch heldProbeStarted = new CountDownLatch(1);

		private final CountDownLatch releaseProbe = new CountDownLatch(1);

		private final AtomicBoolean reachable = new AtomicBoolean(true);

		private final AtomicBoolean holdProbe = new AtomicBoolean(false);

		private final AtomicBoolean releaseHeldOnClose = new AtomicBoolean(true);

		private final AtomicBoolean ignoreHeldInterrupt = new AtomicBoolean(false);

		private int closeCount;

		private Exception submitFailure;

		@Override
		public String version() {
			return SkyPilotBridgeSettings.SKY_PILOT_VERSION;
		}

		@Override
		public void probe() throws Exception {
			if (this.holdProbe.compareAndSet(true, false)) {
				this.heldProbeStarted.countDown();
				this.releaseProbe.await();
			}
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
			while (true) {
				try {
					this.releaseHeld.await();
					break;
				}
				catch (InterruptedException interrupted) {
					if (!this.ignoreHeldInterrupt.get()) {
						throw interrupted;
					}
				}
			}
			return new OperationOutcome.Controlled(true);
		}

		@Override
		public void close() {
			this.closeCount++;
			if (this.releaseHeldOnClose.get()) {
				this.releaseHeld.countDown();
			}
			this.releaseProbe.countDown();
		}

	}

}
