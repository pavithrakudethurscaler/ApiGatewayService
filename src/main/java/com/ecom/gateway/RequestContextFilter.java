package com.ecom.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs first on every request:
 * - removes X-Internal-Token, so outside callers can never present the service-to-service secret
 * - gives every request an X-Request-Id (kept if the client sent a valid one), passed to the service and
 *   returned to the client, so one request can be traced across logs
 * - writes one access-log line per request
 */
@Component
public class RequestContextFilter implements GlobalFilter, Ordered {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        // Only reuse client ids that cannot inject anything into logs or headers
        String requestId = incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-Internal-Token");
                    headers.set(REQUEST_ID_HEADER, requestId);
                })
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> log.info("{} {} {} -> {} in {} ms",
                        requestId, request.getMethod(), request.getPath().value(),
                        exchange.getResponse().getStatusCode(), System.currentTimeMillis() - start));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
