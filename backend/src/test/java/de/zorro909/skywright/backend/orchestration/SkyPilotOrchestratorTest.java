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
	void catalogueAndCompletionsShareBoundedHeldAdmission() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var catalogue = this.orchestrator.catalogue(query -> {
			entered.countDown();
			release.await();
			return java.util.Optional.empty();
		});
		var query = new de.zorro909.skywright.backend.pricing.SkyPilotCatalogueQuery("aws", "us-east-1", "p5.48xlarge",
				"H100", 8, false, java.time.Instant.now());
		try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
			var price = caller.submit(() -> catalogue.price(query));
			try {
				assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
				var completion = this.orchestrator.complete(new OrchestratorOperation("held-1", OperationKind.STATUS));
				assertThat(completion).isNotDone();
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalogue.price(query))
					.isInstanceOfSatisfying(SkyPilotClientFailure.class, failure -> assertThat(failure.causeCategory())
						.isEqualTo(BridgeFailure.FailureCause.SATURATION));
				assertThat(client.heldStarted.getCount()).isEqualTo(1);
			}
			finally {
				release.countDown();
				client.releaseHeld.countDown();
			}
			assertThat(price.get(1, TimeUnit.SECONDS)).isEmpty();
		}
	}

	@Test
	void dependencyDiagnosticsDoNotExposeGuestFailureText() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.submitFailure = new SkyPilotClientFailure(BridgeFailure.FailureCause.AUTHENTICATION,
				"Authorization: Bearer secret-value");
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();

		var failure = this.orchestrator
			.submit(new OrchestratorTaskSpecification("job", null, "true",
					List.of(new OrchestratorTaskSpecification.Resources("aws", "2", "4", null, null, false)),
					java.util.Map.of()))
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
	void stalledAvailabilityRefreshSharesBoundedControlAdmission() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();
		client.holdProbe.set(true);

		var refresh = this.orchestrator.refreshAvailability();
		assertThat(client.heldProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();
		var status = this.orchestrator.observe(new StatusRequest(List.of("job"))).toCompletableFuture();
		try {
			assertThat(status).isNotDone();
			assertThat(this.orchestrator.cleanup(new CleanupRequest("job"))
				.toCompletableFuture()
				.get(1, TimeUnit.SECONDS)
				.failure()
				.cause()).isEqualTo(BridgeFailure.FailureCause.SATURATION);
			assertThat(refresh).isNotDone();
		}
		finally {
			client.releaseProbe.countDown();
		}
		assertThat(status.get(1, TimeUnit.SECONDS).value().kind()).isEqualTo(OperationKind.STATUS);
	}

	@Test
	void heldWorkDoesNotBlockAvailabilityRefresh() throws Exception {
		var client = new ControllableSkyPilotClient();
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();

		var held = this.orchestrator.complete(new OrchestratorOperation("held-1", OperationKind.SUBMISSION));
		assertThat(client.heldStarted.await(1, TimeUnit.SECONDS)).isTrue();

		assertThat(this.orchestrator.refreshAvailability().toCompletableFuture().get(1, TimeUnit.SECONDS).available())
			.isTrue();
		assertThat(held).isNotDone();
		client.releaseHeld.countDown();
	}

	@Test
	void submissionPreflightSerializesWithStatus() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.holdSubmit.set(true);
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();

		var submission = this.orchestrator.submit(new OrchestratorTaskSpecification("job", null, "true",
				List.of(new OrchestratorTaskSpecification.Resources("aws", "2", "4", null, null, false)),
				java.util.Map.of()));
		assertThat(client.submitStarted.await(1, TimeUnit.SECONDS)).isTrue();

		var status = this.orchestrator.observe(new StatusRequest(List.of("job"))).toCompletableFuture();
		try {
			assertThat(status).isNotDone();
			assertThat(submission).isNotDone();
			assertThat(this.orchestrator.control(new ControlRequest("job", ControlRequest.Action.CANCEL))
				.toCompletableFuture()
				.get(1, TimeUnit.SECONDS)
				.failure()
				.cause()).isEqualTo(BridgeFailure.FailureCause.SATURATION);
		}
		finally {
			client.releaseSubmit.countDown();
		}
		assertThat(status.get(1, TimeUnit.SECONDS).value().kind()).isEqualTo(OperationKind.STATUS);
	}

	@Test
	void actionPreflightSerializesWithStatus() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.holdControl.set(true);
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(1)));
		awaitInitialProbe();

		var action = this.orchestrator.control(new ControlRequest("job", ControlRequest.Action.CANCEL));
		assertThat(client.controlStarted.await(1, TimeUnit.SECONDS)).isTrue();

		var status = this.orchestrator.observe(new StatusRequest(List.of("job"))).toCompletableFuture();
		try {
			assertThat(status).isNotDone();
			assertThat(action).isNotDone();
			assertThat(this.orchestrator.control(new ControlRequest("job", ControlRequest.Action.CANCEL))
				.toCompletableFuture()
				.get(1, TimeUnit.SECONDS)
				.failure()
				.cause()).isEqualTo(BridgeFailure.FailureCause.SATURATION);
		}
		finally {
			client.releaseControl.countDown();
		}
		assertThat(status.get(1, TimeUnit.SECONDS).value().kind()).isEqualTo(OperationKind.STATUS);
	}

	@Test
	void forcedShutdownClassifiesInterruptedActiveWorkAsShutdown() throws Exception {
		var client = new ControllableSkyPilotClient();
		client.releaseHeldOnClose.set(false);
		this.orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(1, 1, Duration.ZERO));
		awaitInitialProbe();

		var active = this.orchestrator.complete(new OrchestratorOperation("held-1", OperationKind.SUBMISSION));
		assertThat(client.heldStarted.await(1, TimeUnit.SECONDS)).isTrue();

		this.orchestrator.close();

		assertThat(active.toCompletableFuture().get(1, TimeUnit.SECONDS).failure().cause())
			.isEqualTo(BridgeFailure.FailureCause.SHUTDOWN);
	}

	private void awaitInitialProbe() throws Exception {
		this.orchestrator.refreshAvailability().toCompletableFuture().get(1, TimeUnit.SECONDS);
	}

	private static final class ControllableSkyPilotClient implements SkyPilotClient {

		private final CountDownLatch heldStarted = new CountDownLatch(1);

		private final CountDownLatch releaseHeld = new CountDownLatch(1);

		private final CountDownLatch heldProbeStarted = new CountDownLatch(1);

		private final CountDownLatch releaseProbe = new CountDownLatch(1);

		private final CountDownLatch submitStarted = new CountDownLatch(1);

		private final CountDownLatch releaseSubmit = new CountDownLatch(1);

		private final CountDownLatch controlStarted = new CountDownLatch(1);

		private final CountDownLatch releaseControl = new CountDownLatch(1);

		private final AtomicBoolean reachable = new AtomicBoolean(true);

		private final AtomicBoolean holdProbe = new AtomicBoolean(false);

		private final AtomicBoolean holdSubmit = new AtomicBoolean(false);

		private final AtomicBoolean holdControl = new AtomicBoolean(false);

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
			if (this.holdSubmit.compareAndSet(true, false)) {
				this.submitStarted.countDown();
				this.releaseSubmit.await();
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
		public OrchestratorOperation control(ControlRequest request) throws InterruptedException {
			if (this.holdControl.compareAndSet(true, false)) {
				this.controlStarted.countDown();
				this.releaseControl.await();
			}
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
			this.releaseSubmit.countDown();
			this.releaseControl.countDown();
		}

	}

}
