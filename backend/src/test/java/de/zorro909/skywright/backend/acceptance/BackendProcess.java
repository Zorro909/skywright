package de.zorro909.skywright.backend.acceptance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class BackendProcess implements AutoCloseable {

	private final Process process;

	private final List<String> output = Collections.synchronizedList(new ArrayList<>());

	private final Thread outputReader;

	private volatile boolean closing;

	private BackendProcess(Process process) {
		this.process = process;
		this.outputReader = Thread.ofPlatform().daemon().start(this::readOutput);
	}

	static BackendProcess start(String... arguments) throws IOException {
		return start(Map.of(), List.of(), arguments);
	}

	static BackendProcess start(Map<String, String> environment, List<String> jvmArguments, String... arguments)
			throws IOException {
		return start(null, environment, jvmArguments, arguments);
	}

	static BackendProcess start(Path workingDirectory, Map<String, String> environment, List<String> jvmArguments,
			String... arguments) throws IOException {
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(jvmArguments);
		command.add("-jar");
		command.add(System.getProperty("backend.executable"));
		command.addAll(List.of(arguments));
		var processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
		if (workingDirectory != null) {
			processBuilder.directory(workingDirectory.toFile());
		}
		processBuilder.environment().putAll(environment);
		var process = processBuilder.start();
		return new BackendProcess(process);
	}

	boolean isAlive() {
		return process.isAlive();
	}

	static int availablePort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	static void awaitReadiness(int port, Duration timeout) throws InterruptedException {
		var client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/readyz")).GET().build();
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			try {
				var response = client.send(request, HttpResponse.BodyHandlers.discarding());
				if (response.statusCode() == 200) {
					return;
				}
			}
			catch (IOException ignored) {
				// The process has not bound its HTTP socket yet.
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("Backend did not become ready within " + timeout);
	}

	int awaitExit(Duration timeout) throws InterruptedException {
		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			throw new AssertionError("Backend process did not exit within " + timeout);
		}
		outputReader.join(timeout.toMillis());
		return process.exitValue();
	}

	String output() {
		synchronized (output) {
			return String.join("\n", output);
		}
	}

	private void readOutput() {
		try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.add(line);
			}
		}
		catch (IOException exception) {
			if (!closing && process.isAlive()) {
				throw new IllegalStateException("Could not capture backend output", exception);
			}
		}
	}

	@Override
	public void close() throws InterruptedException {
		closing = true;
		if (process.isAlive()) {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
		outputReader.join(Duration.ofSeconds(5));
	}

}
