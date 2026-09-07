package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpRunLogSourceTest {

	@Test
	void preservesUnconfirmedSnapshotAsDistinctGenerationWithoutDiscardingAnIdenticalPrefix() throws Exception {
		for (boolean emptyPrefix : java.util.List.of(false, true)) {
			var objects = new ArchiveJournalTest.Objects();
			var journal = new ArchiveJournal(objects, ArchiveJournalTest.RUN, ArchiveJournalTest.VERSION);
			var archive = new RunLogArchive(ArchiveJournalTest.RUN, ArchiveJournalTest.VERSION);
			byte[] prefix = (emptyPrefix ? "" : "prefix\r\n").getBytes(StandardCharsets.UTF_8);
			var prior = journal.append(archive.append("task", RunLogArchive.Cursor.initial(),
					new RunLogArchive.Page("pod:1", 0, prefix, "{}", true, false, null), false, ArchiveJournalTest.NOW,
					ignored -> false));
			byte[] whole = (new String(prefix, StandardCharsets.UTF_8) + "tail\r\n").getBytes(StandardCharsets.UTF_8);
			var requests = new AtomicInteger();
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/page", exchange -> {
				var json = ArchiveJournalTest.JSON;
				var request = json.readTree(exchange.getRequestBody().readAllBytes());
				int offset = 0;
				int limit = request.path("limit").asInt();
				byte[] bytes = java.util.Arrays.copyOfRange(whole, offset, Math.min(whole.length, offset + limit));
				var page = json.createObjectNode()
					.put("runId", ArchiveJournalTest.RUN.toString())
					.put("stream", "task")
					.put("generation", "snapshot:1")
					.put("snapshot", true)
					.put("offset", offset)
					.put("bytes", Base64.getEncoder().encodeToString(bytes))
					.put("sha256", RunLogArchive.digest(bytes))
					.put("endOfFile", offset + bytes.length == whole.length)
					.put("sealed", offset + bytes.length == whole.length)
					.put("finalSource", true)
					.put("gap", "SOURCE_GENERATION_UNCONFIRMED")
					.set("cursor", json.createObjectNode());
				byte[] body = json.writeValueAsBytes(json.createObjectNode().put("schemaVersion", 1).set("page", page));
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
				requests.incrementAndGet();
			});
			server.start();
			try {
				var source = new HttpRunLogSource("http://127.0.0.1:" + server.getAddress().getPort());
				var fetched = source.fetch(ArchiveJournalTest.RUN, "task", prior);
				assertThat(fetched).isNotNull();
				assertThat(fetched.generation()).isEqualTo("snapshot:1");
				assertThat(fetched.bytes()).isEqualTo(whole);
				assertThat(requests).hasValue(1);
				var appended = journal
					.append(archive.append("task", prior, fetched, true, ArchiveJournalTest.NOW, ignored -> false));
				assertThat(appended.bytes()).isEqualTo(prefix.length + whole.length);
				assertThat(appended.ready()).isTrue();
				assertThat(appended.partialReason()).isNotNull();
			}
			finally {
				server.stop(0);
			}
		}
	}

}
