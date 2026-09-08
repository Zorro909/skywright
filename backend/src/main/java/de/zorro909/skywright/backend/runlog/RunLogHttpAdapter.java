package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.boundary.generated.api.RunLogsApi;
import de.zorro909.skywright.backend.boundary.generated.model.RunLogNavigation;
import de.zorro909.skywright.backend.boundary.generated.model.RunLogPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class RunLogHttpAdapter implements RunLogsApi {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunLogReads reads;

	private final RunLogFollowing following;

	private final HttpServletRequest request;

	private final HttpServletResponse response;

	RunLogHttpAdapter(RunLogReads reads, RunLogFollowing following, HttpServletRequest request,
			HttpServletResponse response) {
		this.reads = reads;
		this.following = following;
		this.request = request;
		this.response = response;
	}

	@Override
	public ResponseEntity<RunLogPage> readRunLog(UUID runId, String stream, String cursor, String before) {
		return ResponseEntity.ok()
			.header("Cache-Control", "no-store")
			.body(JSON.convertValue(reads.read(runId, stream, RunLogReads.cursor(cursor), RunLogReads.cursor(before)),
					RunLogPage.class));
	}

	@Override
	public ResponseEntity<RunLogNavigation> readRunLogNavigation(UUID runId, String cursor) {
		Long position = RunLogReads.cursor(cursor);
		return ResponseEntity.ok()
			.header("Cache-Control", "no-store")
			.body(JSON.convertValue(reads.navigation(runId, position == null ? 0 : position), RunLogNavigation.class));
	}

	@Override
	public ResponseEntity<RunLogPage> followRunLog(UUID runId, String stream, String cursor) {
		try {
			following.follow(runId, stream, RunLogReads.cursor(cursor), request, response);
			return null; // Spring's AsyncWebRequest and the nonblocking writer own this
							// response.
		}
		catch (java.io.IOException disconnected) {
			throw new IllegalStateException("Archive viewer disconnected", disconnected);
		}
	}

}
