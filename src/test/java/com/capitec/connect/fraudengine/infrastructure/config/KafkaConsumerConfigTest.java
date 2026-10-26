package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.infrastructure.messaging.MalformedTransactionEventException;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A record that will fail identically on every redelivery has to go straight to the dead letter
 * queue, while a record that failed for a reason outside the message has to be retried with
 * backoff. Getting it backwards is expensive both ways: poison burns the retry backoff and
 * floods the log, and a transient failure gets parked in the DLQ where a redelivery would have
 * succeeded.
 */
class KafkaConsumerConfigTest {

    private static final String TRANSACTION_TOPIC = "transactions-topic";

    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final DefaultErrorHandler errorHandler =
        new KafkaConsumerConfig().transactionErrorHandler(kafkaTemplate);

    @Test
    void messagesThatCanNeverSucceedAreClassifiedAsNotRetryable() {
        assertEquals(false, classification(SerializationException.class));
        assertEquals(false, classification(MalformedTransactionEventException.class));
        assertEquals(false, classification(ConstraintViolationException.class));
        assertEquals(false, classification(UnsupportedCurrencyException.class));
    }

    @Test
    void aLockTimeoutStaysRetryableBecauseItIsContentionAndNotAPoisonMessage() {
        assertNull(classification(AccountLockTimeoutException.class));
    }

    @Test
    void genericIllegalArgumentIsNotTreatedAsPoison() {
        // Too broad to prove a message is permanently unprocessable. Classifying it would park
        // unrelated bugs in the dead letter queue without ever retrying them.
        assertNull(classification(IllegalArgumentException.class));
    }

    @Test
    void aPoisonRecordIsPublishedToTheDlqInsteadOfBeingRetried() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        errorHandler.handleOne(
            failure(new MalformedTransactionEventException("not json", new RuntimeException("boom"))),
            record(), consumer(), container());

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void aLockTimeoutIsSeekedBackForRetryInsteadOfBeingDeadLettered() {
        errorHandler.handleOne(
            failure(new AccountLockTimeoutException("ACC-1001", new RuntimeException("still locked"))),
            record(), consumer(), container());

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private ListenerExecutionFailedException failure(Exception cause) {
        return new ListenerExecutionFailedException("listener failure", cause);
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>(TRANSACTION_TOPIC, 0, 0L, "ACC-1001", "{ \"id\": \"bad\" }");
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, String> consumer() {
        return mock(Consumer.class);
    }

    private MessageListenerContainer container() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ContainerProperties containerProperties = new ContainerProperties(TRANSACTION_TOPIC);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        return container;
    }

    /**
     * {@link DefaultErrorHandler} exposes no public accessor for its classification map, so the
     * decision is read back with the public {@code removeClassification} probe: it returns the
     * value the handler currently holds, or {@code null} when the exception stays retryable.
     */
    private Boolean classification(Class<? extends Exception> exceptionType) {
        return errorHandler.removeClassification(exceptionType);
    }
}
