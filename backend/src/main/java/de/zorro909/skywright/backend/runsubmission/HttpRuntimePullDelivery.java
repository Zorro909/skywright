package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.credential.LocalProjectionFacts;
import de.zorro909.skywright.backend.credential.RuntimePullProjection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Registry material crosses only the private, target-side helper channel. */
@Service
final class HttpRuntimePullDelivery implements RuntimePullDelivery {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String endpoint;

	private final LocalProjectionFacts facts;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	HttpRuntimePullDelivery(@Value("${skywright.runtime-pull.endpoint:}") String endpoint, LocalProjectionFacts facts) {
		this.endpoint = endpoint;
		this.facts = facts;
	}

	@Override
	public String namespace(String context) {
		var response = exchange("namespace", Map.of("context", context), null);
		if (response.statusCode() != 200)
			throw unavailable();
		var namespace = JSON.readTree(response.body()).path("namespace").asText();
		if (!namespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
			throw unavailable();
		return namespace;
	}

	@Override
	public Readiness readiness(String context) {
		var response = exchange("readiness", Map.of("context", context), null);
		if (response.statusCode() != 200)
			throw unavailable();
		var value = JSON.readTree(response.body());
		if (!value.path("nodeReady").isBoolean() || !value.path("writerReady").isBoolean()
				|| !value.path("gpuModel").isString() || !value.path("gpuCount").isInt()
				|| !value.path("freeGpuCount").isInt())
			throw unavailable();
		int count = value.path("gpuCount").asInt(), free = value.path("freeGpuCount").asInt();
		if (count < 0 || count > 32 || free < 0 || free > count)
			throw unavailable();
		return new Readiness(true, value.path("nodeReady").asBoolean(), value.path("gpuModel").asText(), count, free,
				value.path("writerReady").asBoolean());
	}

	@Override
	public boolean installed(AcceptedRun run) {
		var response = exchange("pull", identity(run), null);
		if (response.statusCode() == 404)
			return false;
		verify(response);
		return true;
	}

	@Override
	public void install(AcceptedRun run, RuntimePullProjection projection) {
		try {
			if (Files.size(projection.file()) > 1024 * 1024)
				throw unavailable();
			byte[] content = Files.readAllBytes(projection.file());
			try {
				verify(exchange("pull", identity(run), content));
			}
			finally {
				java.util.Arrays.fill(content, (byte) 0);
			}
		}
		catch (java.io.IOException failure) {
			throw unavailable();
		}
	}

	private Map<String, String> identity(AcceptedRun run) {
		var pin = facts.forConsumer(run.runId())
			.stream()
			.filter(f -> f.slot().equals("runtime-pull") && f.role().equals("execution-target-pull")
					&& f.releasedAt() == null)
			.findFirst()
			.orElseThrow(this::unavailable);
		if (run.task().resources().size() != 1 || run.task().runtimePullNamespace() == null
				|| !run.task().resources().getFirst().infrastructure().startsWith("kubernetes/"))
			throw unavailable();
		return Map.of("context", run.task().resources().getFirst().infrastructure().substring("kubernetes/".length()),
				"namespace", run.task().runtimePullNamespace(), "run", run.runId().toString(), "binding",
				pin.bindingId().toString(), "revision", Long.toString(pin.revision()));
	}

	private HttpResponse<byte[]> exchange(String path, Map<String, String> parameters, byte[] body) {
		if (endpoint.isBlank())
			throw unavailable();
		try {
			var base = URI.create(endpoint);
			if (!java.util.Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
					|| base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
					|| !java.util.Set.of("", "/").contains(base.getPath()))
				throw unavailable();
			var query = parameters.entrySet()
				.stream()
				.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
			var builder = HttpRequest.newBuilder(base.resolve("/" + path + "?" + query)).timeout(Duration.ofSeconds(5));
			if (body != null)
				builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(body));
			return client.send(builder.build(),
					HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 4096));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (Exception failure) {
			throw unavailable();
		}
	}

	private void verify(HttpResponse<byte[]> response) {
		if (response.statusCode() == 409)
			throw new RunSubmissionException("RUNTIME_PULL_IDENTITY_CONFLICT", 409);
		if (response.statusCode() != 200 || !JSON.readTree(response.body()).path("installed").asBoolean())
			throw unavailable();
	}

	private RunSubmissionException unavailable() {
		return new RunSubmissionException("RUNTIME_PULL_PROJECTION_UNAVAILABLE", 503);
	}

}
