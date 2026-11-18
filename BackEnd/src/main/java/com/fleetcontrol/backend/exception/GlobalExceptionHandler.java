package com.fleetcontrol.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MeterRegistry meters;
    public GlobalExceptionHandler(MeterRegistry meters) { this.meters=meters; }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, ServerWebExchange exchange) {
        ProblemDetail problem = problem(exception.status(), exception.getMessage(), exchange);
        problem.setTitle(titleFor(exception.status()));
        problem.setProperty("code", exception.code());
        return response(exception.status(), problem);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> handleBindingException(
            WebExchangeBindException exception, ServerWebExchange exchange) {
        countRejectedIngestion(exchange);
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed.", exchange);
        problem.setTitle("Bad Request");
        Map<String, List<String>> errors = exception.getFieldErrors().stream()
                .collect(Collectors.groupingBy(
                        FieldError::getField,
                        Collectors.mapping(error -> error.getDefaultMessage() == null
                                ? "Invalid value." : error.getDefaultMessage(), Collectors.toList())));
        problem.setProperty("errors", errors);
        problem.setProperty("code", "VALIDATION_FAILED");
        return response(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception, ServerWebExchange exchange) {
        countRejectedIngestion(exchange);
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed.", exchange);
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        return response(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalidInput(ServerWebInputException exception,ServerWebExchange exchange) {
        countRejectedIngestion(exchange);
        ProblemDetail problem=problem(HttpStatus.BAD_REQUEST,"Request body or parameter is invalid.",exchange);
        problem.setTitle("Bad Request");
        problem.setProperty("code","INVALID_REQUEST");
        return response(HttpStatus.BAD_REQUEST,problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(
            AuthenticationException exception, ServerWebExchange exchange) {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED,
                "Authentication is required or invalid.", exchange);
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "AUTHENTICATION_REQUIRED");
        return response(HttpStatus.UNAUTHORIZED, problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException exception, ServerWebExchange exchange) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN,
                "You are not allowed to perform this action.", exchange);
        problem.setTitle("Forbidden");
        problem.setProperty("code", "ACCESS_DENIED");
        return response(HttpStatus.FORBIDDEN, problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException exception, ServerWebExchange exchange) {
        HttpStatusCode status = exception.getStatusCode();
        String detail = status.is4xxClientError()
                ? "The request could not be processed."
                : "An unexpected error occurred.";
        ProblemDetail problem = problem(status, detail, exchange);
        problem.setTitle(titleFor(status));
        problem.setProperty("code", status.is4xxClientError() ? "REQUEST_REJECTED" : "SERVER_ERROR");
        return response(status, problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, ServerWebExchange exchange) {
        log.error("Unhandled request failure for {}", exchange.getRequest().getPath(), exception);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.", exchange);
        problem.setTitle("Internal Server Error");
        problem.setProperty("code", "SERVER_ERROR");
        return response(HttpStatus.INTERNAL_SERVER_ERROR, problem);
    }

    private static ProblemDetail problem(HttpStatusCode status, String detail, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatusCode status, ProblemDetail problem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String titleFor(HttpStatusCode status) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        return knownStatus == null ? "Request Error" : knownStatus.getReasonPhrase();
    }
    private void countRejectedIngestion(ServerWebExchange exchange) {
        if(exchange.getRequest().getPath().value().startsWith("/ingest/v1/telemetry")) {
            meters.counter("ingest.events.received").increment();
            meters.counter("ingest.events.rejected").increment();
            meters.counter("ingest.requests.rejected").increment();
        }
    }
}
