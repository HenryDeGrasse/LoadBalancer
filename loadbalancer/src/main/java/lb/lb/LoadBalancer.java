package lb.lb;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milestone 1: naive L7 reverse proxy doing round-robin.
 * Milestone 2: add active + passive health checking to avoid routing to dead upstreams.
 * Milestone 3: smarter retry rules (e.g. retry non-idempotent requests on *connect* failures).
 */
public final class LoadBalancer {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    /**
     * Runtime knobs so tests (and later milestones) can control behavior.
     */
    public static final class Options {
        public final boolean enableActiveHealthChecks;
        public final Duration healthTimeout;
        public final Duration healthPeriod;
        public final Duration passiveUnhealthyCooldown;
        public final Duration upstreamTimeout;

        public Options() {
            this(true);
        }

        public Options(boolean enableActiveHealthChecks) {
            this(
                    enableActiveHealthChecks,
                    Duration.ofMillis(200),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(10)
            );
        }

        public Options(
                boolean enableActiveHealthChecks,
                Duration healthTimeout,
                Duration healthPeriod,
                Duration passiveUnhealthyCooldown,
                Duration upstreamTimeout
        ) {
            this.enableActiveHealthChecks = enableActiveHealthChecks;
            this.healthTimeout = Objects.requireNonNull(healthTimeout, "healthTimeout");
            this.healthPeriod = Objects.requireNonNull(healthPeriod, "healthPeriod");
            this.passiveUnhealthyCooldown = Objects.requireNonNull(passiveUnhealthyCooldown, "passiveUnhealthyCooldown");
            this.upstreamTimeout = Objects.requireNonNull(upstreamTimeout, "upstreamTimeout");
        }
    }

    /**
     * Start the load balancer.
     *
     * @param listenPort 0 to bind to an ephemeral port.
     */
    public static HttpServer start(int listenPort, List<URI> backends) throws IOException {
        return start(listenPort, backends, new Options());
    }

    /**
     * Start the load balancer.
     *
     * @param listenPort 0 to bind to an ephemeral port.
     */
    public static HttpServer start(int listenPort, List<URI> backends, Options options) throws IOException {
        Objects.requireNonNull(backends, "backends");
        Objects.requireNonNull(options, "options");
        if (backends.isEmpty()) throw new IllegalArgumentException("backends must not be empty");

        // Proxy client
        HttpClient proxyClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // Health check client (keep it separate so we can tune timeouts independently)
        HttpClient healthClient = HttpClient.newBuilder()
                .connectTimeout(options.healthTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        BackendPool pool = new BackendPool(backends, options);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", listenPort), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(8, Runtime.getRuntime().availableProcessors()),
                new NamedDaemonThreadFactory("lb-worker-")
        ));

        // Active health checks run in the background.
        if (options.enableActiveHealthChecks) {
            ScheduledExecutorService healthExec = Executors.newSingleThreadScheduledExecutor(new NamedDaemonThreadFactory("lb-health-"));
            healthExec.scheduleAtFixedRate(() -> {
                try {
                    pool.runActiveHealthChecks(healthClient);
                } catch (Exception ignored) {
                    // keep health loop alive
                }
            }, 0, options.healthPeriod.toMillis(), TimeUnit.MILLISECONDS);
        }

