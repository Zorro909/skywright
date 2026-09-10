package de.zorro909.skywright.backend.orchestration;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded workload against the packaged client and an isolated, empty API server. */
final class SkyPilotResourceQualification {

	private SkyPilotResourceQualification() {
	}

	static Map<String, Object> run(SkyPilotClient client) throws Exception {
		var samples = new ArrayList<Map<String, Object>>();
		status(client);
		long baseline = sockets();
		samples.add(sample("warm"));
		for (int round = 0; round < 4; round++) {
			for (int request = 0; request < 10; request++) {
				client.probe();
				status(client);
			}
			samples.add(sample("round-" + round));
			requireBound(baseline);
		}
		Thread.sleep(2000);
		samples.add(sample("idle"));
		requireBound(baseline);
		client.close();
		Thread.sleep(1000);
		samples.add(sample("closed"));
		requireBound(baseline);
		return Map.of("sdk_resources_bounded", true, "samples", samples);
	}

	private static void status(SkyPilotClient client) throws Exception {
		var result = client.complete(client.observe(new StatusRequest(List.of("resource-qualification"))));
		if (!new OperationOutcome.Failed("ClusterNotUpError", "SkyPilot target is unavailable").equals(result)
				&& !new OperationOutcome.Observed(List.of(), true).equals(result)) {
			throw new IllegalStateException("unexpected isolated SDK status result: " + result);
		}
	}

	private static void requireBound(long baseline) throws Exception {
		long count = sockets();
		if (count > baseline + 2) {
			throw new IllegalStateException("SDK socket growth: " + baseline + " -> " + count);
		}
	}

	private static Map<String, Object> sample(String phase) throws Exception {
		var rss = Files.readAllLines(Path.of("/proc/self/status"))
			.stream()
			.filter(line -> line.startsWith("VmRSS:"))
			.findFirst()
			.orElseThrow();
		var sample = Map.<String, Object>of("phase", phase, "sockets", sockets(), "heapBytes",
				ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), "rss", rss);
		System.out.println(sample);
		return sample;
	}

	private static long sockets() throws Exception {
		try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
			return descriptors.filter(path -> {
				try {
					return Files.readSymbolicLink(path).toString().startsWith("socket:");
				}
				catch (java.io.IOException ignored) {
					return false;
				}
			}).count();
		}
	}

}
