package lb.lb;

import com.sun.net.httpserver.HttpServer;
import lb.backend.BackendServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 3 (RED tests): Retry rules + determinism.
 *
 * Problem statement:
 * 1) "Never retry non-idempotent requests" is too strict.
 *    If the failure is a clear CONNECT failure (connection refused/timeout),
 *    we can safely retry even POST because the request never reached the app.
 *
 * 2) We need the ability to disable/tune active health checks for deterministic testing
 *    (otherwise the health checker might mark a dead backend unhealthy before the first request).
 *
 * These tests are expected to FAIL (or even fail to compile) until Milestone 3 is implemented.
 */
public class Milestone3ProblemStatementTest {

    private HttpServer backendA;
    private HttpServer lb;

    @AfterEach
    void tearDown() {
        if (lb != null) lb.stop(0);
        if (backendA != null) backendA.stop(0);
    }

    @Test
    void postConnectFailureShouldFailOverToHealthyBackend() throws Exception {
        backendA = BackendServer.start(0, "backend-a");

        URI healthy = URI.create("http://localhost:" + backendA.getAddress().getPort() + "/");
        URI dead = URI.create("http://localhost:" + findFreePort() + "/");

        // Milestone 3: introduce configurable options; we want active health checks OFF
        // to ensure the first attempt can hit the dead backend.
        lb = LoadBalancer.start(0, List.of(dead, healthy), new LoadBalancer.Options(false));

        URI lbBase = URI.create("http://localhost:" + lb.getAddress().getPort() + "/");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        HttpRequest req = HttpRequest.newBuilder(lbBase.resolve("/"))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        // Expected Milestone 3 behavior: even for POST, connect failures should be retried.
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("backend=backend-a"), resp.body());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
