package de.zorro909.skywright.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
final class SkyPilotApiServerImageIT {

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final String databasePassword = "db-" + UUID.randomUUID();

	private String network;

	private String databaseContainer;

	private String stateVolume;

	private String serverContainer;

	private URI baseUri;

	@BeforeAll
	void startProductionImage() throws Exception {
		network = name("network");
		databaseContainer = name("database");
		stateVolume = name("state");
		serverContainer = name("server");
		docker("network", "create", network);
		docker("volume", "create", stateVolume);
		docker("run", "--detach", "--name", databaseContainer, "--network", network, "--env", "POSTGRES_DB=skypilot",
				"--env", "POSTGRES_USER=skypilot", "--env", "POSTGRES_PASSWORD=" + databasePassword, postgresqlImage());
		awaitPostgreSql(databaseContainer, STARTUP_TIMEOUT);

		docker(serverArguments(serverContainer).toArray(String[]::new));
		var port = awaitPublishedPort(serverContainer, STARTUP_TIMEOUT);
		baseUri = URI.create("http://127.0.0.1:" + port);
		awaitHealth(STARTUP_TIMEOUT);
	}

	@AfterAll
	void cleanUp() {
		removeContainer(serverContainer);
		removeContainer(databaseContainer);
		if (stateVolume != null) {
			dockerIgnoringFailure("volume", "rm", stateVolume);
		}
		if (network != null) {
			dockerIgnoringFailure("network", "rm", network);
		}
	}

