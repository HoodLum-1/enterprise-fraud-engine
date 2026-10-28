package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.domain.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.engine.service.FraudEvaluationService;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransactionIngestionConsumerTest {

    @Mock
    private FraudEvaluationService fraudEvaluationService;

    private TransactionIngestionConsumer consumer;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private TransactionRequestDto request;

    @BeforeEach
    void setUp() {
        consumer = new TransactionIngestionConsumer(objectMapper, validator, fraudEvaluationService);
        request = FraudTestDataFactory.transactionRequest()
            .amount(new BigDecimal("150.00"))
            .build();
    }

    @Test
    void aPayloadThatIsNotJsonIsReportedAsMalformed() {
        MalformedTransactionEventException thrown =
            assertThrows(MalformedTransactionEventException.class, () -> consumer.consume("{ not json"));

        assertInstanceOf(JsonProcessingException.class, thrown.getCause());
        verifyNoInteractions(fraudEvaluationService);
    }

    @Test
    void aValidationFailureIsReportedAsAConstraintViolation() throws Exception {
        String payload = objectMapper.writeValueAsString(
            FraudTestDataFactory.transactionRequest().accountId("").amount(new BigDecimal("-5.00")).build());

        assertThrows(ConstraintViolationException.class, () -> consumer.consume(payload));

        verifyNoInteractions(fraudEvaluationService);
    }

    @Test
    void anUnsupportedCurrencyPropagatesUnchangedSoItCanBeDeadLettered() {
        UnsupportedCurrencyException unsupported = new UnsupportedCurrencyException("GBP");
        doThrow(unsupported).when(fraudEvaluationService).evaluate(request);

        UnsupportedCurrencyException thrown = assertThrows(
            UnsupportedCurrencyException.class, () -> consumer.consume(asJson(request)));

        assertSame(unsupported, thrown);
    }

    @Test
    void aLockTimeoutPropagatesUnwrappedSoTheHandlerRetriesItInsteadOfPoisoning() {
        AccountLockTimeoutException timeout =
            new AccountLockTimeoutException("ACC-1001", new RuntimeException("still locked"));
        doThrow(timeout).when(fraudEvaluationService).evaluate(request);

        AccountLockTimeoutException thrown = assertThrows(
            AccountLockTimeoutException.class, () -> consumer.consume(asJson(request)));

        assertSame(timeout, thrown);
    }

    @Test
    void aDuplicateIsSwallowedBecauseTheWorkIsAlreadyDone() {
        doThrow(new DuplicateTransactionException(request.id())).when(fraudEvaluationService).evaluate(request);

        consumer.consume(asJson(request));

        verify(fraudEvaluationService).evaluate(request);
    }

    private String asJson(TransactionRequestDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
