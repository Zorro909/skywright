package de.zorro909.skywright.backend.orchestration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Packaged SDK qualification coordinated with an external HTTP fault fixture. */
final class SkyPilotHeldQualification {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SkyPilotHeldQualification() {
	}

	static Map<String, Object> run(GraalPySkyPilotClient client, boolean saturateControl) throws Exception {
		var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		var evidence = new LinkedHashMap<String, Object>();
		evidence.put("control_queue_capacity", 2);
		evidence.put("held_queue_capacity", 1);
		evidence.put("shutdown_grace_ms", 100);
		var orchestrator = new SkyPilotOrchestrator(client, new SkyPilotBridgeSettings(2, 1, Duration.ofMillis(100)));
		try {
			require(orchestrator.refreshAvailability().toCompletableFuture().get(60, TimeUnit.SECONDS).available(),
					"initial availability");
			var operation = orchestrator.observe(new StatusRequest(List.of("missing-job")))
				.toCompletableFuture()
				.get(90, TimeUnit.SECONDS)
				.value();
			System.out.println("HOLD " + JSON.readTree(operation.id()).required("request_id").asText());
			proceed(input, "complete");
			var held = orchestrator.complete(operation).toCompletableFuture();
			proceed(input, "measure");
			var started = System.nanoTime();
			var cancellation = orchestrator.control(new ControlRequest("missing-job", ControlRequest.Action.CANCEL))
				.toCompletableFuture()
				.get(2, TimeUnit.SECONDS);
			require(cancellation.failure() == null, "cancellation initiation");
			evidence.put("cancellation_ms", elapsed(started));
			started = System.nanoTime();
			require(orchestrator.refreshAvailability().toCompletableFuture().get(2, TimeUnit.SECONDS).available(),
					"health with held work");
			evidence.put("probe_ms", elapsed(started));
			require(!held.isDone(), "completion remains outstanding");
			started = System.nanoTime();
			var queued = orchestrator.complete(operation).toCompletableFuture();
			var rejected = orchestrator.complete(operation).toCompletableFuture().get(100, TimeUnit.MILLISECONDS);
			require(rejected.failure() != null && rejected.failure().cause() == BridgeFailure.FailureCause.SATURATION,
					"held queue saturation");
			require(!queued.isDone(), "one completion queued");
			evidence.put("admission_ms", elapsed(started));
			started = System.nanoTime();
			try {
				orchestrator.catalogue(client::price)
					.price(new de.zorro909.skywright.backend.pricing.SkyPilotCatalogueQuery("aws", "us-east-1",
							"p5.48xlarge", "H100", 8, false, java.time.Instant.now()));
				throw new IllegalStateException("catalogue bypassed the full held queue");
			}
			catch (SkyPilotClientFailure full) {
				require(full.causeCategory() == BridgeFailure.FailureCause.SATURATION, "catalogue queue saturation");
			}
			evidence.put("catalogue_admission_ms", elapsed(started));
			java.util.concurrent.CompletableFuture<OrchestratorResult<OrchestratorOperation>> activeControl = null;
			List<java.util.concurrent.CompletableFuture<OrchestratorResult<OrchestratorOperation>>> queuedControl = List
				.of();
			if (saturateControl) {
				System.out.println("HOLD_CONTROL");
				proceed(input, "control");
				activeControl = orchestrator.observe(new StatusRequest(List.of("missing-job"))).toCompletableFuture();
				proceed(input, "measure_control");
				started = System.nanoTime();
				queuedControl = List.of(
						orchestrator.observe(new StatusRequest(List.of("missing-job"))).toCompletableFuture(),
						orchestrator.observe(new StatusRequest(List.of("missing-job"))).toCompletableFuture());
				var controlRejected = orchestrator.observe(new StatusRequest(List.of("missing-job")))
					.toCompletableFuture()
					.get(100, TimeUnit.MILLISECONDS);
				require(controlRejected.failure() != null
						&& controlRejected.failure().cause() == BridgeFailure.FailureCause.SATURATION,
						"control queue saturation");
				require(!activeControl.isDone()
						&& queuedControl.stream().noneMatch(java.util.concurrent.CompletableFuture::isDone),
						"one active control call and two queued");
				evidence.put("control_admission_ms", elapsed(started));
			}
			System.out.println("UNREACHABLE");
			proceed(input, "unreachable");
			started = System.nanoTime();
			if (!saturateControl) {
				var unavailable = orchestrator.refreshAvailability().toCompletableFuture().get(6, TimeUnit.SECONDS);
				require(!unavailable.available()
						&& unavailable.failure().cause() == BridgeFailure.FailureCause.REACHABILITY,
						"unreachable server");
				evidence.put("unreachable_probe_ms", elapsed(started));
				require(orchestrator.observe(new StatusRequest(List.of()))
					.toCompletableFuture()
					.get(100, TimeUnit.MILLISECONDS)
					.failure()
					.cause() == BridgeFailure.FailureCause.REACHABILITY,
						"unavailable admission remains distinct from saturation");
			}
			started = System.nanoTime();
			orchestrator.close();
			evidence.put("shutdown_ms", elapsed(started));
			require(elapsed(started) < 5000, "bounded shutdown");
			require(held.get(1, TimeUnit.SECONDS).failure().cause() == BridgeFailure.FailureCause.SHUTDOWN,
					"active completion shutdown");
			require(queued.get(1, TimeUnit.SECONDS).failure().cause() == BridgeFailure.FailureCause.SHUTDOWN,
					"queued completion shutdown");
			if (saturateControl) {
				require(activeControl.get(1, TimeUnit.SECONDS).failure().cause() == BridgeFailure.FailureCause.SHUTDOWN,
						"active control shutdown");
			}
			for (var pending : queuedControl) {
				require(pending.get(1, TimeUnit.SECONDS).failure().cause() == BridgeFailure.FailureCause.SHUTDOWN,
						"queued control shutdown");
			}
			return evidence;
		}
		finally {
			orchestrator.close();
		}
	}

	private static long elapsed(long started) {
		return (System.nanoTime() - started) / 1_000_000;
	}

	private static void proceed(BufferedReader input, String expected) throws Exception {
		require(expected.equals(input.readLine()), "fixture command " + expected);
	}

	private static void require(boolean condition, String claim) {
		if (!condition) {
			throw new IllegalStateException("qualification failed: " + claim);
		}
	}

}
