package com.ecom.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-client-IP rate limiting (HLD §2) with a token bucket: each IP can burst up to {@code capacity} requests,
 * refilled at {@code refillPerSecond}. Over the limit returns 429 with Retry-After.
 * <p>
 * In memory, so the limit is per gateway instance; use the Redis-backed RequestRateLimiter when running several.
 * The client IP is the TCP peer address; behind a load balancer, configure trusted X-Forwarded-For handling first.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
    private static final long IDLE_BUCKET_MS = 10 * 60_000;

    private final boolean enabled;
    private final double capacity;
    private final double refillPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();

    public RateLimitFilter(@Value("${gateway.rate-limit.enabled:true}") boolean enabled,
                           @Value("${gateway.rate-limit.capacity:100}") int capacity,
                           @Value("${gateway.rate-limit.refill-per-second:20}") double refillPerSecond) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Payment gateway webhooks are signed and retried by the provider; never throttle them
        if (!enabled || exchange.getRequest().getPath().value().equals("/api/v1/payments/webhook")) {
            return chain.filter(exchange);
        }
        long now = System.currentTimeMillis();
        if (requestCount.incrementAndGet() % 1000 == 0) {
            buckets.values().removeIf(b -> now - b.lastSeen > IDLE_BUCKET_MS);
        }

        Bucket bucket = buckets.computeIfAbsent(clientIp(exchange), ip -> new Bucket(capacity, now));
        if (bucket.tryConsume(now, capacity, refillPerSecond)) {
            return chain.filter(exchange);
        }

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf((long) Math.ceil(1 / refillPerSecond)));
        DataBuffer body = response.bufferFactory().wrap(
                "{\"errorMessage\":\"Too many requests; slow down and retry\"}".getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastSeen;

        Bucket(double capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        synchronized boolean tryConsume(long now, double capacity, double refillPerSecond) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) / 1000.0 * refillPerSecond);
            lastRefill = now;
            lastSeen = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }
}
