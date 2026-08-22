package de.zorro909.skywright.backend.orchestration;

import de.zorro909.skywright.backend.orchestration.BridgeFailure.FailureCause;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SkyPilotOrchestrator implements Orchestrator, AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(SkyPilotOrchestrator.class);

	private final SkyPilotClient client;

	private final SkyPilotBridgeSettings settings;

	private final ThreadPoolExecutor controlLane;

	private final ThreadPoolExecutor submissionLane;

	private final ThreadPoolExecutor heldLane;

	private final ThreadPoolExecutor probeLane;

	private final AtomicBoolean admitting = new AtomicBoolean(true);

	private volatile SkyPilotAvailability availability;

	SkyPilotOrchestrator(SkyPilotClient client, SkyPilotBridgeSettings settings) {
		this.client = client;
		this.settings = settings;
		this.controlLane = lane("skypilot-control", settings.controlQueueCapacity());
		this.submissionLane = lane("skypilot-submission", settings.controlQueueCapacity());
		this.heldLane = lane("skypilot-held", settings.heldQueueCapacity());
		this.probeLane = lane("skypilot-probe", 1);
		this.availability = SkyPilotAvailability.unavailable(BridgeFailure
			.unavailable(FailureCause.CLIENT_INITIALIZATION, "SkyPilot availability check is pending"));
		refreshAvailability();
	}

	@Override
	public CompletionStage<OrchestratorResult<OrchestratorOperation>> submit(OrchestratorTaskSpecification task) {
		return submit(() -> this.client.submit(task));
	}

	@Override
	public CompletionStage<OrchestratorResult<OrchestratorOperation>> observe(StatusRequest request) {
		return control(() -> this.client.observe(request));
	}

	@Override
	public CompletionStage<OrchestratorResult<OrchestratorOperation>> control(ControlRequest request) {
		return control(() -> this.client.control(request));
	}

	@Override
	public CompletionStage<OrchestratorResult<OrchestratorOperation>> cleanup(CleanupRequest request) {
		return control(() -> this.client.cleanup(request));
	}

	@Override
	public CompletionStage<OrchestratorResult<OperationOutcome>> complete(OrchestratorOperation operation) {
		return dispatch(this.heldLane, () -> this.client.complete(operation));
	}

	@Override
	public SkyPilotAvailability availability() {
		return this.availability;
	}

	@Override
	public CompletionStage<SkyPilotAvailability> refreshAvailability() {
		var refreshed = new CompletableFuture<SkyPilotAvailability>();
		if (!this.admitting.get()) {
			refreshed.complete(this.availability);
			return refreshed;
		}
		try {
			this.probeLane.execute(new AvailabilityRefresh(refreshed));
		}
		catch (java.util.concurrent.RejectedExecutionException exception) {
			refreshed.complete(this.availability);
		}
		return refreshed;
	}

	private void recordAvailability(SkyPilotAvailability next) {
		var previous = this.availability;
		this.availability = next;
		if (previous != null && previous.available() == next.available()
				&& (next.available() || previous.failure().cause() == next.failure().cause())) {
			return;
		}
		if (next.available()) {
			LOGGER.info("SkyPilot capability available");
		}
		else {
			LOGGER.warn("SkyPilot capability unavailable: {} ({})", next.failure().diagnostic(),
					next.failure().cause());
		}
	}

	private SkyPilotAvailability probeAvailability() {
		try {
			if (!SkyPilotBridgeSettings.SKY_PILOT_VERSION.equals(this.client.version())) {
				throw new SkyPilotVersionMismatchException();
			}
			this.client.probe();
			return SkyPilotAvailability.healthy();
		}
		catch (Exception failure) {
			return SkyPilotAvailability.unavailable(mapFailure(failure));
		}
	}

	private <T> CompletionStage<OrchestratorResult<T>> control(CheckedSupplier<T> call) {
		return availableDispatch(this.controlLane, call);
	}

	private <T> CompletionStage<OrchestratorResult<T>> submit(CheckedSupplier<T> call) {
		return availableDispatch(this.submissionLane, call);
	}

	private <T> CompletionStage<OrchestratorResult<T>> availableDispatch(ThreadPoolExecutor lane,
			CheckedSupplier<T> call) {
		if (!this.admitting.get()) {
			return CompletableFuture.completedFuture(shutdownResult());
		}
		if (!this.availability.available()) {
			return CompletableFuture.completedFuture(OrchestratorResult.failure(this.availability.failure()));
		}
		return dispatch(lane, call);
	}

	private <T> CompletionStage<OrchestratorResult<T>> dispatch(ThreadPoolExecutor lane, CheckedSupplier<T> call) {
		if (!this.admitting.get()) {
			return CompletableFuture.completedFuture(OrchestratorResult
				.failure(BridgeFailure.unavailable(FailureCause.SHUTDOWN, "SkyPilot bridge is shutting down")));
		}
		var result = new CompletableFuture<OrchestratorResult<T>>();
		try {
			lane.execute(new BridgeCall<>(call, result));
		}
		catch (java.util.concurrent.RejectedExecutionException exception) {
			result.complete(this.admitting.get() ? OrchestratorResult.failure(BridgeFailure.busy()) : shutdownResult());
		}
		return result;
	}

	private <T> void run(CheckedSupplier<T> call, CompletableFuture<OrchestratorResult<T>> result) {
		try {
			result.complete(OrchestratorResult.accepted(call.get()));
		}
		catch (Exception failure) {
			if (!this.admitting.get()) {
				result.complete(shutdownResult());
				return;
			}
			var mapped = mapFailure(failure);
			if (mapped.cause() != FailureCause.ADAPTER_CONTRACT) {
				recordAvailability(SkyPilotAvailability.unavailable(mapped));
			}
			result.complete(OrchestratorResult.failure(mapped));
		}
	}

	private static BridgeFailure mapFailure(Exception failure) {
		var message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
		var normalized = message.toLowerCase(java.util.Locale.ROOT);
		if (failure instanceof SkyPilotClientFailure clientFailure) {
			return BridgeFailure.unavailable(clientFailure.causeCategory(),
					safeDiagnostic(clientFailure.causeCategory()));
		}
		if (failure instanceof SkyPilotVersionMismatchException) {
			return BridgeFailure.unavailable(FailureCause.VERSION_MISMATCH,
					"SkyPilot API server version does not match " + SkyPilotBridgeSettings.SKY_PILOT_VERSION);
		}
		if (normalized.contains("auth") || normalized.contains("unauthorized") || normalized.contains("forbidden")) {
			return BridgeFailure.unavailable(FailureCause.AUTHENTICATION, safeDiagnostic(FailureCause.AUTHENTICATION));
		}
		if (failure instanceof java.net.ConnectException || normalized.contains("connection")
				|| normalized.contains("unreachable")) {
			return BridgeFailure.unavailable(FailureCause.REACHABILITY, safeDiagnostic(FailureCause.REACHABILITY));
		}
		return BridgeFailure.unavailable(FailureCause.ADAPTER_CONTRACT, safeDiagnostic(FailureCause.ADAPTER_CONTRACT));
	}

	private static String safeDiagnostic(FailureCause cause) {
		return switch (cause) {
			case CLIENT_INITIALIZATION -> "SkyPilot client initialization failed";
			case AUTHENTICATION -> "SkyPilot authentication failed";
			case REACHABILITY -> "SkyPilot API server is unreachable";
			case VERSION_MISMATCH -> "SkyPilot client and API server versions do not match";
			case ADAPTER_CONTRACT -> "SkyPilot returned an unsupported result";
			case SHUTDOWN -> "SkyPilot bridge is shutting down";
			case SATURATION -> "SkyPilot bridge queue is full";
		};
	}

	private static ThreadPoolExecutor lane(String name, int capacity) {
		return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
				Thread.ofPlatform().name(name + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
	}

	@Override
	public void close() {
		if (!this.admitting.compareAndSet(true, false)) {
			return;
		}
		this.controlLane.shutdown();
		this.submissionLane.shutdown();
		this.heldLane.shutdown();
		this.probeLane.shutdown();
		var deadline = System.nanoTime() + this.settings.shutdownGrace().toNanos();
		await(this.controlLane, deadline);
		await(this.submissionLane, deadline);
		await(this.heldLane, deadline);
		await(this.probeLane, deadline);
		completeDiscarded(this.controlLane.shutdownNow());
		completeDiscarded(this.submissionLane.shutdownNow());
		completeDiscarded(this.heldLane.shutdownNow());
		completeDiscarded(this.probeLane.shutdownNow());
		this.client.close();
	}

	private static void completeDiscarded(java.util.List<Runnable> discarded) {
		for (var task : discarded) {
			if (task instanceof ShutdownAwareTask shutdownAwareTask) {
				shutdownAwareTask.rejectForShutdown();
			}
		}
	}

	private static <T> OrchestratorResult<T> shutdownResult() {
		return OrchestratorResult
			.failure(BridgeFailure.unavailable(FailureCause.SHUTDOWN, "SkyPilot bridge is shutting down"));
	}

	private static void await(ThreadPoolExecutor lane, long deadline) {
		try {
			lane.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	private interface CheckedSupplier<T> {

		T get() throws Exception;

	}

	private interface ShutdownAwareTask extends Runnable {

		void rejectForShutdown();

	}

	private final class AvailabilityRefresh implements ShutdownAwareTask {

		private final CompletableFuture<SkyPilotAvailability> result;

		private AvailabilityRefresh(CompletableFuture<SkyPilotAvailability> result) {
			this.result = result;
		}

		@Override
		public void run() {
			recordAvailability(probeAvailability());
			this.result.complete(availability);
		}

		@Override
		public void rejectForShutdown() {
			this.result.complete(availability);
		}

	}

	private final class BridgeCall<T> implements ShutdownAwareTask {

		private final CheckedSupplier<T> call;

		private final CompletableFuture<OrchestratorResult<T>> result;

		private BridgeCall(CheckedSupplier<T> call, CompletableFuture<OrchestratorResult<T>> result) {
			this.call = call;
			this.result = result;
		}

		@Override
		public void run() {
			SkyPilotOrchestrator.this.run(this.call, this.result);
		}

		@Override
		public void rejectForShutdown() {
			this.result.complete(shutdownResult());
		}

	}

	private static final class SkyPilotVersionMismatchException extends Exception {

	}

}
