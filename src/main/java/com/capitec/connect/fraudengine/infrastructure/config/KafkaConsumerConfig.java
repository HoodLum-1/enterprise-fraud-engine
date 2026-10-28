package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.infrastructure.messaging.MalformedTransactionEventException;
import com.capitec.connect.fraudengine.infrastructure.messaging.TransactionEvaluatedEvent;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, TransactionEvaluatedEvent> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, TransactionEvaluatedEventDeserializer.class.getName());

        return new DefaultKafkaConsumerFactory<>(
                configs,
                new StringDeserializer(),
                new TransactionEvaluatedEventDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEvaluatedEvent> kafkaListenerContainerFactory(
            @Qualifier("consumerFactory") ConsumerFactory<String, TransactionEvaluatedEvent> consumerFactory,
            DefaultErrorHandler transactionErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, TransactionEvaluatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        configureListenerContainerFactory(factory, transactionErrorHandler);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, String> transactionIngestionConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(configs, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> transactionIngestionListenerContainerFactory(
            @Qualifier("transactionIngestionConsumerFactory")ConsumerFactory<String, String> transactionIngestionConsumerFactory,
            DefaultErrorHandler transactionErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionIngestionConsumerFactory);
        configureListenerContainerFactory(factory, transactionErrorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler transactionErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(30000L);
        backOff.setMaxElapsedTime(60000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + "-dlq", -1));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                SerializationException.class,
                MalformedTransactionEventException.class,
                ConstraintViolationException.class,
                UnsupportedCurrencyException.class);
        return errorHandler;
    }

    private void configureListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory,
            DefaultErrorHandler errorHandler) {
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class.getName());
        return new DefaultKafkaProducerFactory<>(configs);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    public static class TransactionEvaluatedEventDeserializer
            implements org.apache.kafka.common.serialization.Deserializer<TransactionEvaluatedEvent> {

        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {}

        @Override
        public TransactionEvaluatedEvent deserialize(String topic, byte[] data) {
            try {
                if (data == null) {
                    return null;
                }
                return objectMapper.readValue(data, TransactionEvaluatedEvent.class);
            } catch (Exception e) {
                throw new SerializationException(
                        "Failed to deserialize TransactionEvaluatedEvent from topic " + topic + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {}
    }
}