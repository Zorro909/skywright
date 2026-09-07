package de.zorro909.skywright.backend.runlog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A bounded read-only transport, separate from every orchestration operation. */
@Component
final class HttpRunLogSource implements RunLogSource {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private final String endpoint;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	HttpRunLogSource(@Value("${skywright.log-collector.endpoint:}") String endpoint) {
		this.endpoint = endpoint;
	}

	@jakarta.annotation.PreDestroy
	void shutdown() {
		http.shutdownNow();
	}

	@Override
	public RunLogArchive.Page fetch(UUID runId, String stream, RunLogArchive.Cursor prior) {
		ObjectNode cursor = (ObjectNode) JSON.readTree(prior.sourceCursor());
		ObjectNode transport = cursor.has("collector") ? (ObjectNode) cursor.path("collector")
				: JSON.createObjectNode();
		int limit = RunLogArchive.CHUNK_BYTES;
		var response = request(runId, stream, transport, prior.sourceOffset(), limit);
		if (!response.path("generation").isString() || response.path("generation").asText().isBlank()
				|| response.path("generation").asText().length() > 1024 || !response.path("cursor").isObject()
				|| !response.path("offset").isIntegralNumber() || !response.path("offset").canConvertToLong()
				|| response.path("offset").asLong() < 0 || !response.path("bytes").isString()
				|| !response.path("sha256").isString() || !response.path("endOfFile").isBoolean()
				|| !response.path("sealed").isBoolean() || !response.path("finalSource").isBoolean()
				|| response.path("sealed").asBoolean() && !response.path("endOfFile").asBoolean()
				|| response.has("gap")
						&& (!response.path("gap").isString() || !response.path("gap").asText().matches("[A-Z_]{1,64}"))
				|| JSON.writeValueAsBytes(response.path("cursor")).length > 3500)
			throw new Unavailable("SOURCE_RESPONSE_INVALID");
		String generation = response.required("generation").asText();
		if (response.path("snapshot").asBoolean() && prior.generation() != null
				&& !generation.equals(prior.generation()))
			throw new Unavailable("SOURCE_GENERATION_UNCONFIRMED");
		ObjectNode nextCollector = (ObjectNode) response.required("cursor");
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(response.required("bytes").asText());
		}
		catch (RuntimeException malformed) {
			throw new Unavailable("SOURCE_RESPONSE_INVALID");
		}
		long offset = response.required("offset").asLong();
		if (bytes.length > limit || !RunLogArchive.digest(bytes).equals(response.path("sha256").asText()))
			throw new Unavailable("SOURCE_RESPONSE_INVALID");
		if (offset != (generation.equals(prior.generation()) ? prior.sourceOffset() : 0))
			throw new Unavailable("SOURCE_CURSOR_DIFFERS");
		cursor.set("collector", nextCollector);
		boolean eof = response.path("endOfFile").asBoolean();
		boolean sealed = response.path("sealed").asBoolean();
		String gap = response.has("gap") ? response.path("gap").asText() : null;
		return new RunLogArchive.Page(generation, offset, bytes, JSON.writeValueAsString(cursor), eof, sealed, gap,
				response.path("finalSource").asBoolean());
	}

	private JsonNode request(UUID runId, String stream, ObjectNode cursor, long offset, int limit) {
		if (endpoint.isBlank())
			throw new Unavailable("COLLECTOR_UNAVAILABLE");
		var request = JSON.createObjectNode()
			.put("runId", runId.toString())
			.put("stream", stream)
			.put("offset", offset)
			.put("limit", limit)
			.set("cursor", cursor);
		try {
			var result = http.send(
					HttpRequest.newBuilder(URI.create(endpoint + "/v1/page"))
						.timeout(Duration.ofSeconds(10))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(request)))
						.build(),
					HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 2 * 1024 * 1024));
			var envelope = JSON.readTree(result.body());
			if (result.statusCode() != 200) {
				String reason = envelope.path("unavailable").asText();
				throw new Unavailable(reason.matches("[A-Z_]{1,64}") ? reason : "SOURCE_UNAVAILABLE");
			}
			var page = envelope.required("page");
			if (!envelope.path("schemaVersion").isIntegralNumber() || envelope.path("schemaVersion").asInt() != 1
					|| !page.path("runId").asText().equals(runId.toString())
					|| !page.path("stream").asText().equals(stream))
				throw new Unavailable("SOURCE_IDENTITY_DIFFERS");
			return page;
		}
		catch (Unavailable failure) {
			throw failure;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new Unavailable("COLLECTOR_INTERRUPTED");
		}
		catch (Exception failure) {
			throw new Unavailable("COLLECTOR_UNAVAILABLE");
		}
	}

}
