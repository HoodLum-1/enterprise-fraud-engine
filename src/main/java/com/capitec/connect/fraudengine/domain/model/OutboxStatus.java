package com.capitec.connect.fraudengine.domain.model;

/**
 * Lifecycle of a row in the transactional outbox.
 *
 * <p>{@code PENDING} rows are waiting to be published, {@code PUBLISHED} rows have been handed to
 * Kafka, and {@code FAILED} rows exhausted their retry budget and were parked for investigation.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
