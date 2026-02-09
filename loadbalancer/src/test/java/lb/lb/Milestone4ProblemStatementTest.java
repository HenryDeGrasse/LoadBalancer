package lb.lb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 4 (RED test): response streaming / time-to-first-byte.
 *
 * Problem statement:
 * a naive L7 proxy that buffers the full upstream response before sending anything
 * causes head-of-line blocking and poor latency for streaming endpoints.
 */
public class Milestone4ProblemStatementTest {

    private HttpServer upstream;
    private HttpServer lb;

    @AfterEach
    void tearDown() {
        if (lb != null) lb.stop(0);
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void loadBalancerShouldStreamResponseInsteadOfBufferingWholeBody() throws Exception {
        upstream = startDripServer(150, 6); // ~900ms total body duration

        URI upstreamBase = URI.create("http://localhost:" + upstream.getAddress().getPort() + "/");
        lb = LoadBalancer.start(0, List.of(upstreamBase), new LoadBalancer.Options(false));

        URI lbUri = URI.create("http://localhost:" + lb.getAddress().getPort() + "/drip");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        HttpRequest req = HttpRequest.newBuilder(lbUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        long t0 = System.nanoTime();
        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int firstByte = resp.body().read();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        assertTrue(firstByte >= 0, "expected a byte from upstream response");

        // Milestone 4 expected behavior: first byte should arrive quickly (well before full 900ms body completes).
        assertTrue(elapsedMs < 500,
                "expected streaming first byte < 500ms, but was " + elapsedMs + "ms (likely full-response buffering)");
    }

    private static HttpServer startDripServer(int delayMs, int chunks) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        s.setExecutor(Executors.newFixedThreadPool(4));

        s.createContext("/health", ex -> {
            byte[] b = "OK\n".getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            } finally {
                ex.close();
            }
        });

        s.createContext("/drip", ex -> handleDrip(ex, delayMs, chunks));
        s.start();
        return s;
    }

    private static void handleDrip(HttpExchange ex, int delayMs, int chunks) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, chunks); // fixed-length body so headers are emitted immediately

        try (OutputStream os = ex.getResponseBody()) {
            for (int i = 0; i < chunks; i++) {
                os.write('x');
                os.flush();
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            ex.close();
        }
    }
}
