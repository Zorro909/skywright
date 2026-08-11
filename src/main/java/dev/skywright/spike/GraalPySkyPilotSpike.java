package dev.skywright.spike;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GraalPySkyPilotSpike {

  private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(15);

  public static void main(String[] args) {
    SpringApplication.run(GraalPySkyPilotSpike.class, args);
  }

  @Bean
  CommandLineRunner probe() {
    return args -> {
      boolean halt = Boolean.getBoolean("spike.halt");
      Context context = buildContext();
      int exitCode = 0;
      try {
        if (System.getProperty("spike.mode", "import").equals("exercise")) {
          exerciseSdk(context);
        } else {
          importProbe(context);
        }
      } catch (Throwable failure) {
        exitCode = 1;
        failure.printStackTrace(System.err);
      } finally {
        if (halt) {
          System.out.printf("HALT exit=%d reason=avoid-native-extension-teardown-crash%n", exitCode);
          System.out.flush();
          System.err.flush();
          Runtime.getRuntime().halt(exitCode);
        }
        context.close();
      }
    };
  }

  private Context buildContext() {
    Path resources = Path.of(System.getProperty(
        "graalpy.resources", "target/graalpy-resources")).toAbsolutePath();
    String posixBackend = System.getProperty("graalpy.posix", "java");
    System.out.printf("PROTOTYPE resources=%s posix=%s thread=%s virtual=%s%n",
        resources, posixBackend, Thread.currentThread(), Thread.currentThread().isVirtual());
    return GraalPyResources.contextBuilder(resources)
        .allowAllAccess(true)
        .arguments("python", new String[] {"skywright-graalpy-spike"})
        .option("python.PosixModuleBackend", posixBackend)
        .build();
  }

  private void importProbe(Context context) {
    Instant importStart = Instant.now();
    Value result = context.eval("python", """
        import json
        import platform
        import sys
        import time
        import traceback

        sys.setrecursionlimit(10_000)
        started = time.perf_counter()
        try:
            import sky
            outcome = {
                "ok": True,
                "skypilot": sky.__version__,
            }
        except BaseException as failure:
            outcome = {
                "ok": False,
                "failure_type": type(failure).__name__,
                "failure": str(failure),
                "traceback": traceback.format_exc(),
            }

        outcome.update({
            "graalpy": platform.python_version(),
            "implementation": sys.implementation.name,
            "python_import_seconds": time.perf_counter() - started,
            "module_count": len(sys.modules),
        })
        json.dumps(outcome)
        """);
    long elapsedMillis = Duration.between(importStart, Instant.now()).toMillis();
    System.out.printf("IMPORT_RESULT eval_ms=%d result=%s%n", elapsedMillis, result.asString());
  }

  private void exerciseSdk(Context context) throws Exception {
    Instant importStart = Instant.now();
    context.eval("python", """
        import json
        import sys
        import sky

        sys.setrecursionlimit(10_000)

        def _optional(value):
            value = str(value)
            return value if value else None

        def spike_submit(definition, cluster_name):
            requested = definition.resources()
            resources = sky.Resources(
                infra=str(requested.infra()),
                cpus=str(requested.cpus()),
                memory=str(requested.memory()),
                image_id=_optional(requested.imageId()),
            )
            task = sky.Task(
                name=str(definition.name()),
                setup=_optional(definition.setup()),
                run=str(definition.run()),
                resources=resources,
            )
            request_id = sky.launch(task, cluster_name=str(cluster_name))
            return request_id, json.dumps(task.to_yaml_config(), sort_keys=True)

        def spike_await(request_id):
            return sky.stream_and_get(str(request_id))

        def spike_status(cluster_name):
            request_id = sky.status(cluster_names=[str(cluster_name)])
            return sky.stream_and_get(request_id)

        def spike_down(cluster_name):
            return sky.stream_and_get(sky.down(str(cluster_name)))
        """);
    long importMillis = Duration.between(importStart, Instant.now()).toMillis();

    Value bindings = context.getBindings("python");
    Value submit = bindings.getMember("spike_submit");
    Value await = bindings.getMember("spike_await");
    Value status = bindings.getMember("spike_status");
    Value down = bindings.getMember("spike_down");

    String clusterName = System.getProperty("spike.cluster", "skywright-graalpy-spike");
    RunDefinition definition = new RunDefinition(
        "graalpy-boundary",
        "",
        "python -c \"print('graalpy boundary reached')\"",
        new ResourceDefinition(
            requiredProperty("spike.infra"),
            System.getProperty("spike.cpus", "1"),
            System.getProperty("spike.memory", "1"),
            System.getProperty("spike.image", "")));

    Value submitted = submit.execute(definition, clusterName);
    Value typedRequestId = submitted.getArrayElement(0);
    Operation operation = new Operation("launch", typedRequestId.asString(), typedRequestId);
    String taskSpecification = submitted.getArrayElement(1).asString();
    System.out.printf("SUBMITTED import_ms=%d operation=%s task_spec=%s%n",
        importMillis, operation, taskSpecification);

    CountDownLatch streamStarted = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(
        2, Thread.ofPlatform().name("graalpy-platform-", 0).factory())) {
      Instant streamStart = Instant.now();
      Future<LaunchResult> launch = executor.submit(() -> {
        assertPlatformThread();
        streamStarted.countDown();
        Value result = await.execute(operation.requestId());
        return readLaunchResult(result, Duration.between(streamStart, Instant.now()));
      });

      if (!streamStarted.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("launch stream did not start");
      }
      Thread.sleep(1_000);

      Instant controlStart = Instant.now();
      Future<StatusQueryResult> control = executor.submit(() -> {
        assertPlatformThread();
        return readStatusQuery(status.execute(clusterName));
      });
      StatusQueryResult duringLaunch = control.get(
          CONTROL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      Duration controlDuration = Duration.between(controlStart, Instant.now());
      boolean streamStillHeld = !launch.isDone();
      System.out.printf(
          "ONE_CONTEXT_CONCURRENCY control_ms=%d stream_still_held=%s control=%s%n",
          controlDuration.toMillis(), streamStillHeld, duringLaunch);

      LaunchResult launchResult = launch.get(LAUNCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      StatusQueryResult finalQuery = readStatusQuery(status.execute(clusterName));
      if (finalQuery.snapshot() == null) {
        throw new IllegalStateException("launched cluster absent from final status");
      }
      System.out.printf("LAUNCH_RESULT %s%n", launchResult);
      System.out.printf("TYPED_STATUS %s%n", finalQuery.snapshot());
      if (!streamStillHeld) {
        throw new IllegalStateException("control call did not overlap the held stream");
      }
    } finally {
      System.out.printf("CLEANUP cluster=%s%n", clusterName);
      down.execute(clusterName);
    }
  }

  private static LaunchResult readLaunchResult(Value result, Duration duration) {
    Value jobId = result.getArrayElement(0);
    Value handle = result.getArrayElement(1);
    return new LaunchResult(
        jobId.isNull() ? null : jobId.asLong(),
        readHandle(handle),
        duration.toMillis(),
        Thread.currentThread().getName(),
        Thread.currentThread().isVirtual());
  }

  private static StatusQueryResult readStatusQuery(Value records) {
    if (!records.hasArrayElements() || records.getArraySize() > 1) {
      throw new IllegalStateException("expected zero or one status record, got " + records);
    }
    if (records.getArraySize() == 0) {
      return new StatusQueryResult(0, null);
    }
    Value record = records.getArrayElement(0);
    Value status = field(record, "status");
    return new StatusQueryResult(1, new ClusterSnapshot(
        field(record, "name").asString(),
        status.getMember("value").asString(),
        readHandle(field(record, "handle")),
        Thread.currentThread().getName(),
        Thread.currentThread().isVirtual()));
  }

  private static Value field(Value object, String name) {
    if (object.hasHashEntries()) {
      return object.getHashValue(name);
    }
    if (object.hasMember(name)) {
      return object.getMember(name);
    }
    throw new IllegalStateException(
        "guest value has no field '" + name + "': " + object.getMetaObject());
  }

  private static HandleSnapshot readHandle(Value handle) {
    return new HandleSnapshot(
        handle.getMetaObject().getMetaQualifiedName(),
        handle.getMember("cluster_name").asString(),
        handle.getMember("cluster_name_on_cloud").asString(),
        handle.getMember("launched_nodes").asInt(),
        handle.getMember("launched_resources").toString());
  }

  private static void assertPlatformThread() {
    if (Thread.currentThread().isVirtual()) {
      throw new IllegalStateException("GraalPy work reached a virtual thread");
    }
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing -D" + name);
    }
    return value;
  }

  public record ResourceDefinition(String infra, String cpus, String memory, String imageId) {}

  public record RunDefinition(
      String name, String setup, String run, ResourceDefinition resources) {}

  public record Operation(String kind, String requestId, Value typedRequestId) {
    @Override
    public String toString() {
      return "Operation[kind=" + kind + ", requestId=" + requestId
          + ", guestType=" + typedRequestId.getMetaObject().getMetaQualifiedName() + "]";
    }
  }

  public record HandleSnapshot(
      String type, String clusterName, String clusterNameOnCloud,
      int launchedNodes, String launchedResources) {}

  public record ClusterSnapshot(
      String name, String status, HandleSnapshot handle,
      String thread, boolean virtual) {}

  public record StatusQueryResult(int recordCount, ClusterSnapshot snapshot) {}

  public record LaunchResult(
      Long jobId, HandleSnapshot handle, long streamMillis,
      String thread, boolean virtual) {}
}
