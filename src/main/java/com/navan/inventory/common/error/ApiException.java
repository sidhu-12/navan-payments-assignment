package com.navan.inventory.common.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String existingId;

    private ApiException(HttpStatus status, String message, String existingId) {
        super(message);
        this.status = status;
        this.existingId = existingId;
    }

    public HttpStatus status() {
        return status;
    }

    public String existingId() {
        return existingId;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, null);
    }

    public static ApiException conflict(String message, String existingId) {
        return new ApiException(HttpStatus.CONFLICT, message, existingId);
    }
}
