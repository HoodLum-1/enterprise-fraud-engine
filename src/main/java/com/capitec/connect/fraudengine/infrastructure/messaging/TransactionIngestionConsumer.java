package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.engine.service.FraudEvaluationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionIngestionConsumer {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final FraudEvaluationService fraudEvaluationService;

    @KafkaListener(
        topics = "${fraud.engine.messaging.transaction-topic:transactions-topic}",
        groupId = "${spring.kafka.consumer.group-id:fraud-engine-transactions}",
        containerFactory = "transactionIngestionListenerContainerFactory")
    public void consume(String payload) {
        TransactionRequestDto request;
        try {
            request = objectMapper.readValue(payload, TransactionRequestDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid transaction event JSON", ex);
        }

        Set<ConstraintViolation<TransactionRequestDto>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException("Invalid transaction event", violations);
        }
        try {
            fraudEvaluationService.evaluate(request);
        } catch (DuplicateTransactionException duplicate) {
            log.debug("Ignoring duplicate transaction event {}", request.id());
        }
    }
}
