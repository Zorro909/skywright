package de.zorro909.skywright.backend.runlog;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet nonblocking output keeps a stalled viewer out of both archive and control
 * workers.
 */
@Service
final class RunLogFollowing {

	static final int MAX_VIEWERS = 16;

	static final int MAX_FRAME = 128 * 1024;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@FunctionalInterface
	interface Reader {

		ArchiveReader.Page read(UUID run, String stream, Long cursor);

	}

	private final Reader reads;

	private final java.util.function.Consumer<UUID> requireRun;

	private final Semaphore admission = new Semaphore(MAX_VIEWERS);

	private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

	private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(MAX_VIEWERS), Thread.ofPlatform().daemon().name("archive-view-read-", 0).factory(),
			new ThreadPoolExecutor.AbortPolicy());

	private final ScheduledExecutorService timer = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("archive-view-timeout-", 0).factory());

	@org.springframework.beans.factory.annotation.Autowired
	RunLogFollowing(RunLogReads reads) {
		this((run, stream, cursor) -> reads.read(run, stream, cursor, null), reads::requireRun);
	}

	RunLogFollowing(Reader reads, java.util.function.Consumer<UUID> requireRun) {
		this.reads = reads;
		this.requireRun = requireRun;
		timer.scheduleWithFixedDelay(() -> sessions.forEach(Session::tick), 250, 250, TimeUnit.MILLISECONDS);
	}

	void follow(UUID run, String stream, Long cursor, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		RunLogReads.validateStream(stream);
		requireRun.accept(run);
		if (!admission.tryAcquire())
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Archive viewer admission is full");
		Session session = null;
		try {
			response.setContentType("text/event-stream");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("X-Accel-Buffering", "no");
			var webRequest = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
			if (webRequest == null)
				throw new IllegalStateException("MVC asynchronous request is unavailable");
			webRequest.setTimeout(30_000L);
			webRequest.startAsync();
			var context = request.getAsyncContext();
			session = new Session(run, stream, cursor, context);
			sessions.add(session);
			context.addListener(session);
			session.output.setWriteListener(session);
		}
		catch (RuntimeException | IOException failure) {
			if (session == null)
				admission.release();
			else
				session.close();
			throw failure;
		}
	}

	@PreDestroy
	void close() {
		sessions.forEach(Session::close);
		timer.shutdownNow();
		workers.shutdownNow();
	}

	private final class Session implements WriteListener, AsyncListener {

		private final UUID run;

		private final String stream;

		private final AsyncContext context;

		private final ServletOutputStream output;

		private final long started = System.nanoTime();

		private Long cursor;

		private byte[] frame;

		private int written;

		private long pendingSince;

		private long nextRead;

		private Future<?> work;

		private boolean reading;

		private boolean terminal;

		private boolean closed;

		Session(UUID run, String stream, Long cursor, AsyncContext context) throws IOException {
			this.run = run;
			this.stream = stream;
			this.cursor = cursor;
			this.context = context;
			this.output = context.getResponse().getOutputStream();
		}

		synchronized void tick() {
			if (closed)
				return;
			long now = System.nanoTime();
			if (now - started >= TimeUnit.SECONDS.toNanos(30)
					|| frame != null && now - pendingSince >= TimeUnit.SECONDS.toNanos(5)) {
				close();
				return;
			}
			if (frame != null || reading || now < nextRead)
				return;
			reading = true;
			try {
				work = workers.submit(this::read);
			}
			catch (RejectedExecutionException full) {
				close();
			}
		}

		private void read() {
			try {
				var page = reads.read(run, stream, cursor);
				byte[] encoded = ((page.nextCursor() == null ? "" : "id: " + page.nextCursor() + "\n")
						+ "event: archive\ndata: " + JSON.writeValueAsString(page) + "\n\n")
					.getBytes(StandardCharsets.UTF_8);
				synchronized (this) {
					if (closed)
						return;
					if (encoded.length > MAX_FRAME)
						throw new IllegalStateException("Archive frame exceeds bound");
					if (page.nextCursor() != null)
						cursor = Long.parseLong(page.nextCursor());
					terminal = "finalized".equals(page.archiveState()) && page.nextCursor().equals(page.endCursor());
					frame = encoded;
					written = 0;
					pendingSince = System.nanoTime();
					nextRead = pendingSince + TimeUnit.MILLISECONDS.toNanos(page.bytesBase64().isEmpty() ? 2000 : 250);
					reading = false;
					pump();
				}
			}
			catch (RuntimeException | IOException failure) {
				close();
			}
		}

		private synchronized void pump() throws IOException {
			if (closed || frame == null)
				return;
			while (written < frame.length) {
				if (!output.isReady())
					return;
				int size = Math.min(8192, frame.length - written);
				output.write(frame, written, size);
				written += size;
			}
			if (!output.isReady())
				return;
			output.flush();
			if (!output.isReady())
				return;
			frame = null;
			if (terminal)
				close();
		}

		public void onWritePossible() throws IOException {
			pump();
		}

		public void onError(Throwable failure) {
			close();
		}

		public void onComplete(AsyncEvent event) {
			close();
		}

		public void onTimeout(AsyncEvent event) {
			close();
		}

		public void onError(AsyncEvent event) {
			close();
		}

		public void onStartAsync(AsyncEvent event) {
			close();
		}

		synchronized void close() {
			if (closed)
				return;
			closed = true;
			frame = null;
			if (work != null)
				work.cancel(true);
			sessions.remove(this);
			admission.release();
			try {
				context.complete();
			}
			catch (IllegalStateException alreadyComplete) {
				// The servlet container can complete first on disconnect or timeout.
			}
		}

	}

}
