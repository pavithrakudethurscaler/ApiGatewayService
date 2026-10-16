package com.ecom.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes public API paths to the microservices (HLD §2). Paths are forwarded unchanged.
 * /internal/** is deliberately not routed: service-to-service endpoints are unreachable through the gateway.
 */
@Configuration
public class RoutesConfig {
    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                               @Value("${gateway.services.product}") String productService,
                               @Value("${gateway.services.user}") String userService,
                               @Value("${gateway.services.payment}") String paymentService,
                               @Value("${gateway.services.order}") String orderService,
                               @Value("${gateway.services.cart}") String cartService,
                               @Value("${gateway.services.notification}") String notificationService) {
        return builder.routes()
                .route("product-service", r -> r
                        .path("/api/v1/products", "/api/v1/products/**", "/api/v1/categories", "/api/v1/categories/**")
                        .uri(productService))
                .route("user-service", r -> r
                        .path("/api/v1/users/**", "/api/v1/admin/users", "/api/v1/admin/users/**")
                        .uri(userService))
                .route("payment-service", r -> r
                        .path("/api/v1/payments", "/api/v1/payments/**")
                        .uri(paymentService))
                .route("order-service", r -> r
                        .path("/api/v1/orders", "/api/v1/orders/**", "/api/v1/admin/orders", "/api/v1/admin/orders/**")
                        .uri(orderService))
                .route("cart-service", r -> r
                        .path("/api/v1/cart", "/api/v1/cart/**")
                        .uri(cartService))
                .route("notification-service", r -> r
                        .path("/api/v1/notifications", "/api/v1/notifications/**")
                        .uri(notificationService))
                .build();
    }
}
