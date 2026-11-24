import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small client for the rate-limiter demo, separate from the course load
 * client (which must not be changed). One file, run directly with java:
 *
 *   java scripts/demo/RateLimitClient.java --mode=polite --stations=300 --duration=60
 *   java scripts/demo/RateLimitClient.java --mode=probe  --path=/items --concurrency=16 --duration=20 --instances=8081,8085
 *
 * polite: checkout stations like the course load client, with one difference:
 *         a station that gets a 429 WAITS before trying again: at least as
 *         long as the server says, and longer each time it is refused again in
 *         a row (exponential backoff with random jitter, capped at 2 s, reset
 *         after an admitted request). The backoff matters: with one global
 *         bucket the server's hint is "the next token exists in ~2 ms", which
 *         is true but is only enough for ONE of the waiting stations. Sleeping
 *         exactly that long makes every station come back together. The course client retries immediately, which turns
 *         every 429 into another request. This client also times the 429
 *         answers, which the course client does not record.
 *         --wait=ms (default) takes the hint from X-RateLimit-Retry-After-Ms;
 *         --wait=seconds uses only the standard Retry-After (whole seconds);
 *         --wait=exact sleeps exactly the ms hint, no backoff (shows the herd).
 *         --report=file.json writes the summary as JSON.
 * probe:  sends GET requests to one path as fast as `concurrency` threads can,
 *         counts 200s and 429s per second, and at the end reads each service
 *         instance's own counters (/internal/ratelimit/stats on its port), so
 *         you can see two instances sharing ONE limit.
 *
 * Both print one line per second and a summary at the end.
 */
public class RateLimitClient {

    static final Pattern TX_ID = Pattern.compile("\"transactionId\"\\s*:\\s*\"([^\"]+)\"");
    static final Pattern SKU = Pattern.compile("\"sku\"\\s*:\\s*\"([^\"]+)\"");

    final Map<String, String> opt;
    final String base;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final AtomicBoolean stop = new AtomicBoolean();

    // counters (LongAdder: many threads add, the ticker reads)
    final LongAdder started = new LongAdder();
    final LongAdder throttled = new LongAdder();
    final LongAdder completed = new LongAdder();
    final LongAdder scans = new LongAdder();
    final LongAdder errors = new LongAdder();
    // every error by "kind | operation" -> {count, first second, last second}
    final Map<String, long[]> errorKinds = new ConcurrentHashMap<>();
    volatile long runStartNanos = System.nanoTime();
    final LongAdder waiting = new LongAdder();
    volatile boolean exactOnly;
    final LongAdder ok = new LongAdder();
    final LongAdder other = new LongAdder();
    final ConcurrentLinkedQueue<Long> admittedNanos = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Long> throttledNanos = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Long> completeNanos = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Long> scanNanos = new ConcurrentLinkedQueue<>();

    RateLimitClient(Map<String, String> opt) {
        this.opt = opt;
        this.base = opt.getOrDefault("baseUrl", "http://localhost:8080");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                opt.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        RateLimitClient c = new RateLimitClient(opt);
        if ("probe".equals(opt.getOrDefault("mode", "polite"))) {
            c.probe();
        } else {
            c.polite();
        }
    }

    // ---- polite stations ---------------------------------------------------

