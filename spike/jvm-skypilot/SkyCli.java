// PROTOTYPE — THROWAWAY. Spike for issue #18 (map #1). Does not merge.
// The comparison arm: same operations, but through the `sky` CLI as a subprocess.
// The question is not "does it work" (it does) but "what does the contract feel like".

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SkyCli {

    static final String SKY = System.getenv().getOrDefault("SKY_BIN", "sky");
    static final String CLUSTER = "spike-jvm-cli";

    public static void main(String[] args) throws Exception {
        step("1. a read with JSON output — the good case");
        // The flag is -o/--output json (NOT --format; that does not exist in 0.13.0).
        // Available on: status, jobs queue, api status, cost-report. Not on launch/logs/check/down.
        // FINDING: this output carries NO pickles — the CLI decodes the handle that the REST
        // surface hands back as an opaque Python object. See README finding 5 vs the CLI section.
        run(List.of(SKY, "status", "-o", "json"), 20);

        step("2. a read WITHOUT machine output — the common case");
        // No --format json here: the JVM would have to scrape a human table.
        run(List.of(SKY, "check", "kubernetes"), 15);

        step("3. launch — the task still has to be a YAML FILE on disk");
        // Same opaque YAML as the REST arm, except the CLI cannot take it inline:
        // it must be materialised to a file the subprocess can read. That is a
        // filesystem coupling the REST arm does not have.
        Path yaml = Files.createTempFile("spike-jvm-cli", ".yaml");
        Files.writeString(yaml, """
                name: spike-jvm-cli
                resources:
                  cpus: 1+
                  infra: k8s
                run: |
                  echo "hello from a CLI-submitted task"
                  for i in 1 2 3; do echo "tick $i"; sleep 2; done
                """);
        System.out.println("  wrote " + yaml);
        int rc = run(List.of(SKY, "launch", "-y", "-c", CLUSTER, "--fast", yaml.toString()), 60);
        System.out.println("  exit code = " + rc + "   <-- the only structured result of a mutation");

        step("4. logs — a subprocess whose stdout IS the stream");
        run(List.of(SKY, "logs", CLUSTER, "1", "--no-follow"), 30);

        step("5. teardown");
        run(List.of(SKY, "down", "-y", CLUSTER), 60);

        Files.deleteIfExists(yaml);
        step("DONE — see README.md for the verdict");
    }

    /** Run a command, echo at most n lines, return the exit code. */
    static int run(List<String> cmd, int maxLines) throws Exception {
        System.out.println("  $ " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        int n = 0;
        try (var in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (n < maxLines) System.out.println("    | " + strip(line));
                n++;
            }
        }
        boolean done = p.waitFor(15, TimeUnit.MINUTES);
        if (!done) { p.destroyForcibly(); System.out.println("    (timed out)"); return -1; }
        System.out.println("  (" + n + " lines, exit=" + p.exitValue() + ")");
        return p.exitValue();
    }

    /** The CLI writes ANSI to a pipe; a JVM consumer has to strip it. That is itself a finding. */
    static String strip(String s) {
        String out = s.replaceAll("\\[[;?0-9]*[a-zA-Z]", "");
        return out.length() > 160 ? out.substring(0, 160) + "…" : out;
    }

    static void step(String s) {
        System.out.println();
        System.out.println("=== " + s);
    }
}
