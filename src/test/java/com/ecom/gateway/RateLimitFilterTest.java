package com.ecom.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    private static MockServerWebExchange request(String path, String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .remoteAddress(new InetSocketAddress(ip, 5000)));
    }

    @Test
    void allowsCapacityThenRejectsWith429() {
        // Refill so slow it cannot add a token during the test
        RateLimitFilter filter = new RateLimitFilter(true, 3, 0.0001);
        TestChains.Recording chain = new TestChains.Recording();
        for (int i = 0; i < 3; i++) {
            MockServerWebExchange ex = request("/api/v1/products", "10.0.0.1");
            filter.filter(ex, chain).block();
            assertThat(ex.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        MockServerWebExchange limited = request("/api/v1/products", "10.0.0.1");
        filter.filter(limited, chain).block();

        assertThat(chain.calls).isEqualTo(3);
        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(limited.getResponse().getBodyAsString().block())
                .contains("\"errorMessage\":\"Too many requests; slow down and retry\"");
    }

    @Test
    void separateIpsHaveSeparateBuckets() {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 0.0001);
        TestChains.Recording chain = new TestChains.Recording();
        filter.filter(request("/api/v1/cart", "10.0.0.1"), chain).block();
        MockServerWebExchange other = request("/api/v1/cart", "10.0.0.2");
        filter.filter(other, chain).block();

        assertThat(chain.calls).isEqualTo(2);
        assertThat(other.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        // 100 tokens/s: after 100 ms several tokens are back. A short sleep lets real time pass without adding a
        // clock seam to production code.
        RateLimitFilter filter = new RateLimitFilter(true, 1, 100);
        TestChains.Recording chain = new TestChains.Recording();
        filter.filter(request("/api/v1/products", "10.0.0.3"), chain).block();
        Thread.sleep(100);
        filter.filter(request("/api/v1/products", "10.0.0.3"), chain).block();

        assertThat(chain.calls).isEqualTo(2);
    }

    @Test
    void paymentWebhookIsNeverLimited() {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 0.0001);
        TestChains.Recording chain = new TestChains.Recording();
        for (int i = 0; i < 5; i++) {
            MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/payments/webhook")
                    .remoteAddress(new InetSocketAddress("10.0.0.4", 5000)));
            filter.filter(ex, chain).block();
        }
        assertThat(chain.calls).isEqualTo(5);
    }

    @Test
    void disabledFilterLetsEverythingThrough() {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 0.0001);
        TestChains.Recording chain = new TestChains.Recording();
        for (int i = 0; i < 5; i++) {
            filter.filter(request("/api/v1/products", "10.0.0.5"), chain).block();
        }
        assertThat(chain.calls).isEqualTo(5);
    }
}