	@Test
	@Order(1)
	void productionArtifactHasTheExpectedIdentityAndRuntimeContract() throws Exception {
		var health = JSON.readTree(get("/api/health"));
		assertThat(health.path("status").asText()).isEqualTo("healthy");
		assertThat(health.path("version").asText()).isEqualTo(skyPilotVersion());
		assertThat(health.path("version_on_disk").asText()).isEqualTo(skyPilotVersion());

		var pidOneCommand = docker("exec", serverContainer, "python", "-c",
				"from pathlib import Path; print(Path('/proc/1/cmdline').read_bytes().replace(b'\\0', b' ').decode())");
		assertThat(pidOneCommand).contains("/usr/bin/tini -- python -I -S /opt/skywright/runtime/launch.py",
				"--host=0.0.0.0", "--port=46580");
		var childCommands = docker("exec", serverContainer, "python", "-c", """
				from pathlib import Path
				for pid in Path('/proc/1/task/1/children').read_text().split():
				    try:
				        print(Path(f'/proc/{pid}/cmdline').read_bytes().replace(b'\\0', b' ').decode())
				    except FileNotFoundError:
				        pass
				""");
		assertThat(childCommands).contains("python -m sky.server.server --host=0.0.0.0 --port=46580");

		assertThat(inspectImage("{{.Config.User}}").strip()).isEqualTo("10002:10002");
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.revision\"}}").strip())
			.isEqualTo(sourceRevision());
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.version\"}}").strip())
			.isEqualTo("0.1.0-SNAPSHOT");
		assertThat(inspectImage("{{index .Config.Labels \"io.skywright.python.version\"}}").strip())
			.isEqualTo(pythonVersion());
		assertThat(inspectImage("{{index .Config.Labels \"io.skywright.skypilot.version\"}}").strip())
			.isEqualTo(skyPilotVersion());
		assertThat(inspectImage("{{index .Config.Labels \"org.opencontainers.image.base.digest\"}}").strip())
			.isEqualTo("sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7");
		assertThat(inspectImage("{{json .Config.Env}}").strip()).contains("OPENBLAS_NUM_THREADS=1")
			.doesNotContain("SKYPILOT_DB_CONNECTION_URI", "postgresql://");
		assertThat(docker("logs", serverContainer)).doesNotContain(databasePassword);
	}

	@Test
	@Order(1)
	void orphanedChildrenAreReaped() throws Exception {
		assertThat(docker("exec", serverContainer, "python", "-I", "-c", """
				import os, time
				from pathlib import Path
				orphans = []
				for _ in range(20):
				    reader, writer = os.pipe()
				    child = os.fork()
				    if child == 0:
				        os.close(reader)
				        orphan = os.fork()
				        if orphan == 0:
				            os.close(writer)
				            while os.getppid() != 1:
				                time.sleep(0.01)
				            os._exit(0)
				        os.write(writer, str(orphan).encode())
				        os._exit(0)
				    os.close(writer)
				    orphans.append(int(os.read(reader, 32)))
				    os.close(reader)
				    os.waitpid(child, 0)
				deadline = time.monotonic() + 5
				while time.monotonic() < deadline:
				    remaining = [pid for pid in orphans if Path(f'/proc/{pid}').exists()]
				    if not remaining:
				        break
				    time.sleep(0.01)
				assert not remaining, f'orphaned children were not reaped: {remaining}'
				print('orphaned children reaped')
				""")).contains("orphaned children reaped");
	}

	@Test
	@Order(1)
	void managedKubernetesLaunchExecutablesRunAsTheServiceUser() throws Exception {
		assertThat(docker("exec", serverContainer, "python", "-I", "-c", """
				import json, subprocess, tempfile
				from pathlib import Path
				with tempfile.TemporaryDirectory() as directory:
				    root = Path(directory)
				    subprocess.run(['git', 'init', str(root / 'repository')], check=True, capture_output=True)
				    subprocess.run(['ssh', '-V'], check=True, capture_output=True)
				    subprocess.run(['socat', '-V'], check=True, capture_output=True)
				    subprocess.run(['nc', '-h'], check=True, capture_output=True)
				    subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(root / 'key')], check=True)
				    (root / 'source').write_text('managed-launch')
				    subprocess.run(['rsync', str(root / 'source'), str(root / 'copy')], check=True)
				    assert (root / 'copy').read_text() == 'managed-launch'
				    version = json.loads(subprocess.check_output(['kubectl', 'version', '--client', '-o', 'json']))
				    assert version['clientVersion']['gitVersion'] == 'v1.37.0'
				from sky.provision.kubernetes.utils import check_port_forward_mode_dependencies
				assert check_port_forward_mode_dependencies() is None
				print('managed Kubernetes launch tools verified')
				""")).contains("managed Kubernetes launch tools verified");
	}

	@Test
	@Order(1)
	void packagedPullHelperUsesThePinnedKubernetesClientAndPreservesImmutableSecrets() throws Exception {
		assertThat(docker("exec", serverContainer, "python", "-I", "-c",
				"""
						import json, runpy, threading
						from http.server import BaseHTTPRequestHandler, HTTPServer
						module = runpy.run_path('/opt/skywright/runtime/runtime_pull.py')
						stored = {}
						class Handler(BaseHTTPRequestHandler):
						    def log_message(self, *args): pass
						    def do_POST(self):
						        assert self.headers['Authorization'] == 'Bearer role-fixture'
						        assert self.path == '/api/v1/namespaces/training/secrets'
						        value = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
						        if stored:
						            self.send_response(409); self.end_headers(); return
						        stored.update(value)
						        self.reply(value)
						    def do_GET(self):
						        assert self.path.endswith('/secrets/skywright-pull-fixture')
						        self.reply(stored)
						    def reply(self, value):
						        body = json.dumps(value).encode()
						        self.send_response(200)
						        self.send_header('Content-Type', 'application/json')
						        self.send_header('Content-Length', str(len(body)))
						        self.end_headers(); self.wfile.write(body)
						server = HTTPServer(('127.0.0.1', 0), Handler)
						threading.Thread(target=server.serve_forever, daemon=True).start()
						config = {'apiVersion': 'v1', 'kind': 'Config', 'current-context': 'local',
						    'clusters': [{'name': 'cluster', 'cluster': {'server': 'http://127.0.0.1:' + str(server.server_port)}}],
						    'contexts': [{'name': 'local', 'context': {'cluster': 'cluster', 'user': 'role'}}],
						    'users': [{'name': 'role', 'user': {'token': 'role-fixture'}}]}
						api = module['KubernetesSecrets'](config, 'local')
						secret = {'apiVersion': 'v1', 'kind': 'Secret', 'immutable': True, 'type': 'kubernetes.io/dockerconfigjson',
						    'metadata': {'name': 'skywright-pull-fixture', 'namespace': 'training'},
						    'data': {'.dockerconfigjson': 'fixture'}}
						try:
						    api.create('training', secret)
						    api.create('training', dict(secret, data={'.dockerconfigjson': 'changed'}))
						    found = api.read('training', 'skywright-pull-fixture')
						    assert found['immutable'] and found['data'] == secret['data']
						    print('immutable helper delivery verified')
						finally:
						    api.close(); server.shutdown(); server.server_close()
						"""))
			.contains("immutable helper delivery verified");
	}

	@Test
	@Order(2)
	void missingExternalDatabaseConfigurationFailsSafely() throws Exception {
		var container = name("missing-database");
		try {
			docker("create", "--name", container, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m", "--volume",
					stateVolume + ":/var/lib/skypilot", "--env", "PYTHONPATH=/tmp/operator-override", imageName());
			docker("start", container);

			assertThat(awaitContainerExit(container, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", container)).contains("SKYPILOT_DB_CONNECTION_URI is required");
		}
		finally {
			removeContainer(container);
		}

		var invalidContainer = name("invalid-database");
		var sensitiveValue = "private-" + UUID.randomUUID();
		try {
			docker("create", "--name", invalidContainer, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m",
					"--volume", stateVolume + ":/var/lib/skypilot", "--env",
					"SKYPILOT_DB_CONNECTION_URI=not-a-postgresql-uri-" + sensitiveValue, imageName());
			docker("start", invalidContainer);

			assertThat(awaitContainerExit(invalidContainer, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", invalidContainer))
				.contains("SKYPILOT_DB_CONNECTION_URI must be a PostgreSQL URI")
				.doesNotContain(sensitiveValue);
		}
		finally {
			removeContainer(invalidContainer);
		}

		var missingStateContainer = name("missing-state");
		var stateProbePassword = "state-" + UUID.randomUUID();
		try {
			docker("create", "--name", missingStateContainer, "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=64m",
					"--env", "SKYPILOT_DB_CONNECTION_URI=postgresql://skypilot:" + stateProbePassword
							+ "@unreachable.invalid/skypilot",
					imageName());
			docker("start", missingStateContainer);

			assertThat(awaitContainerExit(missingStateContainer, Duration.ofSeconds(10))).isEqualTo("78");
			assertThat(dockerCombinedOutput("logs", missingStateContainer))
				.contains("writable runtime path is unavailable: /var/lib/skypilot/.sky")
				.doesNotContain(stateProbePassword);
		}
		finally {
			removeContainer(missingStateContainer);
		}
	}

	@Test
	@Order(3)
	void publicDatabaseAndSubmittedFileStateSurviveContainerReplacement() throws Exception {
		var username = "restart-" + UUID.randomUUID();
		var userPassword = "user-" + UUID.randomUUID();
		var createUser = postJson("/users/create",
				"{\"username\":\"" + username + "\",\"password\":\"" + userPassword + "\",\"role\":\"user\"}");
		assertThat(createUser.statusCode()).withFailMessage("user creation failed: %s", createUser.body())
			.isEqualTo(200);

		var blobId = "a".repeat(64);
		var upload = postBytes(
				"/upload_v2?user_hash=restart-test&upload_id=" + blobId + "&chunk_index=0&total_chunks=1",
				zip("retained.txt", "survives replacement"));
		assertThat(upload.statusCode()).withFailMessage("blob upload failed: %s", upload.body()).isEqualTo(200);
		assertThat(upload.body()).contains("completed");
		assertThat(JSON.readTree(get("/upload_v2/blob?user_hash=restart-test&blob_id=" + blobId))
			.path("exists")
			.asBoolean()).isTrue();

		var stoppedContainer = serverContainer;
		var pidOne = Long.parseLong(docker("inspect", "--format", "{{.State.Pid}}", stoppedContainer).strip());
		var serverProcesses = containerProcesses(stoppedContainer, pidOne);
		assertThat(serverProcesses).filteredOn(process -> process.hostPid() == pidOne && process.containerPid() == 1)
			.hasSize(1);
		assertThat(serverProcesses).filteredOn(process -> process.containerPid() != 1).isNotEmpty();
		var started = Instant.now();
		docker("kill", "--signal=TERM", stoppedContainer);
		awaitProcessExit(serverProcesses, Duration.ofSeconds(25));
		assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(25));
		assertThat(awaitContainerExit(stoppedContainer, Duration.ofSeconds(1))).isIn("0", "143");
		assertThat(docker("inspect", "--format", "{{.State.ExitCode}}", stoppedContainer).strip()).isIn("0", "143");
		assertThat(docker("inspect", "--format", "{{.State.Pid}}", stoppedContainer).strip()).isEqualTo("0");
		docker("rm", stoppedContainer);

		serverContainer = name("replacement");
		docker(serverArguments(serverContainer).toArray(String[]::new));
		var port = awaitPublishedPort(serverContainer, STARTUP_TIMEOUT);
		baseUri = URI.create("http://127.0.0.1:" + port);
		awaitHealth(STARTUP_TIMEOUT);

		var users = JSON.readTree(get("/users"));
		assertThat(users).anySatisfy(user -> assertThat(user.path("name").asText()).isEqualTo(username));
		assertThat(JSON.readTree(get("/upload_v2/blob?user_hash=restart-test&blob_id=" + blobId))
			.path("exists")
			.asBoolean()).isTrue();
		assertThat(docker("logs", serverContainer)).doesNotContain(databasePassword, userPassword);
	}

	@Test
	@Order(4)
	void controllerCanObserveAMissingTargetPod() throws Exception {
		String probe = java.nio.file.Files
			.readString(java.nio.file.Path.of("src/test/resources/controller_missing_pod_probe.py"));
		assertThat(docker("exec", serverContainer, "python", "-I", "-c", probe))
			.contains("Missing target Pod remains a recoverable status observation");
	}

	@Test
	@Order(4)
	void collectorReadsPinnedPostgresAndRawFilesWithReadOnlyStateAndNoSdkImport() throws Exception {
		String run = "38c76a5b-7cba-400e-9595-7657b194ea83";
		String seed = """
				import os, sys, time, uuid
				from pathlib import Path
				sys.path.insert(0, '/opt/skywright/runtime')
				from log_collector import cloud_name, managed_cluster_name
				from sky.jobs.utils import generate_managed_job_cluster_name
				from sky.jobs import log_gc
				from sky.utils.common_utils import make_cluster_name_on_cloud
				from sky.utils import common_utils
				import psycopg2
				name = 'skywright-38c76a5b-7cba-400e-9595-7657b194ea83'
				for i in range(100):
				    candidate = 'skywright-' + str(uuid.UUID(int=i))
				    cluster = generate_managed_job_cluster_name(candidate, 91062 + i)
				    assert managed_cluster_name(candidate, 91062 + i) == cluster
				    assert cloud_name(cluster, 42, common_utils.get_user_hash()) == make_cluster_name_on_cloud(cluster, 42)
				root = Path('/var/lib/skypilot/sky_logs')
				(root / 'jobs_controller').mkdir(exist_ok=True)
				raw = bytes([27, 91, 51, 49, 109, 255, 0, 13, 10, 120])
				(root / 'jobs_controller/91062.log').write_bytes(raw)
				snapshot = root / 'collector-fixture-run.log'
				snapshot.write_bytes(raw)
				with psycopg2.connect(os.environ['SKYPILOT_DB_CONNECTION_URI']) as connection, connection.cursor() as cursor:
				    cursor.execute("INSERT INTO job_info(spot_job_id,name,schedule_state,user_hash) VALUES (91062,%s,'DONE','fixture')", (name,))
				    cursor.execute("INSERT INTO spot(spot_job_id,task_id,task_name,status,end_at,local_log_file) VALUES (91062,0,%s,'FAILED_SETUP',%s,%s)", (name,time.time(),str(snapshot)))
				# A recent terminal fixture must survive the live server's background GC.
				log_gc._clean_task_logs_with_retention(log_gc._DEFAULT_TASK_LOGS_GC_RETENTION_HOURS * 3600)
				log_gc._clean_controller_logs_with_retention(log_gc._DEFAULT_CONTROLLER_LOGS_GC_RETENTION_HOURS * 3600)
				assert snapshot.exists(), 'fixture was eligible for production log cleanup'
				print('fixture ready; naming protocol matches')
				""";
		assertThat(docker("exec", serverContainer, "python", "-I", "-c", seed)).contains("naming protocol matches");
		String check = """
				import sys, base64
				sys.path.insert(0, '/opt/skywright/runtime')
				from log_collector import Sources
				assert 'sky' not in sys.modules
				sources = Sources()
				with sources._connection() as connection, connection.cursor() as cursor:
				    cursor.execute('SHOW transaction_read_only')
				    assert cursor.fetchone()[0] == 'on'
				raw = bytes([27, 91, 51, 49, 109, 255, 0, 13, 10, 120])
				for stream in ('task', 'controller'):
				    cursor, captured, offset = {}, b'', 0
				    for i in range(8):
				        page = sources.page({'runId':'RUN_ID','stream':stream,'limit':3,'cursor':cursor,'offset':offset})
				        captured += base64.b64decode(page['bytes'])
				        cursor = page['cursor']
				        offset = page['offset'] + len(base64.b64decode(page['bytes']))
				        if page['sealed']:
				            assert page['finalSource']
				            break
				    assert captured == raw, (stream, captured)
				assert 'sky' not in sys.modules
				print('read-only collector preserves raw bytes')
				"""
			.replace("RUN_ID", run);
		String uri = "postgresql://skypilot:" + databasePassword + "@" + databaseContainer + ":5432/skypilot";
		assertThat(docker("run", "--rm", "--read-only", "--network", network, "--volume",
				stateVolume + ":/var/lib/skypilot:ro", "--tmpfs", "/tmp:rw,nosuid,size=32m", "--env",
				"SKYPILOT_DB_CONNECTION_URI=" + uri, "--entrypoint", "python", imageName(), "-I", "-c", check))
			.contains("read-only collector preserves raw bytes");
	}

	@Test
	@Order(5)
	void collectorUsesActualKubernetesTlsAndExecProtocolForTheFixedReadOnlyProgram() throws Exception {
		String probe = java.nio.file.Files
			.readString(java.nio.file.Path.of("src/test/resources/collector_kubernetes_probe.py"));
		String uri = "postgresql://skypilot:" + databasePassword + "@" + databaseContainer + ":5432/skypilot";
		assertThat(docker("run", "--rm", "--read-only", "--network", network, "--tmpfs", "/tmp:rw,nosuid,size=32m",
				"--env", "SKYPILOT_DB_CONNECTION_URI=" + uri, "--entrypoint", "python", imageName(), "-I", "-c", probe))
			.contains("Kubernetes raw-byte protocol qualified");
	}

	private ArrayList<String> serverArguments(String container) {
		var databaseUri = "postgresql://skypilot:" + databasePassword + "@" + databaseContainer + ":5432/skypilot";
		return new ArrayList<>(List.of("run", "--detach", "--name", container, "--network", network, "--read-only",
				"--cpus", "2", "--memory", "4g", "--memory-swap", "4g", "--pids-limit", "192", "--tmpfs",
				"/tmp:rw,exec,nosuid,size=64m", "--volume", stateVolume + ":/var/lib/skypilot", "--env",
				"SKYPILOT_DB_CONNECTION_URI=" + databaseUri, "--publish", "127.0.0.1::46580", imageName()));
	}

	private String get(String path) throws IOException, InterruptedException {
		var response = httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
		return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postBytes(String path, byte[] body) throws IOException, InterruptedException {
		return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path))
			.header("Content-Type", "application/zip")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static byte[] zip(String name, String content) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry(name));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private void awaitHealth(Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			try {
				var response = httpClient.send(HttpRequest.newBuilder(baseUri.resolve("/api/health")).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200 && response.body().contains("\"status\":\"healthy\"")) {
					return;
				}
			}
			catch (IOException ignored) {
				// The server is still starting.
			}
			Thread.sleep(Duration.ofMillis(250));
		}
		throw new AssertionError("SkyPilot did not become healthy:\n" + dockerCombinedOutput("logs", serverContainer));
	}

	private static int awaitPublishedPort(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var published = docker("port", container, "46580/tcp").strip();
			if (!published.isBlank()) {
				return Integer.parseInt(published.substring(published.lastIndexOf(':') + 1));
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container runtime did not publish port 46580");
	}

	private static void awaitPostgreSql(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		Instant continuouslyReadySince = null;
		while (Instant.now().isBefore(deadline)) {
			try {
				docker("exec", container, "pg_isready", "--username", "skypilot", "--dbname", "skypilot");
				if (continuouslyReadySince == null) {
					continuouslyReadySince = Instant.now();
				}
				else if (Duration.between(continuouslyReadySince, Instant.now())
					.compareTo(Duration.ofSeconds(1)) >= 0) {
					return;
				}
			}
			catch (AssertionError ignored) {
				// PostgreSQL is still starting.
				continuouslyReadySince = null;
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("PostgreSQL did not become ready");
	}

	private static String awaitContainerExit(String container, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var running = docker("inspect", "--format", "{{.State.Running}}", container).strip();
			if (running.equals("false")) {
				return docker("inspect", "--format", "{{.State.ExitCode}}", container).strip();
			}
			Thread.sleep(Duration.ofMillis(100));
		}
		throw new AssertionError("Container did not stop within " + timeout);
	}

	private static List<ContainerProcess> containerProcesses(String container, long pidOne) throws Exception {
		try {
			return podmanContainerProcesses(container);
		}
		catch (AssertionError unsupportedPodmanDescriptors) {
			return dockerContainerProcesses(container, pidOne);
		}
	}

	private static List<ContainerProcess> podmanContainerProcesses(String container) throws Exception {
		var processes = parseContainerProcesses(
				docker("top", container, "hpid", "pid", "ppid").lines().skip(1).toList(), true, -1);
		if (processes.stream().noneMatch(process -> process.containerPid() == 1)) {
			throw new AssertionError("container PID 1 was absent from Podman process inventory");
		}
		return processes;
	}

	private static List<ContainerProcess> dockerContainerProcesses(String container, long pidOne) throws Exception {
		return parseContainerProcesses(docker("top", container, "-eo", "pid,ppid").lines().skip(1).toList(), false,
				pidOne);
	}

	private static List<ContainerProcess> parseContainerProcesses(List<String> lines, boolean includesContainerPid,
			long pidOne) {
		var processes = new ArrayList<ContainerProcess>();
		for (var line : lines) {
			var columns = line.strip().split("\\s+");
			if (columns.length != (includesContainerPid ? 3 : 2)) {
				throw new AssertionError("Unexpected docker top row: " + line);
			}
			var hostPid = Long.parseLong(columns[0]);
			var containerPid = includesContainerPid ? Long.parseLong(columns[1]) : hostPid == pidOne ? 1 : -1;
			var parentPid = Long.parseLong(columns[includesContainerPid ? 2 : 1]);
			processes.add(new ContainerProcess(hostPid, containerPid, parentPid));
		}
		return List.copyOf(processes);
	}

	private static void awaitProcessExit(List<ContainerProcess> processes, Duration timeout) throws Exception {
		var deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var live = processes.stream().filter(ContainerProcess::isAlive).toList();
			if (live.isEmpty()) {
				return;
			}
			Thread.sleep(Duration.ofMillis(50));
		}
		throw new AssertionError("Server container PID 1 or descendants survived SIGTERM: "
				+ processes.stream().filter(ContainerProcess::isAlive).toList());
	}

	private static String inspectImage(String format) throws Exception {
		return docker("image", "inspect", "--format", format, imageName());
	}

	private static String docker(String... arguments) throws Exception {
		return docker(false, arguments);
	}

	private static String dockerCombinedOutput(String... arguments) throws Exception {
		return docker(true, arguments);
	}

	private static String docker(boolean combineOutput, String... arguments) throws Exception {
		var command = new ArrayList<String>();
		command.add("docker");
		command.addAll(Arrays.asList(arguments));
		var process = new ProcessBuilder(command).redirectErrorStream(combineOutput).start();
		var standardOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		var errorOutput = combineOutput ? ""
				: new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		var exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new AssertionError(String.join(" ", command) + " exited " + exitCode + ":\n"
					+ (combineOutput ? standardOutput : errorOutput));
		}
		return standardOutput;
	}

	private static void removeContainer(String container) {
		if (container != null) {
			dockerIgnoringFailure("rm", "--force", container);
		}
	}

	private static void dockerIgnoringFailure(String... arguments) {
		try {
			docker(arguments);
		}
		catch (Exception | AssertionError ignored) {
			// Best-effort cleanup preserves the original test failure.
		}
	}

	private static String name(String purpose) {
		return "skywright-skypilot-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private record ContainerProcess(long hostPid, long containerPid, long parentPid) {

		boolean isAlive() {
			return ProcessHandle.of(this.hostPid).map(ProcessHandle::isAlive).orElse(false);
		}

	}

	private static String imageName() {
		return System.getProperty("image.name");
	}

	private static String postgresqlImage() {
		return System.getProperty("postgresql.container.image");
	}

	private static String sourceRevision() {
		return System.getProperty("source.revision");
	}

	private static String skyPilotVersion() {
		return System.getProperty("skypilot.version");
	}

	private static String pythonVersion() {
		return System.getProperty("python.version");
	}

}
