package de.zorro909.skywright.backend.orchestration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Runs deployment qualification against the packaged orchestration port. */
public final class OrchestratorQualificationMain {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(30);

	private OrchestratorQualificationMain() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length < 1 || arguments.length > 2) {
			throw new IllegalArgumentException("expected unavailable <cause>, saturation, held or held-control");
		}
		var endpoint = URI.create(requiredEnvironment("SKYWRIGHT_SKYPILOT_BRIDGE_API_SERVER_ENDPOINT"));
		try (var client = new GraalPySkyPilotClient(
				Path.of(System.getProperty("graalpy.external.directory", "graalpy-resources")), endpoint)) {
			var result = switch (arguments[0]) {
				case "unavailable" -> unavailable(client, expectedCause(arguments));
				case "saturation" -> saturation(client);
				case "held" -> SkyPilotHeldQualification.run(client, false);
				case "held-control" -> SkyPilotHeldQualification.run(client, true);
				default -> throw new IllegalArgumentException("unsupported qualification: " + arguments[0]);
			};
			System.out.println(JSON.writeValueAsString(result));
		}
	}

	private static Map<String, Object> unavailable(SkyPilotClient client, BridgeFailure.FailureCause expected)
			throws Exception {
		try (var orchestrator = new SkyPilotOrchestrator(client,
				new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(5)))) {
			var availability = orchestrator.refreshAvailability()
				.toCompletableFuture()
				.get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			var result = orchestrator.observe(new StatusRequest(List.of()))
				.toCompletableFuture()
				.get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			var failure = requiredFailure(result);
			if (availability.available() || failure.cause() != expected
					|| !BridgeFailure.UNAVAILABLE_CODE.equals(failure.code())) {
				throw new IllegalStateException("unexpected unavailable qualification result: " + failure);
			}
			return Map.of("available", false, "code", failure.code(), "cause", failure.cause().name(), "diagnostic",
					failure.diagnostic());
		}
	}

	private static Map<String, Object> saturation(SkyPilotClient client) throws Exception {
		var gate = new CountDownLatch(1);
		var entered = new CountDownLatch(1);
		try (var orchestrator = new SkyPilotOrchestrator(new BlockingStatusClient(client, entered, gate),
				new SkyPilotBridgeSettings(1, 1, Duration.ofSeconds(5)))) {
			var initial = orchestrator.refreshAvailability()
				.toCompletableFuture()
				.get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			if (!initial.available()) {
				throw new IllegalStateException("SkyPilot was unavailable before saturation: " + initial.failure());
			}
			var first = orchestrator.observe(new StatusRequest(List.of()));
			if (!entered.await(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
				throw new IllegalStateException("the first orchestration call did not enter the control lane");
			}
			var queued = orchestrator.observe(new StatusRequest(List.of()));
			var rejected = orchestrator.observe(new StatusRequest(List.of()))
				.toCompletableFuture()
				.get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			var failure = requiredFailure(rejected);
			if (!BridgeFailure.BUSY_CODE.equals(failure.code())
					|| failure.cause() != BridgeFailure.FailureCause.SATURATION
					|| !orchestrator.availability().available()) {
				throw new IllegalStateException("unexpected saturation qualification result: " + failure);
			}
			gate.countDown();
			first.toCompletableFuture().get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			queued.toCompletableFuture().get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			return Map.of("available", true, "code", failure.code(), "cause", failure.cause().name(), "diagnostic",
					failure.diagnostic());
		}
		finally {
			gate.countDown();
		}
	}

	private static BridgeFailure requiredFailure(OrchestratorResult<?> result) {
		return Optional.ofNullable(result.failure())
			.orElseThrow(() -> new IllegalStateException("orchestration call unexpectedly succeeded"));
	}

	private static BridgeFailure.FailureCause expectedCause(String[] arguments) {
		if (arguments.length != 2) {
			throw new IllegalArgumentException("unavailable qualification requires one expected cause");
		}
		return BridgeFailure.FailureCause.valueOf(arguments[1]);
	}

	private static String requiredEnvironment(String name) {
		var value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}

	private record BlockingStatusClient(SkyPilotClient delegate, CountDownLatch entered,
			CountDownLatch gate) implements SkyPilotClient {

		@Override
		public String version() {
			return this.delegate.version();
		}

		@Override
		public void probe() throws Exception {
			this.delegate.probe();
		}

		@Override
		public OrchestratorOperation submit(OrchestratorTaskSpecification task) throws Exception {
			return this.delegate.submit(task);
		}

		@Override
		public OrchestratorOperation observe(StatusRequest request) throws Exception {
			this.entered.countDown();
			this.gate.await();
			return this.delegate.observe(request);
		}

		@Override
		public OrchestratorOperation control(ControlRequest request) throws Exception {
			return this.delegate.control(request);
		}

		@Override
		public OrchestratorOperation cleanup(CleanupRequest request) throws Exception {
			return this.delegate.cleanup(request);
		}

		@Override
		public OperationOutcome complete(OrchestratorOperation operation) throws Exception {
			return this.delegate.complete(operation);
		}

		@Override
		public void close() {
			// The enclosing qualification owns the packaged client.
		}
	}

}
