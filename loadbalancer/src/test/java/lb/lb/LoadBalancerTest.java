package lb.lb;

import com.sun.net.httpserver.HttpServer;
import lb.backend.BackendServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoadBalancerTest {

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
    void roundRobinDistributesAcrossBackends() throws Exception {
        backendA = BackendServer.start(0, "backend-a");
        backendB = BackendServer.start(0, "backend-b");

        URI a = URI.create("http://localhost:" + backendA.getAddress().getPort() + "/");
        URI b = URI.create("http://localhost:" + backendB.getAddress().getPort() + "/");

        lb = LoadBalancer.start(0, List.of(a, b));
        URI lbBase = URI.create("http://localhost:" + lb.getAddress().getPort() + "/");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        String r1 = get(client, lbBase.resolve("/"));
        String r2 = get(client, lbBase.resolve("/"));
        String r3 = get(client, lbBase.resolve("/"));
        String r4 = get(client, lbBase.resolve("/"));

        // We expect strict alternation in this single-threaded test.
        assertTrue(r1.contains("backend=backend-a"), r1);
        assertTrue(r2.contains("backend=backend-b"), r2);
        assertTrue(r3.contains("backend=backend-a"), r3);
        assertTrue(r4.contains("backend=backend-b"), r4);
    }

    private static String get(HttpClient client, URI uri) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }
}
