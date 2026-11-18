package com.fleetcontrol.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@Component
public class ProblemResponseWriter {
    private final HandlerStrategies strategies = HandlerStrategies.withDefaults();
    private final ServerResponse.Context responseContext = new ServerResponse.Context() {
        @Override
        public List<HttpMessageWriter<?>> messageWriters() {
            return strategies.messageWriters();
        }

        @Override
        public List<ViewResolver> viewResolvers() {
            return strategies.viewResolvers();
        }
    };

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(HttpStatus.resolve(status.value()) == null
                ? "Request Error" : HttpStatus.valueOf(status.value()).getReasonPhrase());
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("code", code);

        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }
}
