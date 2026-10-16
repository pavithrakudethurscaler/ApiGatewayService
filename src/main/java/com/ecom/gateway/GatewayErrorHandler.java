package com.ecom.gateway;

import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * Turns gateway-level failures into the platform's standard {"errorMessage": "..."} body:
 * no route → 404, service down → 503, service too slow → 504. Errors returned by the services themselves are
 * passed through untouched; this only handles failures to get a response at all.
 */
@Component
@Order(-2) // before Spring Boot's default error handler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatusCode status;
        String message;
        if (hasCause(ex, ConnectException.class) || hasCause(ex, ConnectTimeoutException.class)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Service unavailable; please retry shortly";
        } else if (hasCause(ex, TimeoutException.class)
                || (ex instanceof ResponseStatusException rse && rse.getStatusCode() == HttpStatus.GATEWAY_TIMEOUT)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "Service took too long to respond";
        } else if (ex instanceof ResponseStatusException rse && rse.getStatusCode() == HttpStatus.NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
            message = "No such API endpoint";
        } else if (ex instanceof ResponseStatusException rse) {
            status = rse.getStatusCode();
            message = rse.getReason() == null ? "Request failed" : rse.getReason();
        } else {
            status = HttpStatus.BAD_GATEWAY;
            message = "Gateway error";
        }
        log.warn("{} {} failed: {} ({})", exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                status, ex.toString());

        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = response.bufferFactory().wrap(
                ("{\"errorMessage\":\"" + message.replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
