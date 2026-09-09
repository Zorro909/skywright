package de.zorro909.skywright.backend.orchestration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/** Holds one actual SDK stream at the wire while forwarding other real API calls. */
final class HeldSkyPilotProxy implements AutoCloseable {

	private static final Set<String> HOP_HEADERS = Set.of("host", "connection", "content-length", "transfer-encoding",
			"upgrade", "http2-settings", "expect");

	private final HttpServer server;

	private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(8);

	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final URI upstream;

	private volatile String heldRequest;

	private volatile CountDownLatch entered = new CountDownLatch(1);

	private volatile CountDownLatch release = new CountDownLatch(1);

	private volatile boolean holdControl;

	private final CountDownLatch controlEntered = new CountDownLatch(1);

	private final AtomicInteger requests = new AtomicInteger();

	private volatile ConcurrentLinkedQueue<RequestTiming> cancellationRequests;

	record RequestTiming(String method, String path, int status, long upstreamMillis) {
	}

	void beginCancellation() {
		this.cancellationRequests = new ConcurrentLinkedQueue<>();
	}

	List<RequestTiming> finishCancellation() {
		var captured = this.cancellationRequests;
		this.cancellationRequests = null;
		return captured == null ? List.of() : List.copyOf(captured);
	}

	HeldSkyPilotProxy(URI upstream) throws IOException {
		this(upstream, null);
	}

	HeldSkyPilotProxy(URI upstream, SSLContext tls) throws IOException {
		this.upstream = upstream;
		var address = new InetSocketAddress("127.0.0.1", 0);
		if (tls == null) {
			this.server = HttpServer.create(address, 0);
		}
		else {
			var https = HttpsServer.create(address, 0);
			https.setHttpsConfigurator(new HttpsConfigurator(tls));
			this.server = https;
		}
		this.server.setExecutor(this.executor);
		this.server.createContext("/", this::forward);
		this.server.start();
	}

	URI endpoint() {
		var scheme = this.server instanceof HttpsServer ? "https" : "http";
		return URI.create(scheme + "://127.0.0.1:" + this.server.getAddress().getPort());
	}

	int requests() {
		return this.requests.get();
	}

	void hold(String requestId) {
		this.entered = new CountDownLatch(1);
		this.release = new CountDownLatch(1);
		this.heldRequest = requestId;
	}

	boolean awaitHeld(Duration timeout) throws InterruptedException {
		return this.entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	void holdControl() {
		this.holdControl = true;
	}

	boolean awaitControl(Duration timeout) throws InterruptedException {
		return this.controlEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	void release() {
		this.release.countDown();
	}

	private void forward(HttpExchange exchange) throws IOException {
		this.requests.incrementAndGet();
		var captured = this.cancellationRequests;
		try (exchange) {
			var uri = exchange.getRequestURI();
			if (this.holdControl && "/jobs/queue/v2".equals(uri.getPath())) {
				this.controlEntered.countDown();
				if (!this.release.await(30, TimeUnit.SECONDS)) {
					throw new IOException("qualification control request was not released");
				}
			}
			if ("/api/stream".equals(uri.getPath()) && this.heldRequest != null
					&& uri.getRawQuery().contains("request_id=" + this.heldRequest)) {
				this.entered.countDown();
				if (!this.release.await(30, TimeUnit.SECONDS)) {
					throw new IOException("qualification stream was not released");
				}
			}
			var request = HttpRequest.newBuilder(this.upstream.resolve(uri.toString()))
				.method(exchange.getRequestMethod(),
						HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
			exchange.getRequestHeaders().forEach((name, values) -> {
				if (!HOP_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
					values.forEach(value -> request.header(name, value));
				}
			});
			HttpResponse<byte[]> response;
			var started = System.nanoTime();
			try {
				response = this.client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
			}
			catch (IOException unavailable) {
				exchange.sendResponseHeaders(503, -1);
				return;
			}
			if (captured != null) {
				captured.add(new RequestTiming(exchange.getRequestMethod(), uri.getPath(), response.statusCode(),
						TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
			}
			response.headers().map().forEach((name, values) -> {
				if (!HOP_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
					exchange.getResponseHeaders().put(name, values);
				}
			});
			exchange.sendResponseHeaders(response.statusCode(), response.body().length);
			exchange.getResponseBody().write(response.body());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		release();
		this.server.stop(0);
		this.executor.shutdownNow();
		this.client.close();
	}

}
