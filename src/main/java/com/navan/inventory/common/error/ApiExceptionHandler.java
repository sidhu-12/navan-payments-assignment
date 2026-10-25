package com.navan.inventory.common.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.navan.inventory.common.NavanFeeKind;
import com.navan.inventory.common.TransactionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.getMessage(), ex.existingId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(new ErrorResponse(message, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(new ErrorResponse(iae.getMessage(), null));
        }
        if (cause instanceof InvalidFormatException ife) {
            Class<?> target = ife.getTargetType();
            if (target == TransactionType.class || target == NavanFeeKind.class) {
                return ResponseEntity.badRequest().body(new ErrorResponse("unknown value: " + ife.getValue(), null));
            }
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("malformed JSON", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict with stored data", null));
    }
}
