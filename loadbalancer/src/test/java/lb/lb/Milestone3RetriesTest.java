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
 * Milestone 3: smarter retry rules.
 *
 * Historical bump: "never retry non-idempotent" is too strict in practice.
 * Many L7 load balancers will retry even POST/PUT *if the failure is clearly a connect failure*
 * (meaning the request never reached the application).
 */
public class Milestone3RetriesTest {

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

        // Disable active health checks so the first attempt will hit the dead backend
        // and we can verify passive retry behavior deterministically.
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
