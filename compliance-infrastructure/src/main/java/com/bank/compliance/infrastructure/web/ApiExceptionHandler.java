package com.bank.compliance.infrastructure.web;

import com.bank.compliance.application.TransactionAlreadyScreenedException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps application and domain exceptions to the ErrorResponse shape of
 * compliance-context.yaml: a stable code, a message and the interaction id.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TransactionAlreadyScreenedException.class)
    ResponseEntity<ErrorResponse> alreadyScreened(TransactionAlreadyScreenedException ex) {
        return error(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_SCREENED", ex.getMessage());
    }

    /** Two concurrent first screenings of one transaction: the database lets one through. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> duplicate(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "This transaction is already being screened; retry");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = switch (ex) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getAllErrors().stream()
                    .map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request");
            case HttpMessageNotReadableException unreadable -> "Malformed request body";
            default -> ex.getMessage();
        };
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
