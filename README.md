# LoadBalancer (Learning Repo)

This repo is a step-by-step build of a **Layer-7 (HTTP) load balancer** in **Java 21** using **Gradle**.

The goal is to iterate through “milestones” where we intentionally hit the kinds of problems teams historically hit when evolving from a naïve reverse proxy into a real production load balancer.

## Milestones

### Milestone 1 — Naïve L7 reverse proxy (round-robin)
What we have now:
- `backend`: a tiny HTTP server with `/health` and `/slow?ms=...`
- `loadbalancer`: an HTTP reverse proxy on `:8080` that forwards requests to backends with round-robin selection

This milestone is intentionally “too simple” so we can observe early issues:
- buffering entire request/response bodies in memory
- limited header correctness (hop-by-hop headers are filtered, but many subtleties remain)
- **restricted header handling** (some HTTP client libraries refuse you setting headers like `Host`)
- no health checks, retries, outlier detection
- simplistic timeouts

### Future milestones (we’ll build next)
2. **Health checks + passive failure detection** (stop routing to dead upstreams) ✅
3. **Retries + idempotency rules** (when you can/can’t replay) ✅
4. **Streaming** (avoid buffering, handle large uploads/downloads)
5. **Keep-alive + connection pooling + backpressure** (performance under concurrency)
6. **Least-connections / latency-aware** balancing
7. **Sticky sessions** (cookies)
8. **Observability** (structured logs, metrics)
9. **Rate limiting / overload protection**

## Run it

## Test it (JUnit 5)
```bash
./gradlew test
```


Open 3 terminals.

### Terminal 1: backend A
```bash
./gradlew :backend:run --args="9001 backend-a"
```

### Terminal 2: backend B
```bash
./gradlew :backend:run --args="9002 backend-b"
```

### Terminal 3: load balancer
```bash
./gradlew :loadbalancer:run
# or configure backends explicitly
./gradlew :loadbalancer:run --args="8080 http://localhost:9001,http://localhost:9002"
```

### Try it
```bash
curl -i http://localhost:8080/
curl -i http://localhost:8080/slow?ms=500
```

You should see responses alternating between `backend-a` and `backend-b`.

