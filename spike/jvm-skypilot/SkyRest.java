// PROTOTYPE — THROWAWAY. Spike for issue #18 (map #1). Does not merge.
// Question: does the SkyPilot REST surface hold up when driven from a JVM?
// No dependencies on purpose: JDK 25 HttpClient only, single-file source launch.
// Every step prints the raw state it saw, so the contract is visible, not summarised.

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class SkyRest {

    static final String BASE = System.getenv().getOrDefault("SKY_API", "http://127.0.0.1:46580");
    // FINDING (the first thing this spike cost us): the JDK HttpClient defaults to HTTP_2,
    // which attempts an h2c upgrade. SkyPilot's uvicorn rejects it with a bare
    //   400 "Invalid HTTP request received."
    // — from uvicorn's parser, so no SkyPilot error, no request id, nothing to retry on.
    // Worse, GETs still succeed under the default. A Java client therefore looks healthy
    // right up until its first mutation. HTTP_1_1 must be pinned explicitly.
    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    static final String CLUSTER = "spike-jvm";

    public static void main(String[] args) throws Exception {
        step("0. health — is the server reachable, and does it want auth?");
        var health = get("/api/health");
        System.out.println("  status=" + health.statusCode());
        System.out.println("  body=" + trunc(health.body(), 400));

        step("1. POST /launch — the request id comes back in a HEADER, not the body");
        // LaunchBody requires exactly two fields: task (an opaque YAML STRING) and cluster_name.
        // Note we are hand-building SkyPilot task YAML as a string. There is no structured task type.
        String taskYaml = """
                name: spike-jvm
                resources:
                  cpus: 1+
                  infra: k8s
                run: |
                  echo "hello from a JVM-submitted task"
                  for i in 1 2 3 4 5; do echo "tick $i"; sleep 2; done
                  echo "done"
                """;
        String launchBody = json(Map.of(
                "task", taskYaml,
                "cluster_name", CLUSTER,
                "fast", true));
        var launch = post("/launch", launchBody);
        System.out.println("  http=" + launch.statusCode());
        System.out.println("  body=" + trunc(launch.body(), 200) + "   <-- note: carries no id");
        String reqId = launch.headers().firstValue("x-skypilot-request-id").orElse(null);
        System.out.println("  x-skypilot-request-id=" + reqId + "   <-- the ONLY handle on the operation");
        if (reqId == null) { System.out.println("  !! no request id; aborting"); return; }

        step("2. stream the launch's own log via GET /api/stream?request_id=...");
        System.out.println("  (this is provisioning output — a held connection, plain text)");
        streamAtMost("/api/stream?request_id=" + reqId + "&follow=true&format=plain", 40);

        step("3. poll GET /api/get?request_id=... until terminal");
        Map<String, String> payload = pollUntilDone(reqId);
        System.out.println("  final status = " + payload.get("status"));
        System.out.println("  return_value = " + trunc(payload.get("return_value"), 300));
        System.out.println("  error        = " + trunc(payload.get("error"), 300));
        System.out.println("  entrypoint   = " + trunc(payload.get("entrypoint"), 60));
        System.out.println("     ^ base64 of a PYTHON PICKLE. Opaque to a JVM. So is request_body.");

        step("4. POST /status — what does a read look like, and is its result typed?");
        var st = post("/status", json(Map.of("all_users", true, "refresh", "NONE")));
        String stId = st.headers().firstValue("x-skypilot-request-id").orElse(null);
        Map<String, String> stPayload = pollUntilDone(stId);
        System.out.println("  return_value = " + trunc(stPayload.get("return_value"), 1200));
        System.out.println("     ^ JSON here, but /launch's was a bare int. No schema declares either.");

        step("5. POST /logs — can a JVM follow a RUNNING job's output?");
        // ClusterJobBody wants a job_id. We never received one as a typed field: it has to be
        // dug out of an untyped return_value. That coupling is the finding, so show it.
        var logs = post("/logs", json(Map.of(
                "cluster_name", CLUSTER, "job_id", 1, "follow", true, "tail", 0)));
        String logId = logs.headers().firstValue("x-skypilot-request-id").orElse(null);
        System.out.println("  http=" + logs.statusCode() + " request_id=" + logId);
        streamAtMost("/api/stream?request_id=" + logId + "&follow=true&format=plain", 40);

        step("6. teardown — POST /down, then confirm it completes");
        var down = post("/down", json(Map.of("cluster_name", CLUSTER, "purge", false)));
        String downId = down.headers().firstValue("x-skypilot-request-id").orElse(null);
        System.out.println("  http=" + down.statusCode() + " request_id=" + downId);
        if (downId != null) {
            Map<String, String> d = pollUntilDone(downId);
            System.out.println("  final status = " + d.get("status") + " error=" + trunc(d.get("error"), 200));
        }

        step("DONE — see README.md for the verdict");
    }

    // ---- the whole client, such as it is -------------------------------------------------

    static HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofMinutes(5)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Poll /api/get. Prints every status transition so the state machine is visible.
     *
     * FINDING (this one cost us a 10-minute timeout on the first run): a FAILED request is
     * not reported as a status on a 200. /api/get answers HTTP 500, and the very same
     * RequestPayload is then nested one level deeper, under "detail". So the success and
     * failure shapes differ in BOTH transport code and JSON path, and neither is declared:
     * the OpenAPI lists only the 200 -> RequestPayload. A generated Java client would throw
     * on the 500 and discard the payload that explains why.
     */
    static Map<String, String> pollUntilDone(String reqId) throws Exception {
        String last = null;
        for (int i = 0; i < 900; i++) {
            var r = get("/api/get?request_id=" + reqId);
            Map<String, String> p = flatJson(r.body());
            if (r.statusCode() == 500) {
                System.out.println("  poll http=500 -> TERMINAL FAILURE, payload nested under \"detail\"");
                return p;
            }
            if (r.statusCode() != 200) {
                System.out.println("  poll http=" + r.statusCode() + " body=" + trunc(r.body(), 200));
                Thread.sleep(1000);
                continue;
            }
            String s = p.get("status");
            if (!Objects.equals(s, last)) { System.out.println("  status -> " + s); last = s; }
            if ("SUCCEEDED".equals(s) || "FAILED".equals(s) || "CANCELLED".equals(s)) return p;
            Thread.sleep(1000);
        }
        return Map.of("status", "TIMEOUT");
    }

    /** Read a held stream, printing at most n lines, so the spike stays bounded. */
    static void streamAtMost(String path, int n) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofMinutes(5)).GET().build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("  stream http=" + resp.statusCode()
                + " content-type=" + resp.headers().firstValue("content-type").orElse("?")
                + " transfer-encoding=" + resp.headers().firstValue("transfer-encoding").orElse("(none)"));
        int count = 0;
        try (var in = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (count < n && (line = in.readLine()) != null) {
                System.out.println("    | " + trunc(line, 160));
                count++;
            }
        } catch (IOException e) {
            System.out.println("    stream broke: " + e);
        }
        System.out.println("  (stopped after " + count + " lines)");
    }

    // ---- hand-rolled JSON, because the spike refuses to pretend a library makes this typed ----

    static String json(Map<String, Object> m) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(quote(e.getKey())).append(":");
            Object v = e.getValue();
            if (v instanceof String s) sb.append(quote(s));
            else sb.append(v);
        }
        return sb.append("}").toString();
    }

    static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append("\"").toString();
    }

    /** Good enough for a flat RequestPayload; deliberately crude. */
    static Map<String, String> flatJson(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            int k1 = s.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = k1 + 1;
            while (k2 < s.length() && (s.charAt(k2) != '"' || s.charAt(k2 - 1) == '\\')) k2++;
            String key = s.substring(k1 + 1, k2);
            int colon = s.indexOf(':', k2);
            if (colon < 0) break;
            int v = colon + 1;
            while (v < s.length() && Character.isWhitespace(s.charAt(v))) v++;
            String val;
            if (v < s.length() && s.charAt(v) == '"') {
                int v2 = v + 1;
                while (v2 < s.length() && (s.charAt(v2) != '"' || s.charAt(v2 - 1) == '\\')) v2++;
                val = s.substring(v + 1, v2).replace("\\n", "\n").replace("\\\"", "\"");
                i = v2 + 1;
            } else {
                int v2 = v;
                while (v2 < s.length() && ",}".indexOf(s.charAt(v2)) < 0) v2++;
                val = s.substring(v, v2).trim();
                i = v2;
            }
            out.put(key, val);
        }
        return out;
    }

    static String trunc(String s, int n) {
        if (s == null) return "null";
        s = s.replace("\n", "\\n");
        return s.length() <= n ? s : s.substring(0, n) + "…(+" + (s.length() - n) + " chars)";
    }

    static void step(String s) {
        System.out.println();
        System.out.println("=== " + s);
    }
}
