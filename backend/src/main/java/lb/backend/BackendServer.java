package lb.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class BackendServer {

    /**
     * Start a backend HTTP server.
     *
     * @param port 0 to bind to an ephemeral port.
     */
    public static HttpServer start(int port, String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        // Keep it simple but not single-threaded.
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));

        server.createContext("/health", exchange -> respondText(exchange, 200, "OK\n"));
        server.createContext("/", new RootHandler(name));

        server.start();
        int boundPort = server.getAddress().getPort();
        System.out.println("[" + name + "] listening on http://localhost:" + boundPort);
        return server;
    }

    public static void main(String[] args) throws Exception {
        int port = intArg(args, 0, 9001);
        String name = strArg(args, 1, "backend-" + port);
        start(port, name);
    }

    private static final class RootHandler implements HttpHandler {
        private final String name;

        private RootHandler(String name) {
            this.name = name;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();

            if ("/slow".equals(uri.getPath())) {
                long ms = queryLong(uri, "ms", 250);
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] requestBody = readAllBytes(exchange.getRequestBody());

            StringBuilder sb = new StringBuilder();
            sb.append("backend=").append(name).append('\n');
            sb.append("time=").append(Instant.now()).append('\n');
            sb.append("method=").append(exchange.getRequestMethod()).append('\n');
            sb.append("path=").append(uri.getPath()).append('\n');
            sb.append("query=").append(uri.getRawQuery()).append('\n');
            sb.append("remote=").append(exchange.getRemoteAddress()).append('\n');
            sb.append("headers:\n");
            for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
            }
            sb.append("bodyBytes=").append(requestBody.length).append('\n');

            respondText(exchange, 200, sb.toString());
        }
    }

    private static void respondText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", "text/plain; charset=utf-8");

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

    private static int intArg(String[] args, int index, int defaultValue) {
        if (args.length <= index) return defaultValue;
        return Integer.parseInt(args[index]);
    }

    private static String strArg(String[] args, int index, String defaultValue) {
        if (args.length <= index) return defaultValue;
        return args[index];
    }

    private static long queryLong(URI uri, String key, long defaultValue) {
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return defaultValue;

        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq);
            if (!key.equals(k)) continue;
            String v = part.substring(eq + 1);
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
