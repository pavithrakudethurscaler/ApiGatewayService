package com.ecom.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextFilterTest {
    private final RequestContextFilter filter = new RequestContextFilter();

    private record Result(HttpHeaders forwarded, String responseId) {
    }

    private Result run(MockServerHttpRequest.BaseBuilder<?> builder) {
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        TestChains.Recording chain = new TestChains.Recording();
        filter.filter(exchange, chain).block();
        return new Result(chain.received.get().getRequest().getHeaders(),
                exchange.getResponse().getHeaders().getFirst(RequestContextFilter.REQUEST_ID_HEADER));
    }

    @Test
    void removesInternalToken() {
        Result r = run(MockServerHttpRequest.get("/api/v1/orders").header("X-Internal-Token", "secret"));
        assertThat(r.forwarded().getFirst("X-Internal-Token")).isNull();
    }

    @Test
    void generatesRequestIdWhenMissingAndReturnsIt() {
        Result r = run(MockServerHttpRequest.get("/api/v1/orders"));
        String id = r.forwarded().getFirst(RequestContextFilter.REQUEST_ID_HEADER);
        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(r.responseId()).isEqualTo(id);
    }

    @Test
    void keepsValidClientRequestId() {
        Result r = run(MockServerHttpRequest.get("/api/v1/orders").header("X-Request-Id", "trace-abc-123"));
        assertThat(r.forwarded().getFirst(RequestContextFilter.REQUEST_ID_HEADER)).isEqualTo("trace-abc-123");
        assertThat(r.responseId()).isEqualTo("trace-abc-123");
    }

    @Test
    void replacesUnsafeClientRequestIds() {
        for (String bad : new String[]{"bad id", "<script>", "x".repeat(65)}) {
            Result r = run(MockServerHttpRequest.get("/api/v1/orders").header("X-Request-Id", bad));
            String id = r.forwarded().getFirst(RequestContextFilter.REQUEST_ID_HEADER);
            assertThat(id).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
            assertThat(r.responseId()).isEqualTo(id);
        }
    }
}
