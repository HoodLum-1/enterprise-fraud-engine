package com.capitec.connect.fraudengine.api.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void testHandleValidationException() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "amount", "Amount must be positive"));

        MethodParameter parameter = new MethodParameter(
            GlobalExceptionHandlerTest.class.getDeclaredMethod("setUp"),
            -1
        );
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ProblemDetail problemDetail = exceptionHandler.handleValidationException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.BAD_REQUEST.value(), problemDetail.getStatus());
        assertEquals("Bad Request", problemDetail.getTitle());
        assertEquals("Request validation failed", problemDetail.getDetail());
        assertNotNull(problemDetail.getProperties());
        assertTrue(problemDetail.getProperties().containsKey("invalidParams"));
        @SuppressWarnings("unchecked")
        Map<String, String> invalidParams = (Map<String, String>) problemDetail.getProperties().get("invalidParams");
        assertEquals("Amount must be positive", invalidParams.get("amount"));
    }

    @Test
    void testHandleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument provided");

        ProblemDetail problemDetail = exceptionHandler.handleIllegalArgumentException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.BAD_REQUEST.value(), problemDetail.getStatus());
        assertEquals("Bad Request", problemDetail.getTitle());
        assertEquals("Invalid argument provided", problemDetail.getDetail());
    }

    @Test
    void testHandleDuplicateTransactionException() {
        UUID id = UUID.randomUUID();
        DuplicateTransactionException ex = new DuplicateTransactionException(id);

        ProblemDetail problemDetail = exceptionHandler.handleDuplicateTransactionException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.CONFLICT.value(), problemDetail.getStatus());
        assertEquals("Conflict", problemDetail.getTitle());
        assertEquals("Transaction already exists with id: " + id, problemDetail.getDetail());
    }

    @Test
    void testHandleTransactionNotFoundException() {
        UUID id = UUID.randomUUID();
        TransactionNotFoundException ex = new TransactionNotFoundException(id);

        ProblemDetail problemDetail = exceptionHandler.handleTransactionNotFoundException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.NOT_FOUND.value(), problemDetail.getStatus());
        assertEquals("Not Found", problemDetail.getTitle());
        assertEquals("Transaction not found with id: " + id, problemDetail.getDetail());
    }

    @Test
    void testHandleDataIntegrityViolationException() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key value violates unique constraint");

        ProblemDetail problemDetail = exceptionHandler.handleDataIntegrityViolationException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.CONFLICT.value(), problemDetail.getStatus());
        assertEquals("Conflict", problemDetail.getTitle());
        assertEquals("Request conflicts with existing data", problemDetail.getDetail());
    }

    @Test
    void testHandleGeneralException() {
        Exception ex = new RuntimeException("Unexpected error");

        ProblemDetail problemDetail = exceptionHandler.handleGeneralException(ex);

        assertNotNull(problemDetail);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), problemDetail.getStatus());
        assertEquals("Internal Server Error", problemDetail.getTitle());
        assertEquals("An unexpected internal error occurred.", problemDetail.getDetail());
    }

    @Test
    void rateLimitIs429WithRetryAfter() {
        var response = exceptionHandler.handleRateLimitExceeded(new RateLimitExceededException(new RuntimeException()));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals(429, response.getBody().getStatus());
    }

    @Test
    void evaluationUnavailableIs503WithRetryAfter() {
        var response = exceptionHandler.handleEvaluationUnavailable(new EvaluationUnavailableException(new RuntimeException()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(503, response.getBody().getStatus());
    }
}
