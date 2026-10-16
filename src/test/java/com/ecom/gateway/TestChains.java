package com.ecom.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/** A GatewayFilterChain stub that records the exchange it was called with. */
class TestChains {
    static class Recording implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> received = new AtomicReference<>();
        int calls;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            calls++;
            received.set(exchange);
            return Mono.empty();
        }
    }
}
