package de.zorro909.skywright.backend.orchestration;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Measures the packaged qualification's real control call without changing the SDK. */
final class CancellationTimingClient implements SkyPilotClient {

	private final SkyPilotClient delegate;

	private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

	private volatile Map<String, Long> cancellation = Map.of();

	CancellationTimingClient(SkyPilotClient delegate) {
		this.delegate = delegate;
		if (!this.threads.isCurrentThreadCpuTimeSupported()) {
			throw new IllegalStateException("Cancellation qualification requires thread CPU accounting");
		}
		this.threads.setThreadCpuTimeEnabled(true);
	}

	Map<String, Long> cancellation() {
		return this.cancellation;
	}

	@Override
	public OrchestratorOperation control(ControlRequest request) throws Exception {
		var cpuStarted = this.threads.getCurrentThreadCpuTime();
		var started = System.nanoTime();
		try {
			return this.delegate.control(request);
		}
		finally {
			this.cancellation = Map.of("call_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
					"thread_cpu_ms",
					TimeUnit.NANOSECONDS.toMillis(this.threads.getCurrentThreadCpuTime() - cpuStarted));
		}
	}

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
	public OrchestratorOperation submit(OrchestratorTaskSpecification task,
			de.zorro909.skywright.backend.credential.TrainingCredentials credentials) throws Exception {
		return this.delegate.submit(task, credentials);
	}

	@Override
	public OrchestratorOperation observe(StatusRequest request) throws Exception {
		return this.delegate.observe(request);
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
		this.delegate.close();
	}

}
