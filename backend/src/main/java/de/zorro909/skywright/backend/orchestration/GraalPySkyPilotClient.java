package de.zorro909.skywright.backend.orchestration;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.python.embedding.GraalPyResources;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class GraalPySkyPilotClient implements SkyPilotClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Path resources;

	private final URI apiServerEndpoint;

	private Context context;

	private Value bindings;

	GraalPySkyPilotClient(Path resources, URI apiServerEndpoint) {
		this.resources = resources.toAbsolutePath();
		this.apiServerEndpoint = apiServerEndpoint;
	}

	@Override
	public String version() {
		return SkyPilotBridgeSettings.SKY_PILOT_VERSION;
	}

	@Override
	public void probe() throws Exception {
		var result = invoke("bridge_probe");
		var serverVersion = requiredText(result, "server_version");
		if (!SkyPilotBridgeSettings.SKY_PILOT_VERSION.equals(serverVersion)) {
			throw new SkyPilotClientFailure(BridgeFailure.FailureCause.VERSION_MISMATCH,
					"SkyPilot API server reports version " + serverVersion);
		}
	}

	@Override
	public OrchestratorOperation submit(OrchestratorTaskSpecification task) throws Exception {
		return operation(invoke("bridge_submit", JSON.writeValueAsString(task)), OperationKind.SUBMISSION);
	}

	@Override
	public OrchestratorOperation observe(StatusRequest request) throws Exception {
		return operation(invoke("bridge_status", JSON.writeValueAsString(request.jobNames())), OperationKind.STATUS);
	}

	@Override
	public OrchestratorOperation control(ControlRequest request) throws Exception {
		return switch (request.action()) {
			case CANCEL -> operation(invoke("bridge_cancel", request.jobName()), OperationKind.CONTROL);
		};
	}

	@Override
	public OrchestratorOperation cleanup(CleanupRequest request) throws Exception {
		return operation(invoke("bridge_cleanup", request.clusterName()), OperationKind.CLEANUP);
	}

	@Override
	public OperationOutcome complete(OrchestratorOperation operation) throws Exception {
		var result = invoke("bridge_complete", operation.id(), operation.kind().name().toLowerCase());
		if (result.has("failure")) {
			var failure = result.required("failure");
			return new OperationOutcome.Failed(requiredText(failure, "category"), requiredText(failure, "message"));
		}
		return switch (operation.kind()) {
			case SUBMISSION -> new OperationOutcome.Submitted(result.required("job_id").asLong(),
					readHandle(result.required("handle")));
			case STATUS -> new OperationOutcome.Observed(readJobs(result.required("jobs")));
			case CONTROL -> new OperationOutcome.Controlled(result.required("applied").asBoolean());
			case CLEANUP -> new OperationOutcome.Cleaned(result.required("removed").asBoolean());
		};
	}

	private JsonNode invoke(String function, Object... arguments) throws Exception {
		initialize();
		var currentBindings = this.bindings;
		try {
			return JSON.readTree(currentBindings.getMember(function).execute(arguments).asString());
		}
		catch (org.graalvm.polyglot.PolyglotException failure) {
			throw SkyPilotClientFailure.from(failure);
		}
	}

	private synchronized void initialize() throws SkyPilotClientFailure {
		if (this.context != null) {
			return;
		}
		try {
			if (!Files.isDirectory(this.resources.resolve("venv"))) {
				throw new IllegalStateException("packaged GraalPy environment is missing");
			}
			this.context = Context.newBuilder()
				.apply(GraalPyResources.forExternalDirectory(this.resources))
				.allowHostAccess(HostAccess.NONE)
				.allowHostClassLookup(ignored -> false)
				.allowPolyglotAccess(PolyglotAccess.NONE)
				.allowNativeAccess(true)
				.allowCreateThread(true)
				.allowEnvironmentAccess(EnvironmentAccess.INHERIT)
				.environment("SKYPILOT_API_SERVER_ENDPOINT", this.apiServerEndpoint.toString())
				.allowIO(IOAccess.newBuilder().allowHostFileAccess(true).allowHostSocketAccess(true).build())
				.logHandler(OutputStream.nullOutputStream())
				.arguments("python", new String[] { "skywright-backend" })
				.option("python.PosixModuleBackend", "native")
				.build();
			try (var stream = GraalPySkyPilotClient.class
				.getResourceAsStream("/META-INF/skywright/skypilot_bridge.py")) {
				if (stream == null) {
					throw new IllegalStateException("packaged SkyPilot bridge resource is missing");
				}
				var source = Source
					.newBuilder("python", new InputStreamReader(stream, StandardCharsets.UTF_8), "skypilot_bridge.py")
					.build();
				this.context.eval(source);
				this.bindings = this.context.getBindings("python");
			}
		}
		catch (Exception failure) {
			close();
			throw new SkyPilotClientFailure(BridgeFailure.FailureCause.CLIENT_INITIALIZATION,
					failure.getMessage() == null ? "GraalPy client initialization failed" : failure.getMessage());
		}
	}

	private static OrchestratorOperation operation(JsonNode result, OperationKind kind) {
		return new OrchestratorOperation(requiredText(result, "operation_id"), kind);
	}

	private static ArrayList<OperationOutcome.ManagedJobStatus> readJobs(JsonNode values) {
		var jobs = new ArrayList<OperationOutcome.ManagedJobStatus>();
		for (var value : values) {
			jobs.add(new OperationOutcome.ManagedJobStatus(value.required("job_id").asLong(),
					requiredText(value, "job_name"), requiredText(value, "status"),
					value.required("recovery_count").asInt()));
		}
		return jobs;
	}

	private static OperationOutcome.ResourceHandle readHandle(JsonNode value) {
		return new OperationOutcome.ResourceHandle(requiredText(value, "type"), requiredText(value, "cluster_name"),
				requiredText(value, "cluster_name_on_cloud"), value.required("launched_nodes").asInt(),
				requiredText(value, "launched_resources"));
	}

	private static String requiredText(JsonNode value, String field) {
		var text = value.required(field).asText();
		if (text.isBlank()) {
			throw new IllegalArgumentException("SkyPilot result field '" + field + "' is blank");
		}
		return text;
	}

	@Override
	public synchronized void close() {
		if (this.context != null) {
			this.context.close(true);
			this.context = null;
			this.bindings = null;
		}
	}

}