    void polite() throws Exception {
        int stations = Integer.parseInt(opt.getOrDefault("stations", "100"));
        int duration = Integer.parseInt(opt.getOrDefault("duration", "60"));
        List<String> skus = fetchSkus();
        String waitOpt = opt.getOrDefault("wait", "ms");
        boolean exactWait = !"seconds".equals(waitOpt);
        exactOnly = "exact".equals(waitOpt);
        System.out.printf("polite client: %d stations for %ds against %s (a 429 means: wait %s, then retry)%n",
                stations, duration, base, exactOnly ? "exactly X-RateLimit-Retry-After-Ms" : exactWait ? "X-RateLimit-Retry-After-Ms + backoff" : "Retry-After (whole seconds)");
        System.out.println("   t | admitted/s throttled/s waiting | completed/s scans/s errors | 201 p50  429 p50");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            runStartNanos = System.nanoTime();
            for (int i = 1; i <= stations; i++) {
                String id = String.format("polite-%03d", i);
                pool.submit(() -> station(id, skus, exactWait));
            }
            ticker(duration, true);
            stop.set(true);
        }
        long[] a = sorted(admittedNanos);
        long[] t = sorted(throttledNanos);
        long[] c = sorted(completeNanos);
        long[] sc = sorted(scanNanos);
        System.out.println();
        System.out.printf("summary: %d s, %d stations%n", duration, stations);
        System.out.printf("  POST /transactions admitted  %8d  (%.1f/s)   latency p50 %s  p95 %s  p99 %s%n",
                started.sum(), started.sum() / (double) duration, ms(pct(a, .50)), ms(pct(a, .95)), ms(pct(a, .99)));
        System.out.printf("  POST /transactions throttled %8d  (%.1f/s)   latency p50 %s  p95 %s  p99 %s  <- the 429s%n",
                throttled.sum(), throttled.sum() / (double) duration, ms(pct(t, .50)), ms(pct(t, .95)), ms(pct(t, .99)));
        System.out.printf("  scans                        %8d  (%.1f/s)   latency p50 %s  p95 %s  p99 %s%n",
                scans.sum(), scans.sum() / (double) duration, ms(pct(sc, .50)), ms(pct(sc, .95)), ms(pct(sc, .99)));
        System.out.printf("  completed transactions       %8d  (%.1f/s)   complete p50 %s  p95 %s  p99 %s%n",
                completed.sum(), completed.sum() / (double) duration, ms(pct(c, .50)), ms(pct(c, .95)), ms(pct(c, .99)));
        System.out.printf("  errors (anything but 201/200/429, or no answer): %d%n", errors.sum());
        if (!errorKinds.isEmpty()) {
            System.out.println("  errors by kind and operation (seconds into the run when they happened):");
            errorKinds.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                    .forEach(e -> {
                        String[] k = e.getKey().split("\\|");
                        long[] v = e.getValue();
                        System.out.printf("    %8d  %-44s %-9s s %d-%d%n", v[0], k[0], k[1], v[1], v[2]);
                    });
        }
        if (opt.containsKey("report")) {
            String json = String.format(java.util.Locale.ROOT, """
                    {"client":"polite","wait":"%s","stations":%d,"durationSeconds":%d,
                     "admitted":%d,"throttled":%d,"completed":%d,"scans":%d,"errors":%d,
                     "transactionsPerSecond":%.1f,
                     "startAdmittedMs":{"p50":%.3f,"p95":%.3f,"p99":%.3f},
                     "start429Ms":{"p50":%.3f,"p95":%.3f,"p99":%.3f},
                     "scanMs":{"p50":%.3f,"p95":%.3f,"p99":%.3f},
                     "completeMs":{"p50":%.3f,"p95":%.3f,"p99":%.3f}}
                    """,
                    waitOpt, stations, duration, started.sum(), throttled.sum(), completed.sum(),
                    scans.sum(), errors.sum(), completed.sum() / (double) duration,
                    msd(pct(a, .5)), msd(pct(a, .95)), msd(pct(a, .99)), msd(pct(t, .5)), msd(pct(t, .95)), msd(pct(t, .99)),
                    msd(pct(sc, .5)), msd(pct(sc, .95)), msd(pct(sc, .99)), msd(pct(c, .5)), msd(pct(c, .95)), msd(pct(c, .99)));
            java.nio.file.Files.writeString(java.nio.file.Path.of(opt.get("report")), json);
            System.out.println("  report: " + opt.get("report"));
        }
    }

    static final long BACKOFF_BASE_MS = 50, BACKOFF_CAP_MS = 2000;

    void station(String id, List<String> skus, boolean exactWait) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int refusedInARow = 0;
        while (!stop.get()) {
            String op = "start";
            try {
                long t0 = System.nanoTime();
                HttpResponse<String> r = send("POST", "/transactions", "{\"stationId\":\"" + id + "\"}");
                long took = System.nanoTime() - t0;
                if (r.statusCode() == 429) {
                    throttled.increment();
                    throttledNanos.add(took);
                    // the server's hint is the earliest moment trying again can work:
                    // ms from the non-standard header, else whole seconds from Retry-After
                    String exact = r.headers().firstValue("X-RateLimit-Retry-After-Ms").orElse(null);
                    long hintMs = exactWait && exact != null
                            ? Long.parseLong(exact)
                            : 1000L * Long.parseLong(r.headers().firstValue("Retry-After").orElse("1"));
                    long waitMs;
                    if (exactOnly) {
                        waitMs = hintMs;                       // --wait=exact: no backoff
                    } else if (exactWait) {
                        // never sooner than the hint; on top of it a random share of a
                        // window that doubles with every 429 in a row (full jitter)
                        long window = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(refusedInARow, 6));
                        waitMs = hintMs + rnd.nextLong(window + 1);
                    } else {
                        waitMs = hintMs + rnd.nextLong(Math.min(hintMs, 250) + 1);
                    }
                    refusedInARow++;
                    waiting.increment();
                    try {
                        Thread.sleep(waitMs);
                    } finally {
                        waiting.decrement();
                    }
                    continue;
                }
                if (r.statusCode() != 201) {
                    error("HTTP " + r.statusCode(), op);
                    continue;
                }
                refusedInARow = 0;
                started.increment();
                admittedNanos.add(took);
                Matcher m = TX_ID.matcher(r.body());
                if (!m.find()) {
                    error("201 without a transaction id", op);
                    continue;
                }
                String tx = m.group(1);
                int n = rnd.nextInt(1, 21);
                boolean okSoFar = true;
                op = "scan";
                for (int i = 0; i < n && okSoFar; i++) {
                    String sku = skus.get(rnd.nextInt(skus.size()));
                    long ts = System.nanoTime();
                    HttpResponse<String> s = send("POST", "/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}");
                    if (s.statusCode() == 200) {
                        scans.increment();
                        scanNanos.add(System.nanoTime() - ts);
                    } else {
                        error("HTTP " + s.statusCode(), op);
                        okSoFar = false;
                    }
                }
                if (!okSoFar) {
                    continue;
                }
                op = "complete";
                long tc = System.nanoTime();
                HttpResponse<String> done = send("POST", "/transactions/" + tx + "/complete", "{}");
                if (done.statusCode() == 200) {
                    completed.increment();
                    completeNanos.add(System.nanoTime() - tc);
                } else {
                    error("HTTP " + done.statusCode(), op);
                }
            } catch (Exception e) {
                error("no answer (" + e.getClass().getSimpleName() + ")", op);
            }
        }
    }

    /** Counts one error, and remembers its kind, operation and when it happened. */
    void error(String kind, String op) {
        errors.increment();
        long second = (System.nanoTime() - runStartNanos) / 1_000_000_000L + 1;
        errorKinds.compute(kind + "|" + op, (k, v) -> {
            if (v == null) {
                return new long[] {1, second, second};
            }
            v[0]++;
            v[1] = Math.min(v[1], second);
            v[2] = Math.max(v[2], second);
            return v;
        });
    }

    List<String> fetchSkus() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            HttpResponse<String> r = send("GET", "/items", null);
            if (r.statusCode() == 200) {
                List<String> skus = new ArrayList<>();
                Matcher m = SKU.matcher(r.body());
                while (m.find()) {
                    skus.add(m.group(1));
                }
                return skus;
            }
            Thread.sleep(1000L * Long.parseLong(r.headers().firstValue("Retry-After").orElse("1")));
        }
        throw new IllegalStateException("could not load the catalog");
    }

    // ---- probe -------------------------------------------------------------

    void probe() throws Exception {
        String path = opt.getOrDefault("path", "/items");
        int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "16"));
        int duration = Integer.parseInt(opt.getOrDefault("duration", "20"));
        String[] ports = opt.getOrDefault("instances", "").isBlank() ? new String[0] : opt.get("instances").split(",");
        Map<String, long[]> before = instanceCounts(ports);
        System.out.printf("probe: GET %s with %d threads for %ds%n", path, concurrency, duration);
        System.out.println("   t |   200/s   429/s  other/s");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        try {
                            int code = send("GET", path, null).statusCode();
                            (code == 200 ? ok : code == 429 ? throttled : other).increment();
                        } catch (Exception e) {
                            other.increment();
                        }
                    }
                });
            }
            ticker(duration, false);
            stop.set(true);
        }
        System.out.println();
        System.out.printf("summary: %d x 200 (%.1f/s), %d x 429, %d other, over %d s%n",
                ok.sum(), ok.sum() / (double) duration, throttled.sum(), other.sum(), duration);
        if (ports.length > 0) {
            Map<String, long[]> after = instanceCounts(ports);
            long total = 0;
            for (String p : ports) {
                long allowed = after.get(p)[0] - before.get(p)[0];
                long rejected = after.get(p)[1] - before.get(p)[1];
                total += allowed;
                System.out.printf("  instance :%s  allowed %6d  rejected %7d   (its own counters)%n", p, allowed, rejected);
            }
            System.out.printf("  all instances together allowed %d = %.1f per second%n", total, total / (double) duration);
        }
    }

    /** {allowed, rejected} of the first rule, read from each instance's own stats endpoint. */
    Map<String, long[]> instanceCounts(String[] ports) {
        Map<String, long[]> out = new HashMap<>();
        for (String p : ports) {
            try {
                String body = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + p.trim() + "/internal/ratelimit/stats"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                out.put(p, new long[]{number(body, "allowed"), number(body, "rejected")});
            } catch (Exception e) {
                out.put(p, new long[2]);
            }
        }
        return out;
    }

    static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    // ---- shared ------------------------------------------------------------

    /** One line per second until the duration is up. */
    void ticker(int duration, boolean stations) throws InterruptedException {
        long[] prev = new long[6];
        int a0 = 0;
        int t0 = 0;
        for (int s = 1; s <= duration; s++) {
            Thread.sleep(1000);
            long[] cur = {started.sum(), throttled.sum(), completed.sum(), scans.sum(), errors.sum() + other.sum(), ok.sum()};
            if (stations) {
                long[] a = sortedTail(admittedNanos, a0);
                long[] t = sortedTail(throttledNanos, t0);
                a0 = admittedNanos.size();
                t0 = throttledNanos.size();
                System.out.printf("%4d | %10d %11d %7d | %11d %7d %6d | %7s %7s%n", s, cur[0] - prev[0], cur[1] - prev[1],
                        waiting.sum(), cur[2] - prev[2], cur[3] - prev[3], cur[4] - prev[4], ms(pct(a, .5)), ms(pct(t, .5)));
            } else {
                System.out.printf("%4d | %7d %7d %8d%n", s, cur[5] - prev[5], cur[1] - prev[1], cur[4] - prev[4]);
            }
            prev = cur;
        }
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static long[] sorted(ConcurrentLinkedQueue<Long> q) {
        long[] a = q.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a;
    }

    /** The samples added since index `from` (the last second), sorted. */
    static long[] sortedTail(ConcurrentLinkedQueue<Long> q, int from) {
        long[] a = q.stream().skip(from).mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a;
    }

    static long pct(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return -1;
        }
        int i = (int) Math.ceil(p * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(sortedNanos.length - 1, i))];
    }

    static double msd(long nanos) {
        return nanos < 0 ? -1 : nanos / 1_000_000.0;
    }

    static String ms(long nanos) {
        return nanos < 0 ? "-" : String.format("%.2fms", nanos / 1_000_000.0);
    }
}
