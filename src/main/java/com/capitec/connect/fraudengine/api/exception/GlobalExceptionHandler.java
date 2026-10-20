package com.capitec.connect.fraudengine.api.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        // Collect field errors first so we can log them cleanly
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }


        log.warn("Validation failed for request payload: errors={}", errors);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request validation failed"
        );
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/bad-request"));
        problemDetail.setProperty("invalidParams", errors);
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/bad-request"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid request parameter {}: {}", ex.getName(), ex.getValue());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Invalid value for request parameter: " + ex.getName()
        );
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/bad-request"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "You do not have permission to access this resource."
        );
        problemDetail.setTitle("Forbidden");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/forbidden"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(DuplicateTransactionException.class)
    public ProblemDetail handleDuplicateTransactionException(DuplicateTransactionException ex) {
        log.warn("Duplicate transaction: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );
        problemDetail.setTitle("Conflict");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/conflict"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Request conflicts with existing data"
        );
        problemDetail.setTitle("Conflict");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/conflict"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFoundException(TransactionNotFoundException ex) {
        log.warn("Transaction not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );
        problemDetail.setTitle("Not Found");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/not-found"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler({RateLimitExceededException.class, RequestNotPermitted.class})
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(Exception ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS,
            "The transaction evaluation rate limit has been exceeded. Retry after the indicated delay."
        );
        problemDetail.setTitle("Too Many Requests");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/rate-limit-exceeded"));
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(problemDetail);
    }

    @ExceptionHandler({EvaluationUnavailableException.class, CallNotPermittedException.class})
    public ResponseEntity<ProblemDetail> handleEvaluationUnavailable(Exception ex) {
        log.warn("Fraud evaluation unavailable: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Fraud evaluation is temporarily unavailable. Retry the request later."
        );
        problemDetail.setTitle("Service Unavailable");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/evaluation-unavailable"));
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "5")
            .body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception ex) {
        log.error("An unexpected error occurred", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected internal error occurred."
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("https://api.capitec.com/errors/internal-server-error"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
