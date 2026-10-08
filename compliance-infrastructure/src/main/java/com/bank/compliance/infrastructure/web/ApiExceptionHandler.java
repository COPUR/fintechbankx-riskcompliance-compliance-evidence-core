package com.bank.compliance.infrastructure.web;

import com.bank.compliance.application.TransactionAlreadyScreenedException;
import com.bank.compliance.domain.ScreeningAlreadyRecordedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TransactionAlreadyScreenedException.class)
    ResponseEntity<ErrorResponse> alreadyScreened(TransactionAlreadyScreenedException ex) {
        return error(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_SCREENED", ex.getMessage());
    }

    /**
     * Two concurrent first screenings of one transaction: the unique
     * transaction_id constraint let the other one through. A retry returns
     * its result (or a 409 TRANSACTION_ALREADY_SCREENED if the facts differ).
     */
    @ExceptionHandler(ScreeningAlreadyRecordedException.class)
    ResponseEntity<ErrorResponse> duplicate(ScreeningAlreadyRecordedException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "This transaction is already being screened; retry");
    }

    /**
     * Any other integrity failure means a request passed validation but not a
     * database constraint: a defect, not a client error. Details stay in the log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrityFailure(DataIntegrityViolationException ex) {
        log.error("Screening could not be stored", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The screening could not be stored");
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
