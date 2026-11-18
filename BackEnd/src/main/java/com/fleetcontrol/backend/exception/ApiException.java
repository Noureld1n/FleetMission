package com.fleetcontrol.backend.exception;

import org.springframework.http.HttpStatusCode;

import java.util.Objects;

/** A safe, intentional API error with a stable machine-readable code. */
public final class ApiException extends RuntimeException {
    private final HttpStatusCode status;
    private final String code;

    public ApiException(HttpStatusCode status, String code, String detail) {
        super(detail);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }
}
