package dev.skywright.spike;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GraalPySkyPilotSpike {

  public static void main(String[] args) {
    SpringApplication.run(GraalPySkyPilotSpike.class, args);
  }

  @Bean
  CommandLineRunner importProbe() {
    return args -> {
      Path resources = Path.of(System.getProperty(
          "graalpy.resources", "target/graalpy-resources")).toAbsolutePath();
      String posixBackend = System.getProperty("graalpy.posix", "java");

      System.out.printf("PROTOTYPE resources=%s posix=%s thread=%s virtual=%s%n",
          resources, posixBackend, Thread.currentThread(), Thread.currentThread().isVirtual());

      Instant contextStart = Instant.now();
      try (Context context = GraalPyResources.contextBuilder(resources)
          .allowAllAccess(true)
          .arguments("python", new String[] {"skywright-graalpy-spike"})
          .option("python.PosixModuleBackend", posixBackend)
          .build()) {
        long contextMillis = Duration.between(contextStart, Instant.now()).toMillis();
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
        long evalMillis = Duration.between(importStart, Instant.now()).toMillis();

        System.out.printf("IMPORT_RESULT context_ms=%d eval_ms=%d result=%s%n",
            contextMillis, evalMillis, result.asString());
      }
    };
  }
}
