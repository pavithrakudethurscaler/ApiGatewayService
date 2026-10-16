package com.ecom.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {
    private final GatewayErrorHandler handler = new GatewayErrorHandler();

    private MockServerWebExchange handle(Throwable ex) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/cart"));
        handler.handle(exchange, ex).block();
        return exchange;
    }

    private static void assertError(MockServerWebExchange ex, HttpStatus status, String message) {
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(status);
        assertThat(ex.getResponse().getBodyAsString().block()).isEqualTo("{\"errorMessage\":\"" + message + "\"}");
    }

    @Test
    void wrappedConnectExceptionIs503() {
        assertError(handle(new RuntimeException(new ConnectException("refused"))),
                HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable; please retry shortly");
    }

    @Test
    void timeoutIs504() {
        assertError(handle(new TimeoutException("slow")), HttpStatus.GATEWAY_TIMEOUT, "Service took too long to respond");
    }

    @Test
    void notFoundIs404() {
        assertError(handle(new ResponseStatusException(HttpStatus.NOT_FOUND)), HttpStatus.NOT_FOUND, "No such API endpoint");
    }

    @Test
    void anythingElseIs502() {
        assertError(handle(new IllegalStateException("boom")), HttpStatus.BAD_GATEWAY, "Gateway error");
    }
}