        server.createContext("/", exchange -> {
            byte[] requestBody = readAllBytes(exchange.getRequestBody());
            String method = exchange.getRequestMethod();

            int maxAttempts = pool.size();

            Exception lastError = null;
            URI lastBackend = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                URI backend = pool.chooseBackend();
                if (backend == null) {
                    respondText(exchange, 503, "no_healthy_upstreams\n");
                    return;
                }

                lastBackend = backend;
                try {
                    proxy(exchange, backend, proxyClient, requestBody, options.upstreamTimeout);
                    return;
                } catch (Exception e) {
                    lastError = e;
                    pool.markPassiveFailure(backend);

                    boolean retryable = shouldRetry(method, e);
                    if (!retryable || attempt == maxAttempts) {
                        String msg = "upstream_error=" + e.getClass().getSimpleName() + " message=" + e.getMessage() + "\n";
                        respondText(exchange, 502, msg);
                        return;
                    }
                }
            }

            // Shouldn't reach here.
            String msg = "upstream_error=" + (lastError == null ? "Unknown" : lastError.getClass().getSimpleName())
                    + " backend=" + lastBackend + "\n";
            respondText(exchange, 502, msg);
        });

        server.start();
        int boundPort = server.getAddress().getPort();
        System.out.println("[lb] listening on http://localhost:" + boundPort);
        System.out.println("[lb] backends=" + backends);

        // Note: HttpServer has no lifecycle hook to stop background threads. Our health threads are daemon threads,
        // so they won't block tests/JVM exit. In a real LB we'd expose a proper stop() to shutdown executors.
        return server;
    }

    public static void main(String[] args) throws Exception {
        int listenPort = intArg(args, 0, 8080);
        List<URI> backends = parseBackends(args.length >= 2 ? args[1] : "http://localhost:9001,http://localhost:9002");
        start(listenPort, backends);
    }

    private static void proxy(HttpExchange exchange, URI backend, HttpClient client, byte[] requestBody, Duration upstreamTimeout)
            throws IOException, InterruptedException {
        URI target = backend.resolve(exchange.getRequestURI().toString());

        HttpRequest.Builder req = HttpRequest.newBuilder(target)
                .timeout(upstreamTimeout)
                .method(exchange.getRequestMethod(), requestBody.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(requestBody));

        copyRequestHeaders(exchange.getRequestHeaders(), req);

        HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());

        Headers out = exchange.getResponseHeaders();
        copyResponseHeaders(resp, out);

        byte[] responseBody = resp.body() == null ? new byte[0] : resp.body();
        exchange.sendResponseHeaders(resp.statusCode(), responseBody.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBody);
        } finally {
            exchange.close();
        }

        System.out.printf("[lb] %s %s -> %s %d (%dB)%n",
                exchange.getRequestMethod(), exchange.getRequestURI(), backend, resp.statusCode(), responseBody.length);
    }

    private static void copyRequestHeaders(Headers in, HttpRequest.Builder out) {
        // Important: don’t forward hop-by-hop headers.
        in.forEach((k, values) -> {
            String key = k.toLowerCase();
            if (HOP_BY_HOP.contains(key)) return;
            if ("host".equals(key)) return;
            if ("content-length".equals(key)) return; // HttpClient sets this; manually setting is rejected.

            // java.net.http.HttpClient restricts some headers (Host, Connection, Content-Length, etc.).
            for (String v : values) {
                out.header(k, v);
            }
        });
    }

    private static void copyResponseHeaders(HttpResponse<?> in, Headers out) {
        in.headers().map().forEach((k, values) -> {
            String key = k.toLowerCase();
            if (HOP_BY_HOP.contains(key)) return;
            for (String v : values) {
                out.add(k, v);
            }
        });
    }

    private static void respondText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static boolean shouldRetry(String method, Exception e) {
        // Baseline: retry idempotent methods.
        if (isIdempotent(method)) return true;

        // Milestone 3: retry *non-idempotent* methods only for failures that strongly suggest
        // the request did not reach the application (connect failures).
        return isConnectFailure(e);
    }

    private static boolean isIdempotent(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static boolean isConnectFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ConnectException) return true;
            if (cur instanceof HttpConnectTimeoutException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private static List<URI> parseBackends(String csv) {
        String[] parts = csv.split(",");
        List<URI> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(URI.create(s.endsWith("/") ? s : (s + "/")));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No backends configured");
        return List.copyOf(out);
    }

    private static int intArg(String[] args, int index, int defaultValue) {
        if (args.length <= index) return defaultValue;
        return Integer.parseInt(args[index]);
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger n = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Tracks backend health and selects a backend for a request.
     *
     * Milestone 2: mix of
     * - active health checks: periodic /health probes
     * - passive health: mark a backend unhealthy on request failures
     */
    private static final class BackendPool {
        private final List<Backend> backends;
        private final AtomicInteger rr = new AtomicInteger();
        private final Options options;

        private BackendPool(List<URI> backends, Options options) {
            this.backends = backends.stream().map(Backend::new).toList();
            this.options = options;
        }

        int size() {
            return backends.size();
        }

        URI chooseBackend() {
            int n = backends.size();
            int start = Math.floorMod(rr.getAndIncrement(), n);

            long now = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Backend b = backends.get((start + i) % n);
                if (b.isEligible(now)) {
                    return b.base;
                }
            }
            return null;
        }

        void markPassiveFailure(URI backend) {
            Backend b = find(backend);
            if (b == null) return;
            b.markUnhealthyFor(options.passiveUnhealthyCooldown);
        }

        void runActiveHealthChecks(HttpClient healthClient) {
            for (Backend b : backends) {
                boolean ok = checkOnce(healthClient, b.base, options.healthTimeout);
                if (ok) {
                    b.markHealthy();
                } else {
                    b.markUnhealthyFor(options.passiveUnhealthyCooldown);
                }
            }
        }

        private Backend find(URI backend) {
            for (Backend b : backends) {
                if (b.base.equals(backend)) return b;
            }
            return null;
        }

        private static boolean checkOnce(HttpClient client, URI backendBase, Duration timeout) {
            try {
                URI healthUri = backendBase.resolve("health");
                HttpRequest req = HttpRequest.newBuilder(healthUri)
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                return resp.statusCode() >= 200 && resp.statusCode() < 300;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static final class Backend {
        private final URI base;

        // If now < unhealthyUntilNanos -> consider unhealthy.
        private volatile long unhealthyUntilNanos = 0;

        private Backend(URI base) {
            this.base = base;
        }

        boolean isEligible(long nowNanos) {
            return nowNanos >= unhealthyUntilNanos;
        }

        void markHealthy() {
            unhealthyUntilNanos = 0;
        }

        void markUnhealthyFor(Duration d) {
            long until = System.nanoTime() + d.toNanos();
            // only extend, never shorten
            if (until > unhealthyUntilNanos) {
                unhealthyUntilNanos = until;
            }
        }
    }
}
