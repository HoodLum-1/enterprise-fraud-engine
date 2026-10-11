package com.capitec.connect.fraudengine.api.dto;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.EvaluationSummaryResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionRequestDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void testValidTransactionRequestDto() {
        TransactionRequestDto dto = FraudTestDataFactory.transactionRequest()
            .accountId("ACC-12345")
            .amount(new BigDecimal("150.50"))
            .category("RETAIL")
            .merchantName("Test Merchant")
            .location("Cape Town")
            .build();

        Set<ConstraintViolation<TransactionRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Should have no violations for valid dto");
    }

    @Test
    void testInvalidTransactionRequestDto_NullIdAndBlankFields() {
        TransactionRequestDto dto = FraudTestDataFactory.transactionRequest()
            .id(null)
            .accountId("")
            .amount(new BigDecimal("-10.00"))
            .currency("INVALID")
            .category("")
            .merchantName(null)
            .location(null)
            .timestamp(Instant.now().plus(1, ChronoUnit.DAYS))
            .build();

        Set<ConstraintViolation<TransactionRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("id")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("accountId")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("currency")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("category")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("timestamp")));
    }

    @Test
    void testInvalidDigitsForAmount() {
        TransactionRequestDto dto = FraudTestDataFactory.transactionRequest()
            .accountId("ACC-12345")
            .amount(new BigDecimal("123.456"))
            .currency("USD")
            .category("GROCERY")
            .merchantName("Store")
            .location("Pretoria")
            .build();

        Set<ConstraintViolation<TransactionRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
    }

    @Test
    void testResponseDtosInstantiation() {
        UUID alertId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        FraudAlertResponseDto alertDto = new FraudAlertResponseDto(
            alertId,
            txId,
            "ACC-001",
            "HighAmountRule",
            40,
            Severity.HIGH,
            "Amount exceeds threshold",
            now
        );

        assertEquals(alertId, alertDto.id());
        assertEquals(txId, alertDto.transactionId());
        assertEquals("ACC-001", alertDto.accountId());
        assertEquals("HighAmountRule", alertDto.ruleName());
        assertEquals(40, alertDto.riskScore());
        assertEquals(Severity.HIGH, alertDto.severity());
        assertEquals("Amount exceeds threshold", alertDto.reason());
        assertEquals(now, alertDto.flaggedAt());

        EvaluationSummaryResponseDto summaryDto = new EvaluationSummaryResponseDto(
            txId,
            "ACC-001",
            40,
            Severity.HIGH,
            true,
            List.of(alertDto)
        );

        assertEquals(txId, summaryDto.transactionId());
        assertEquals("ACC-001", summaryDto.accountId());
        assertEquals(40, summaryDto.totalRiskScore());
        assertEquals(Severity.HIGH, summaryDto.overallSeverity());
        assertTrue(summaryDto.isFraudulent());
        assertEquals(1, summaryDto.alerts().size());
    }
}
