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

/**
 * Milestone 2 tests: health checking / avoiding unhealthy backends.
 *
 * These tests are expected to FAIL until Milestone 2 is implemented.
 */
public class Milestone2HealthChecksTest {

    private HttpServer backendA;
    private HttpServer backendB;
    private HttpServer lb;

    @AfterEach
    void tearDown() {
        if (lb != null) lb.stop(0);
        if (backendA != null) backendA.stop(0);
        if (backendB != null) backendB.stop(0);
    }

    @Test
    void whenAConfiguredBackendIsDown_lbShouldStillServeTrafficWithout502s() throws Exception {
        backendA = BackendServer.start(0, "backend-a");

        URI a = URI.create("http://localhost:" + backendA.getAddress().getPort() + "/");
        URI dead = URI.create("http://localhost:" + findFreePort() + "/");

        lb = LoadBalancer.start(0, List.of(a, dead));
        URI lbBase = URI.create("http://localhost:" + lb.getAddress().getPort() + "/");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        // Expectation for Milestone 2: LB should detect the dead backend and avoid routing to it.
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> resp = get(client, lbBase.resolve("/"));
            assertEquals(200, resp.statusCode(), "Expected LB to avoid dead backend, got: " + resp.body());
        }
    }

    @Test
    void whenBackendDiesAfterStartup_lbShouldStopRoutingToIt() throws Exception {
        backendA = BackendServer.start(0, "backend-a");
        backendB = BackendServer.start(0, "backend-b");

        URI a = URI.create("http://localhost:" + backendA.getAddress().getPort() + "/");
        URI b = URI.create("http://localhost:" + backendB.getAddress().getPort() + "/");

        lb = LoadBalancer.start(0, List.of(a, b));
        URI lbBase = URI.create("http://localhost:" + lb.getAddress().getPort() + "/");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        // Sanity: LB should work initially.
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> resp = get(client, lbBase.resolve("/"));
            assertEquals(200, resp.statusCode());
        }

        // Now simulate a backend dying.
        backendB.stop(0);
        backendB = null;

        // Expectation for Milestone 2: after some detection window, LB stops sending traffic to B.
        // We'll allow a short grace period for health checks to run.
        Thread.sleep(300);

        for (int i = 0; i < 10; i++) {
            HttpResponse<String> resp = get(client, lbBase.resolve("/"));
            assertEquals(200, resp.statusCode(), "Expected LB to avoid dead backend, got: " + resp.body());
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
