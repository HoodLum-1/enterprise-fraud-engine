package com.capitec.connect.fraudengine.infrastructure.metrics;

import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the messages sitting in the dead letter letter topics.
 *
 * <p>The dead letter recoverer publishes to {@code <source-topic>-dlq}. The metric is the sum of
 * the end offsets of those topics, which is the number of messages that have been dead lettered
 * and are still waiting there.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "fraud.engine.messaging", name = "dlq-metrics-enabled",
    havingValue = "true", matchIfMissing = true)
public class KafkaDlqMetrics {

    private static final String DLQ_SUFFIX = "-dlq";
    private static final long ADMIN_TIMEOUT_SECONDS = 5;

    private final ObjectProvider<KafkaAdmin> kafkaAdminProvider;
    private final FraudEngineProperties properties;
    private final AtomicLong deadLetteredMessages = new AtomicLong();
    private volatile AdminClient adminClient;

    public KafkaDlqMetrics(ObjectProvider<KafkaAdmin> kafkaAdminProvider,
                            FraudEngineProperties properties,
                            MeterRegistry meterRegistry) {
        this.kafkaAdminProvider = kafkaAdminProvider;
        this.properties = properties;
        Gauge.builder("fraud.kafka.dlq.messages", deadLetteredMessages, AtomicLong::get)
            .description("Messages currently sitting in the dead letter topics")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${fraud.engine.messaging.dlq-metrics-refresh-ms:30000}",
        initialDelayString = "${fraud.engine.messaging.dlq-metrics-refresh-ms:30000}")
    public void refresh() {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        long total = 0;
        total += countMessages(messaging.getEvaluationTopic() + DLQ_SUFFIX);
        total += countMessages(messaging.getAccountActionsTopic() + DLQ_SUFFIX);
        total += countMessages(messaging.getTransactionTopic() + DLQ_SUFFIX);
        deadLetteredMessages.set(total);
    }

    private long countMessages(String topic) {
        KafkaAdmin kafkaAdmin = kafkaAdminProvider.getIfAvailable();
        if (kafkaAdmin == null) {
            return 0;
        }
        try {
            AdminClient admin = adminClient(kafkaAdmin);
            Map<String, TopicDescription> descriptions =
                admin.describeTopics(List.of(topic)).allTopicNames().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            TopicDescription description = descriptions.get(topic);
            if (description == null) {
                return 0;
            }

            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            for (TopicPartitionInfo partition : description.partitions()) {
                request.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets =
                admin.listOffsets(request).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return offsets.values().stream().mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset).sum();
        } catch (Exception ex) {
            // The topic may not exist yet, and the broker may be briefly unavailable.
            log.debug("Could not read the dead letter depth of {}: {}", topic, ex.getMessage());
            return 0;
        }
    }

    private AdminClient adminClient(KafkaAdmin kafkaAdmin) {
        AdminClient client = adminClient;
        if (client == null) {
            synchronized (this) {
                client = adminClient;
                if (client == null) {
                    client = AdminClient.create(kafkaAdmin.getConfigurationProperties());
                    adminClient = client;
                }
            }
        }
        return client;
    }
}
