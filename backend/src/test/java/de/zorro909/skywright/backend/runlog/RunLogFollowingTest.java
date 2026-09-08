package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.server.ResponseStatusException;

class RunLogFollowingTest {

	@Test
	void unreadyOutputStopsProductionAndReleasesAdmissionAfterItsDeadline() throws Exception {
		var readCount = new AtomicInteger();
		var following = new RunLogFollowing((run, stream, cursor) -> {
			readCount.incrementAndGet();
			return page(run, stream);
		}, ignored -> {
		});
		try {
			var requests = new java.util.ArrayList<MockHttpServletRequest>();
			for (int i = 0; i < RunLogFollowing.MAX_VIEWERS; i++)
				requests.add(open(following, new Output(false)));
			assertThatThrownBy(() -> open(following, new Output(false))).isInstanceOfSatisfying(
					ResponseStatusException.class,
					failure -> assertThat(failure.getStatusCode().value()).isEqualTo(429));
			await(() -> readCount.get() == RunLogFollowing.MAX_VIEWERS, 3);
			assertThat(readCount).hasValue(RunLogFollowing.MAX_VIEWERS);
			await(() -> requests.stream().noneMatch(MockHttpServletRequest::isAsyncStarted), 8);
			assertThat(readCount).as("no extra archive pages accumulate behind an unready output")
				.hasValue(RunLogFollowing.MAX_VIEWERS);
			assertThat(open(following, new Output(false)).isAsyncStarted()).isTrue();
		}
		finally {
			following.close();
		}
	}

	@Test
	void readyCallbackResumesTheSameFrameAndFinalizationClosesOnlyAfterDelivery() throws Exception {
		var following = new RunLogFollowing((run, stream, cursor) -> {
			var page = page(run, stream);
			return new ArchiveReader.Page(run, stream, page.availability(), page.observedAt(), "finalized",
					page.sourceAvailability(), null, page.lastSuccessfulFetch(), "complete", null, "0", "1", "1",
					"QQ==");
		}, ignored -> {
		});
		try {
			var output = new Output(false);
			var request = open(following, output);
			await(() -> output.checked.get() > 0, 3);
			assertThat(output.bytes.size()).isZero();
			output.ready = true;
			output.listener.onWritePossible();
			await(() -> !request.isAsyncStarted(), 2);
			assertThat(output.bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
				.contains("id: 1\nevent: archive\n", "QQ==");
		}
		finally {
			following.close();
		}
	}

	private static MockHttpServletRequest open(RunLogFollowing following, Output output) throws Exception {
		var request = new MockHttpServletRequest();
		request.setAsyncSupported(true);
		var response = new MockHttpServletResponse() {
			@Override
			public ServletOutputStream getOutputStream() {
				return output;
			}
		};
		WebAsyncUtils.getAsyncManager(request)
			.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, response));
		following.follow(UUID.randomUUID(), "task", 0L, request, response);
		return request;
	}

	private static ArchiveReader.Page page(UUID run, String stream) {
		return new ArchiveReader.Page(run, stream, "available", java.time.Instant.now(), "staging", "available", null,
				java.time.Instant.now(), "pending", null, "0", "1", "1", "QQ==");
	}

	private static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean() && System.nanoTime() < end)
			Thread.sleep(20);
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private static final class Output extends ServletOutputStream {

		volatile boolean ready;

		volatile WriteListener listener;

		final AtomicInteger checked = new AtomicInteger();

		final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

		Output(boolean ready) {
			this.ready = ready;
		}

		public boolean isReady() {
			checked.incrementAndGet();
			return ready;
		}

		public void setWriteListener(WriteListener listener) {
			this.listener = listener;
		}

		public void write(int value) {
			assertThat(ready).as("nonblocking write requires readiness").isTrue();
			bytes.write(value);
		}

	}

}
