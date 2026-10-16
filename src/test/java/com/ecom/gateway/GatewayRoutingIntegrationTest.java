package com.ecom.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real gateway against a JDK HttpServer standing in for the Product Service. Every other service URL
 * points at a closed port, to exercise the "service down" path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.rate-limit.enabled=false")
class GatewayRoutingIntegrationTest {
    private static final HttpServer backend;
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastInternalToken = new AtomicReference<>();
    private static final int closedPort;

    static {
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/", exchange -> {
                lastPath.set(exchange.getRequestURI().getPath());
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                lastInternalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] response = "{\"from\":\"product-backend\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            backend.start();
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        String down = "http://127.0.0.1:" + closedPort;
        registry.add("gateway.services.product", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        registry.add("gateway.services.user", () -> down);
        registry.add("gateway.services.payment", () -> down);
        registry.add("gateway.services.order", () -> down);
        registry.add("gateway.services.cart", () -> down);
        registry.add("gateway.services.notification", () -> down);
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Autowired
    WebTestClient client;

    @BeforeEach
    void reset() {
        lastPath.set(null);
        lastBody.set(null);
        lastInternalToken.set(null);
    }

    @Test
    void productRouteForwardsPathAndBodyUnchanged() {
        client.post().uri("/api/v1/products/search/reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Phone\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(RequestContextFilter.REQUEST_ID_HEADER)
                .expectBody().json("{\"from\":\"product-backend\"}");
        assertThat(lastPath.get()).isEqualTo("/api/v1/products/search/reindex");
        assertThat(lastBody.get()).isEqualTo("{\"name\":\"Phone\"}");
    }

    @Test
    void internalTokenIsNotForwardedToServices() {
        client.get().uri("/api/v1/categories").header("X-Internal-Token", "secret")
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/v1/categories");
        assertThat(lastInternalToken.get()).isNull();
    }

    @Test
    void internalPathsAreNotRouted() {
        client.post().uri("/internal/api/v1/orders/1/payment-completed").header("X-Internal-Token", "secret")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"errorMessage\":\"No such API endpoint\"}");
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void unknownPathIs404Json() {
        client.get().uri("/api/v1/nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"errorMessage\":\"No such API endpoint\"}");
    }

    @Test
    void serviceDownIs503Json() {
        client.get().uri("/api/v1/cart").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().json("{\"errorMessage\":\"Service unavailable; please retry shortly\"}");
    }

    @Test
    void corsPreflightAllowsConfiguredOriginOnly() {
        client.method(HttpMethod.OPTIONS).uri("/api/v1/products")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");

        client.method(HttpMethod.OPTIONS).uri("/api/v1/products")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden();
    }
}
